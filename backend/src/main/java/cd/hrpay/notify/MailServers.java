package cd.hrpay.notify;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.AppProperties;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.security.DataCrypto;
import cd.hrpay.tenancy.TenantContext;
import cd.hrpay.tenancy.Tenants;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.io.UnsupportedEncodingException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The email server that sends a company's emails. In order: the company's own server when it is turned on, the
 * platform's server (set by the provider; it takes over from .env), the SMTP_* settings of .env, or none (the email
 * is only written on the Messages page).
 */
@Service
public class MailServers {

    public enum Level { COMPANY, PLATFORM, ENV, NONE }

    /** The settings as the pages show them: the password never leaves the server, only whether one is set. */
    public record Settings(boolean enabled, String protocol, String host, int port, String encryption,
                           String fromAddress, String fromName, String bcc, String username, boolean passwordSet,
                           String ehloDomain, String updatedBy, OffsetDateTime updatedAt) {}

    /** What a page sends. An empty password keeps the one already saved. */
    public record Input(boolean enabled, String protocol, String host, Integer port, String encryption,
                        String fromAddress, String fromName, String bcc, String username, String password,
                        String ehloDomain) {}

    /** What the company's Email page shows: its own settings, and which server sends its emails now. */
    public record CompanyView(Settings settings, Level inUse, String inUseFrom) {}

    /** A server ready to send, with the sender to show and the hidden copy (none for security emails). */
    public record Resolved(Level level, JavaMailSender sender, InternetAddress from, String bcc) {}

    record Row(boolean enabled, String protocol, String host, int port, String encryption, String fromAddress,
                       String fromName, String bcc, String username, String password, String ehloDomain,
                       String updatedBy, OffsetDateTime updatedAt) {
        Settings view() {
            return new Settings(enabled, protocol, host, port, encryption, fromAddress, fromName, bcc, username,
                    password != null && !password.isEmpty(), ehloDomain, updatedBy, updatedAt);
        }
    }

    static final int TIMEOUT_MS = 10_000;
    private static final String COLUMNS = """
            enabled, protocol, host, port, encryption, from_address, from_name, bcc, username, password, ehlo_domain,
            updated_by, updated_at""";

    private final JdbcClient jdbc;
    private final Tenants tenants;
    private final DataCrypto crypto;
    private final AuditService audit;
    private final ObjectProvider<JavaMailSender> envSender;
    private final ObjectProvider<Notifications> notifications;
    private final AppProperties.Notifications env;
    /** Senders built from saved settings, by level, company and save time: a new save builds a new one. */
    private final Map<String, JavaMailSender> senders = new ConcurrentHashMap<>();

    public MailServers(JdbcClient jdbc, Tenants tenants, DataCrypto crypto, AuditService audit,
                       ObjectProvider<JavaMailSender> envSender, ObjectProvider<Notifications> notifications,
                       AppProperties properties) {
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.crypto = crypto;
        this.audit = audit;
        this.envSender = envSender;
        this.env = properties.notifications();
    }

    // ---- choosing the server ------------------------------------------------------------------------------------

    /** The server for an email to someone of this company. */
    public Resolved resolve(long companyId, boolean security) {
        Optional<Row> own = companyRow(companyId).filter(Row::enabled);
        if (own.isPresent()) {
            Row r = own.get();
            return new Resolved(Level.COMPANY, sender("C" + companyId, r), address(r.fromAddress(), r.fromName()),
                    security ? null : r.bcc());
        }
        String company = TenantContext.isProvider(companyId) ? null : companyName(companyId);
        Optional<Row> platform = platformRow().filter(Row::enabled);
        if (platform.isPresent()) {
            Row r = platform.get();
            return new Resolved(Level.PLATFORM, sender("P", r), address(r.fromAddress(), via(company, r.fromName())),
                    security ? null : r.bcc());
        }
        JavaMailSender fromEnv = envSender.getIfAvailable();
        if (fromEnv != null) {
            InternetAddress from = envFrom();
            return new Resolved(Level.ENV, fromEnv, address(from.getAddress(), via(company, from.getPersonal())), null);
        }
        return new Resolved(Level.NONE, null, null, null);
    }

