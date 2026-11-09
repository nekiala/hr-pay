package cd.hrpay.notify;

import cd.hrpay.common.AppProperties;
import cd.hrpay.i18n.Texts;
import cd.hrpay.user.AppUser;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Sends one-time codes by WhatsApp (Meta Cloud API, authentication template) and email. Every attempt is
 * written to outbox_message. The email server is the company's own, else the platform's, else .env's (see
 * MailServers); without any, emails are only logged there, which is how codes are read in development.
 */
@Service
public class NotificationService {

    public enum Channel { EMAIL, WHATSAPP }

    public record Delivery(Channel channel, String status) {}

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final MailServers servers;
    private final AppProperties.Notifications settings;
    private final JdbcClient jdbc;
    private final WhatsApp whatsapp;

    public NotificationService(MailServers servers, AppProperties properties, JdbcClient jdbc, WhatsApp whatsapp) {
        this.servers = servers;
        this.settings = properties.notifications();
        this.jdbc = jdbc;
        this.whatsapp = whatsapp;
    }

    /** What a code is for, as the person reads it. */
    private static String purpose(String purpose) {
        return switch (purpose) {
            case "CHECK_IN" -> Texts.t("check-in");
            case "SIGN_IN" -> Texts.t("sign-in");
            case "TWO_STEP_SETUP" -> Texts.t("second step set-up");
            case "LETTER_SIGN" -> Texts.t("signature");
            case "ADVANCE_SIGN" -> Texts.t("salary advance");
            case WhatsAppConsent.OTP_PURPOSE -> Texts.t("WhatsApp confirmation");
            default -> purpose.toLowerCase(Locale.ROOT).replace('_', '-');
        };
    }

    /**
     * Sends the code by WhatsApp when an account is set up, and always by email (the fallback the design asks for),
     * except the code that confirms a WhatsApp number: it goes by WhatsApp only.
     */
    public List<Delivery> sendCode(AppUser user, String code, String purpose) {
        String language = languageOf(user);
        String text = Texts.in(language, () -> Texts.t("Your HR Pay %s code is %s. It expires in 5 minutes.", purpose(purpose), code));
        java.util.Optional<WhatsApp.Account> account = whatsapp.resolve(user.getCompanyId());
        if (WhatsAppConsent.OTP_PURPOSE.equals(purpose)) {
            return account.map(a -> List.of(sendWhatsapp(a, user, code, text, language))).orElse(List.of());
        }
        Delivery email = send(user, Texts.in(language, () -> Texts.t("HR Pay code: %s", code)), text, true);
        return account.map(a -> List.of(sendWhatsapp(a, user, code, text, language), email)).orElse(List.of(email));
    }

    /** The language a person reads: their own choice, else their company's. */
    public String languageOf(AppUser user) {
        if (user.getLanguage() != null) {
            return user.getLanguage();
        }
        return jdbc.sql("select language from company where id = ?").param(user.getCompanyId())
                .query(String.class).optional().orElse(Texts.FR);
    }

    /** An email written in the person's language: the subject and body are made with it in effect. */
    public Delivery email(AppUser user, Supplier<String> subject, Supplier<String> body) {
        String language = languageOf(user);
        return sendEmail(user, Texts.in(language, subject), Texts.in(language, body));
    }

    /** An email that opens an account (a code, a password link): never copied to the hidden copy (BCC). */
    public Delivery securityEmail(AppUser user, Supplier<String> subject, Supplier<String> body) {
        String language = languageOf(user);
        return send(user, Texts.in(language, subject), Texts.in(language, body), true);
    }

    public Delivery sendEmail(AppUser user, String subject, String body) {
        return send(user, subject, body, false);
    }

    private Delivery send(AppUser user, String subject, String body, boolean security) {
        MailServers.Resolved server = servers.resolve(user.getCompanyId(), security);
        if (server.level() == MailServers.Level.NONE) {
            record(user, Channel.EMAIL, user.getEmail(), subject, body, "LOGGED", null, server.level());
            log.info("No email server set; email to {} kept in the outbox only.", user.getEmail());
            return new Delivery(Channel.EMAIL, "LOGGED");
        }
        try {
            MimeMessage message = server.sender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(server.from());
            helper.setTo(user.getEmail());
            if (server.bcc() != null && !server.bcc().equalsIgnoreCase(user.getEmail())) {
                helper.setBcc(server.bcc());
            }
            helper.setSubject(subject);
            helper.setText(body);
            server.sender().send(message);
            record(user, Channel.EMAIL, user.getEmail(), subject, body, "SENT", null, server.level());
            return new Delivery(Channel.EMAIL, "SENT");
        } catch (MessagingException | RuntimeException e) {
            String error = MailServers.rootMessage(e);
            log.warn("Email to {} failed ({}): {}", user.getEmail(), server.level(), error);
            record(user, Channel.EMAIL, user.getEmail(), subject, body, "FAILED", error, server.level());
            if (server.level() == MailServers.Level.COMPANY) {
                try {
                    servers.companyServerFailed(user.getCompanyId(), error);
                } catch (RuntimeException alert) {
                    log.warn("Could not tell the company's administrators: {}", alert.getMessage());
                }
            }
            return new Delivery(Channel.EMAIL, "FAILED");
        }
    }

    /**
     * An email to someone who has no account (a website visitor), sent and recorded as the provider's company.
     * Run inside the provider's company.
     */
    public Delivery emailAddress(String to, String subject, String body) {
        MailServers.Resolved server = servers.resolve(cd.hrpay.tenancy.TenantContext.providerCompany(), false);
        if (server.level() == MailServers.Level.NONE) {
            record(null, Channel.EMAIL, to, subject, body, "LOGGED", null, server.level());
            return new Delivery(Channel.EMAIL, "LOGGED");
        }
        try {
            MimeMessage message = server.sender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(server.from());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body);
            server.sender().send(message);
            record(null, Channel.EMAIL, to, subject, body, "SENT", null, server.level());
            return new Delivery(Channel.EMAIL, "SENT");
        } catch (MessagingException | RuntimeException e) {
            String error = MailServers.rootMessage(e);
            log.warn("Email to {} failed ({}): {}", to, server.level(), error);
            record(null, Channel.EMAIL, to, subject, body, "FAILED", error, server.level());
            return new Delivery(Channel.EMAIL, "FAILED");
        }
    }

    private Delivery sendWhatsapp(WhatsApp.Account a, AppUser user, String code, String text, String language) {
        // The template is approved by Meta in each language; French when the person reads French.
        String lang = Texts.FR.equals(language) ? "fr" : a.level() == WhatsApp.Level.ENV && settings != null
                && settings.whatsappLanguage() != null ? settings.whatsappLanguage() : "en";
        WhatsApp.Sent sent = whatsapp.sendTemplate(a, user, user.getPhone(), whatsapp.otpTemplate(), lang, List.of(code), code, text);
        return new Delivery(Channel.WHATSAPP, sent.ok() ? "SENT" : "FAILED");
    }

    private void record(AppUser user, Channel channel, String recipient, String subject, String body, String status,
                        String error, MailServers.Level via) {
        jdbc.sql("""
                insert into outbox_message (user_id, channel, recipient, subject, body, status, error, via)
                values (:user, :channel, :recipient, :subject, :body, :status, :error, :via)""")
                .param("via", via == null ? null : via.name())
                .param("user", user == null ? null : user.getId()).param("channel", channel.name()).param("recipient", recipient)
                .param("subject", subject).param("body", body).param("status", status).param("error", truncate(error))
                .update();
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
