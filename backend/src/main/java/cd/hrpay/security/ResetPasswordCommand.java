package cd.hrpay.security;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/**
 * The on-premise last resort, run on the server when nobody can sign in as an administrator:
 * {@code docker compose exec api java -jar /app/app.jar reset-password <email> [--second-step]}.
 * Sets a temporary password, printed once, to be changed at the next sign-in, signs the person out everywhere and
 * lifts a lockout; --second-step also removes their second step. Written in the company's audit log. It uses the
 * database settings of the app (DB_URL, DB_USER, DB_PASSWORD) and does not start the app.
 */
public final class ResetPasswordCommand {

    private ResetPasswordCommand() {}

    public static int run(String[] args) {
        String url = env("DB_URL", "jdbc:postgresql://localhost:5432/hrpay");
        try (Connection c = DriverManager.getConnection(url, env("DB_USER", "hrpay"), env("DB_PASSWORD", "hrpay"))) {
            return run(args, c, System.out);
        } catch (SQLException e) {
            System.err.println("Could not reach the database: " + e.getMessage());
            return 2;
        }
    }

    /** With a database connection as the owner; prints the outcome to out. */
    public static int run(String[] args, Connection c, java.io.PrintStream out) {
        String email = null;
        boolean secondStep = false;
        for (String a : args) {
            if (a.equals("--second-step")) {
                secondStep = true;
            } else if (!a.startsWith("--") && email == null) {
                email = a.trim().toLowerCase();
            } else {
                return usage();
            }
        }
        if (email == null || email.isBlank()) {
            return usage();
        }
        try {
            c.setAutoCommit(false);
            set(c, "app.bypass", "on");
            long userId;
            long companyId;
            String status;
            try (PreparedStatement s = c.prepareStatement("select id, company_id, status from app_user where lower(email) = ?")) {
                s.setString(1, email);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) {
                        System.err.println("No account with the email " + email + ".");
                        return 1;
                    }
                    userId = r.getLong(1);
                    companyId = r.getLong(2);
                    status = r.getString(3);
                }
            }
            if (!"ACTIVE".equals(status)) {
                System.err.println("This account is " + status.toLowerCase() + "; reactivate it from the app first.");
                return 1;
            }
            // The company's own rows from here, so the audit entry is written in its log.
            set(c, "app.company_id", Long.toString(companyId));
            String password = temporaryPassword();
            update(c, "update app_user set password_hash = ?, must_change_password = true where id = ?",
                    PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(password), userId);
            update(c, "delete from login_failure where email = ?", email);
            update(c, "update password_reset set used_at = now() where user_id = ? and used_at is null", userId);
            update(c, "delete from spring_session where session_id in (select session_id from user_session where user_id = ? and ended_at is null)", userId);
            update(c, "update user_session set ended_at = now(), ended_by = 'server command' where user_id = ? and ended_at is null", userId);
            if (secondStep) {
                update(c, "delete from user_two_step where user_id = ?", userId);
            }
            update(c, """
                    insert into audit_log (actor, action, entity_type, entity_id, details)
                    values ('server command', 'user.password_reset_on_server', 'user', ?, cast(? as jsonb))""",
                    Long.toString(userId), "{\"secondStepReset\": " + secondStep + "}");
            c.commit();
            out.println("New temporary password for " + email + ": " + password);
            out.println("It is asked to be changed at the next sign-in." + (secondStep
                    ? " The second step was removed; it is set up again at sign-in if the person's role requires it." : ""));
            return 0;
        } catch (SQLException e) {
            try {
                c.rollback();
            } catch (SQLException ignored) {
                // the connection is closed by the caller
            }
            System.err.println("Could not reset the password: " + e.getMessage());
            return 2;
        }
    }

    private static int usage() {
        System.err.println("Usage: java -jar app.jar reset-password <email> [--second-step]");
        return 64;
    }

    private static void set(Connection c, String name, String value) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("select set_config(?, ?, true)")) {
            s.setString(1, name);
            s.setString(2, value);
            s.execute();
        }
    }

    private static void update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                s.setObject(i + 1, params[i]);
            }
            s.executeUpdate();
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    static String temporaryPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder b = new StringBuilder("Hp");
        for (int i = 0; i < 10; i++) {
            b.append(LETTERS.charAt(random.nextInt(LETTERS.length())));
        }
        return b.append("7!").toString();
    }
}