    /** Whether emails to this company's people can leave at all (codes by email are only offered then). */
    public boolean available(long companyId) {
        return companyRow(companyId).filter(Row::enabled).isPresent() || platformRow().filter(Row::enabled).isPresent()
                || envSender.getIfAvailable() != null;
    }

    /** After a failure of the company's own server: tells its administrators in the app, at most once an hour. */
    public void companyServerFailed(long companyId, String error) {
        tenants.runAs(companyId, () -> {
            int claimed = jdbc.sql("""
                    update company_mail_server set alerted_at = now()
                    where alerted_at is null or alerted_at < now() - interval '1 hour'""").update();
            if (claimed == 0) {
                return;
            }
            Notifications inbox = notifications.getObject();
            for (Long id : inbox.holders("company.manage")) {
                inbox.send(id, "MAIL_FAILED", () -> Texts.t("Your email server failed"),
                        () -> Texts.t("Emails cannot leave through your email server: %s", cut(error, 300)), "/company/email");
            }
        });
    }

    // ---- the company's settings ---------------------------------------------------------------------------------

    public CompanyView company() {
        long companyId = TenantContext.companyId();
        Settings own = companyRow(companyId).map(Row::view).orElse(null);
        Resolved r = resolve(companyId, false);
        return new CompanyView(own, r.level(), r.from() == null ? null : r.from().toUnicodeString());
    }

    @Transactional
    public CompanyView saveCompany(Input in, String by) {
        long companyId = TenantContext.companyId();
        Row saved = checked(in, companyRow(companyId).map(Row::password).orElse(null));
        jdbc.sql("""
                insert into company_mail_server (enabled, protocol, host, port, encryption, from_address, from_name, bcc,
                                                 username, password, ehlo_domain, updated_by, updated_at)
                values (:enabled, :protocol, :host, :port, :encryption, :fromAddress, :fromName, :bcc, :username,
                        :password, :ehlo, :by, now())
                on conflict (company_id) do update set enabled = excluded.enabled, protocol = excluded.protocol,
                    host = excluded.host, port = excluded.port, encryption = excluded.encryption,
                    from_address = excluded.from_address, from_name = excluded.from_name, bcc = excluded.bcc,
                    username = excluded.username, password = excluded.password, ehlo_domain = excluded.ehlo_domain,
                    alerted_at = null, updated_by = excluded.updated_by, updated_at = now()""")
                .params(params(saved, by)).update();
        audit.record("mail_server.company_saved", "company_mail_server", companyId, details(saved));
        return company();
    }

    // ---- the platform's settings --------------------------------------------------------------------------------

    public Settings platform() {
        return platformRow().map(Row::view).orElse(null);
    }

    @Transactional
    public Settings savePlatform(Input in, String by) {
        Row saved = checked(in, platformRow().map(Row::password).orElse(null));
        jdbc.sql("""
                insert into platform_mail_server (id, enabled, protocol, host, port, encryption, from_address, from_name,
                                                  bcc, username, password, ehlo_domain, updated_by, updated_at)
                values (1, :enabled, :protocol, :host, :port, :encryption, :fromAddress, :fromName, :bcc, :username,
                        :password, :ehlo, :by, now())
                on conflict (id) do update set enabled = excluded.enabled, protocol = excluded.protocol,
                    host = excluded.host, port = excluded.port, encryption = excluded.encryption,
                    from_address = excluded.from_address, from_name = excluded.from_name, bcc = excluded.bcc,
                    username = excluded.username, password = excluded.password, ehlo_domain = excluded.ehlo_domain,
                    updated_by = excluded.updated_by, updated_at = now()""")
                .params(params(saved, by)).update();
        audit.record("mail_server.platform_saved", "platform_mail_server", 1, details(saved));
        return platform();
    }

