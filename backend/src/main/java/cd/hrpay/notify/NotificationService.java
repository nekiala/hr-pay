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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Sends one-time codes by WhatsApp (Meta Cloud API, authentication template) and email. Every attempt is
 * written to outbox_message. Without SMTP settings, emails are only logged there, which is how codes are
 * read in development.
 */
@Service
public class NotificationService {

    public enum Channel { EMAIL, WHATSAPP }

    public record Delivery(Channel channel, String status) {}

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final ObjectProvider<JavaMailSender> mail;
    private final AppProperties.Notifications settings;
    private final JdbcClient jdbc;
    private final RestClient whatsapp;

    public NotificationService(ObjectProvider<JavaMailSender> mail, AppProperties properties, JdbcClient jdbc) {
        this.mail = mail;
        this.settings = properties.notifications();
        this.jdbc = jdbc;
        this.whatsapp = RestClient.builder().baseUrl("https://graph.facebook.com/v21.0").build();
    }

    /** What a code is for, as the person reads it. */
    private static String purpose(String purpose) {
        return switch (purpose) {
            case "CHECK_IN" -> Texts.t("check-in");
            case "SIGN_IN" -> Texts.t("sign-in");
            case "TWO_STEP_SETUP" -> Texts.t("second step set-up");
            default -> purpose.toLowerCase(Locale.ROOT).replace('_', '-');
        };
    }

    /** Sends the code by WhatsApp when configured, and always by email (the fallback the design asks for). */
    public List<Delivery> sendCode(AppUser user, String code, String purpose) {
        String language = languageOf(user);
        String text = Texts.in(language, () -> Texts.t("Your HR Pay %s code is %s. It expires in 5 minutes.", purpose(purpose), code));
        Delivery email = sendEmail(user, Texts.in(language, () -> Texts.t("HR Pay code: %s", code)), text);
        if (settings != null && settings.whatsappEnabled()) {
            return List.of(sendWhatsapp(user, code, text, language), email);
        }
        return List.of(email);
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

    public Delivery sendEmail(AppUser user, String subject, String body) {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) {
            record(user, Channel.EMAIL, user.getEmail(), subject, body, "LOGGED", null);
            log.info("No SMTP server configured; email to {} kept in the outbox only.", user.getEmail());
            return new Delivery(Channel.EMAIL, "LOGGED");
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(settings == null ? "no-reply@hrpay.local" : settings.mailFrom());
            message.setTo(user.getEmail());
            message.setSubject(subject);
            message.setText(body);
            sender.send(message);
            record(user, Channel.EMAIL, user.getEmail(), subject, body, "SENT", null);
            return new Delivery(Channel.EMAIL, "SENT");
        } catch (RuntimeException e) {
            log.warn("Email to {} failed: {}", user.getEmail(), e.getMessage());
            record(user, Channel.EMAIL, user.getEmail(), subject, body, "FAILED", e.getMessage());
            return new Delivery(Channel.EMAIL, "FAILED");
        }
    }

    private Delivery sendWhatsapp(AppUser user, String code, String text, String language) {
        String to = user.getPhone().replace("+", "");
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "template",
                "template", Map.of(
                        "name", settings.whatsappTemplate(),
                        // The template is approved by Meta in each language; French when the person reads French.
                        "language", Map.of("code", Texts.FR.equals(language) ? "fr" : settings.whatsappLanguage()),
                        "components", List.of(
                                Map.of("type", "body", "parameters", List.of(Map.of("type", "text", "text", code))),
                                Map.of("type", "button", "sub_type", "url", "index", "0",
                                        "parameters", List.of(Map.of("type", "text", "text", code))))));
        try {
            whatsapp.post().uri("/{id}/messages", settings.whatsappPhoneNumberId())
                    .header("Authorization", "Bearer " + settings.whatsappToken())
                    .body(payload).retrieve().toBodilessEntity();
            record(user, Channel.WHATSAPP, user.getPhone(), null, text, "SENT", null);
            return new Delivery(Channel.WHATSAPP, "SENT");
        } catch (RuntimeException e) {
            log.warn("WhatsApp to {} failed: {}", user.getPhone(), e.getMessage());
            record(user, Channel.WHATSAPP, user.getPhone(), null, text, "FAILED", truncate(e.getMessage()));
            return new Delivery(Channel.WHATSAPP, "FAILED");
        }
    }

    private void record(AppUser user, Channel channel, String recipient, String subject, String body, String status,
                        String error) {
        jdbc.sql("""
                insert into outbox_message (user_id, channel, recipient, subject, body, status, error)
                values (:user, :channel, :recipient, :subject, :body, :status, :error)""")
                .param("user", user.getId()).param("channel", channel.name()).param("recipient", recipient)
                .param("subject", subject).param("body", body).param("status", status).param("error", truncate(error))
                .update();
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
