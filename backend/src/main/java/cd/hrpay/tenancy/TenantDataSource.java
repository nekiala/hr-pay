package cd.hrpay.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * The app's database connections: each one is told the current company before use, and runs as the hrpay_app role
 * when the app signs in as a superuser or owner, so that row-level security applies to it.
 */
public class TenantDataSource extends DelegatingDataSource {

    private static final Logger log = LoggerFactory.getLogger(TenantDataSource.class);
    static final String APP_ROLE = "hrpay_app";

    /** "hrpay_app" or "none"; null until the role exists (it is made by a migration). */
    private volatile String role;
    private volatile boolean warned;

    public TenantDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return prepare(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return prepare(super.getConnection(username, password));
    }

    private Connection prepare(Connection connection) throws SQLException {
        try {
            apply(connection, TenantContext.scope());
            return connection;
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    /** Tells the connection which company it works for; also used when the company changes inside a transaction. */
    void apply(Connection connection, TenantContext.Scope scope) throws SQLException {
        String r = role(connection);
        try (PreparedStatement s = connection.prepareStatement(
                "select set_config('role', ?, false), set_config('app.company_id', ?, false), set_config('app.bypass', ?, false)")) {
            s.setString(1, r);
            s.setString(2, scope.companyId() == null ? "" : scope.companyId().toString());
            s.setString(3, scope.bypass() ? "on" : "off");
            s.execute();
        }
    }

    private String role(Connection connection) throws SQLException {
        String r = role;
        if (r != null) {
            return r;
        }
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("""
                     select (select rolsuper or rolbypassrls from pg_roles where rolname = session_user),
                            exists (select 1 from pg_roles where rolname = '%s'),
                            exists (select 1 from pg_roles where rolname = '%s') and pg_has_role(session_user, '%s', 'MEMBER')
                     """.formatted(APP_ROLE, APP_ROLE, APP_ROLE))) {
            rs.next();
            boolean skipsPolicies = rs.getBoolean(1);
            boolean exists = rs.getBoolean(2);
            boolean member = rs.getBoolean(3);
            if (!exists) {
                // Before the migration that makes it: nothing to switch to yet; check again next time.
                return "none";
            }
            if (skipsPolicies && !member && !warned) {
                warned = true;
                log.warn("The database user skips row-level security and cannot use the {} role: companies are not "
                        + "kept apart by the database. Grant {} to it, or use a non-superuser owner.", APP_ROLE, APP_ROLE);
            }
            role = member ? APP_ROLE : "none";
            return role;
        }
    }
}