    // ---- trying settings before saving them ---------------------------------------------------------------------

    /** Connects and signs in with the form's values (the saved password when the field is empty); sends nothing. */
    public String testConnection(Input in, boolean platform) {
        JavaMailSenderImpl sender = build(checked(in, storedPassword(platform)));
        try {
            sender.testConnection();
            return "Connected to " + sender.getHost() + ":" + sender.getPort() + ".";
        } catch (MessagingException e) {
            throw new BusinessRuleException("The server refused the connection: " + cut(rootMessage(e), 400));
        }
    }

    /** Sends one email with the form's values to the person at the keyboard. */
    public String sendTest(Input in, boolean platform, String to, String subject, String body) {
        Row r = checked(in, storedPassword(platform));
        JavaMailSenderImpl sender = build(r);
        try {
            var message = sender.createMimeMessage();
            var helper = new org.springframework.mail.javamail.MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(address(r.fromAddress(), r.fromName()));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body);
            sender.send(message);
            return "A test email was sent to " + to + ".";
        } catch (MessagingException | RuntimeException e) {
            throw new BusinessRuleException("The test email could not be sent: " + cut(rootMessage(e), 400));
        }
    }

    private String storedPassword(boolean platform) {
        return (platform ? platformRow() : companyRow(TenantContext.companyId())).map(Row::password).orElse(null);
    }

    // ---- building a sender --------------------------------------------------------------------------------------

    private JavaMailSender sender(String scope, Row r) {
        String key = scope + "@" + r.updatedAt();
        if (senders.size() > 5_000) {
            senders.clear();
        }
        return senders.computeIfAbsent(key, k -> {
            senders.keySet().removeIf(old -> old.startsWith(scope + "@"));
            return build(r);
        });
    }

    JavaMailSenderImpl build(Row r) {
        JavaMailSenderImpl s = new JavaMailSenderImpl();
        String protocol = r.protocol().toLowerCase(Locale.ROOT);
        s.setProtocol(protocol);
        s.setHost(r.host());
        s.setPort(r.port());
        s.setDefaultEncoding("UTF-8");
        boolean auth = r.username() != null && !r.username().isBlank();
        if (auth) {
            s.setUsername(r.username());
            s.setPassword(r.password() == null ? null : crypto.decrypt(r.password()));
        }
        Properties p = s.getJavaMailProperties();
        for (String prefix : new String[] {"mail.smtp.", "mail.smtps."}) {
            p.put(prefix + "auth", String.valueOf(auth));
            p.put(prefix + "connectiontimeout", String.valueOf(TIMEOUT_MS));
            p.put(prefix + "timeout", String.valueOf(TIMEOUT_MS));
            p.put(prefix + "writetimeout", String.valueOf(TIMEOUT_MS));
            if (r.ehloDomain() != null && !r.ehloDomain().isBlank()) {
                p.put(prefix + "localhost", r.ehloDomain());
            }
            switch (r.encryption()) {
                case "STARTTLS" -> {
                    p.put(prefix + "starttls.enable", "true");
                    p.put(prefix + "starttls.required", "true");
                }
                case "SSL_TLS" -> p.put(prefix + "ssl.enable", "true");
                default -> { }
            }
            p.put(prefix + "ssl.checkserveridentity", "true");
        }
        return s;
    }

    // ---- reading and checking -----------------------------------------------------------------------------------

    private Optional<Row> companyRow(long companyId) {
        return tenants.callAs(companyId, () -> jdbc.sql("select " + COLUMNS + " from company_mail_server")
                .query(Row.class).optional());
    }

    private Optional<Row> platformRow() {
        return jdbc.sql("select " + COLUMNS + " from platform_mail_server where id = 1").query(Row.class).optional();
    }

    private String companyName(long companyId) {
        return jdbc.sql("select name from company where id = ?").param(companyId).query(String.class).optional()
                .orElse(null);
    }

    private Row checked(Input in, String storedPassword) {
        String protocol = upper(in.protocol(), "SMTP");
        if (!protocol.matches("SMTP|SMTPS")) {
            throw new BusinessRuleException("Choose SMTP or SMTPS.");
        }
        String encryption = upper(in.encryption(), "STARTTLS");
        if (!encryption.matches("NONE|STARTTLS|SSL_TLS")) {
            throw new BusinessRuleException("Choose no encryption, STARTTLS or SSL/TLS.");
        }
        String host = blank(in.host());
        if (host == null || !host.matches("[A-Za-z0-9.\\-\\[\\]:]{1,255}")) {
            throw new BusinessRuleException("Enter the server's name, for example smtp.company.cd.");
        }
        int port = in.port() == null ? ("SMTPS".equals(protocol) || "SSL_TLS".equals(encryption) ? 465 : 587) : in.port();
        if (port < 1 || port > 65535) {
            throw new BusinessRuleException("The port is a number from 1 to 65535.");
        }
        String from = email(in.fromAddress(), "Enter the sender's email address.");
        String fromName = blank(in.fromName());
        if (fromName == null || fromName.length() > 120) {
            throw new BusinessRuleException("Enter the sender's name (at most 120 characters).");
        }
        String bcc = blank(in.bcc()) == null ? null : email(in.bcc(), "The hidden copy (BCC) is not a valid email address.");
        String username = blank(in.username());
        String password = in.password() == null || in.password().isEmpty() ? storedPassword : crypto.encrypt(in.password());
        if (username == null) {
            password = null;
        }
        String ehlo = blank(in.ehloDomain());
        if (ehlo != null && !ehlo.matches("[A-Za-z0-9.\\-]{1,255}")) {
            throw new BusinessRuleException("The EHLO domain is a server name, for example mail.company.cd.");
        }
        return new Row(in.enabled(), protocol, host, port, encryption, from, fromName, bcc, username, password, ehlo,
                null, null);
    }

    private static Map<String, Object> params(Row r, String by) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", r.enabled());
        m.put("protocol", r.protocol());
        m.put("host", r.host());
        m.put("port", r.port());
        m.put("encryption", r.encryption());
        m.put("fromAddress", r.fromAddress());
        m.put("fromName", r.fromName());
        m.put("bcc", r.bcc());
        m.put("username", r.username());
        m.put("password", r.password());
        m.put("ehlo", r.ehloDomain());
        m.put("by", by);
        return m;
    }

    /** What the audit log keeps: never the password, only that one is set. */
    private static Map<String, Object> details(Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", r.enabled());
        m.put("host", r.host());
        m.put("port", r.port());
        m.put("encryption", r.encryption());
        m.put("from", r.fromAddress());
        m.put("bcc", r.bcc() == null ? "" : r.bcc());
        m.put("passwordSet", r.password() != null);
        return m;
    }

    private InternetAddress envFrom() {
        String configured = env == null || env.mailFrom() == null ? "HR Pay <no-reply@hrpay.local>" : env.mailFrom();
        try {
            InternetAddress a = new InternetAddress(configured, false);
            return a.getPersonal() == null ? address(a.getAddress(), "HR Pay") : a;
        } catch (AddressException e) {
            return address("no-reply@hrpay.local", "HR Pay");
        }
    }

    /** The company's name in front of the platform's sender name, so staff know who writes. */
    static String via(String company, String fromName) {
        String name = fromName == null || fromName.isBlank() ? "HR Pay" : fromName;
        return company == null || company.isBlank() ? name : company + " via " + name;
    }

    static InternetAddress address(String email, String name) {
        try {
            return new InternetAddress(email, name, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String email(String value, String error) {
        String v = blank(value);
        if (v == null || v.length() > 320 || !v.matches("[^@\\s<>]+@[^@\\s<>]+\\.[^@\\s<>]+")) {
            throw new BusinessRuleException(error);
        }
        return v;
    }

    private static String upper(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return t == e ? m : (e.getMessage() == null ? "" : e.getMessage() + ": ") + m;
    }

    static String cut(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max);
    }
}
