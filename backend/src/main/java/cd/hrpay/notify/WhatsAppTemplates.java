package cd.hrpay.notify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The WhatsApp templates HR Pay uses, in French and English, as they are submitted to Meta. Meta must approve each one
 * before it can start a conversation. Notifications use four "utility" templates, one per kind of message; the body
 * carries the person's first name and the notification's sentence, and a button opens the page in HR Pay. Pay figures
 * never go in a template: the payslip one only says it is ready.
 */
public final class WhatsAppTemplates {

    private WhatsAppTemplates() {}

    /** What a company may send on WhatsApp, chosen on its WhatsApp page. */
    public enum Group { PAYSLIP, DECISIONS, ACTIONS, REMINDERS }

    public record Text(String body, String button, String exampleText) {}

    public record Template(String name, Group group, Map<String, Text> languages) {}

    public static final List<String> LANGUAGES = List.of("fr", "en");

    public static final List<Template> NOTIFICATIONS = List.of(
            new Template("hrpay_payslip_ready", Group.PAYSLIP, Map.of(
                    "fr", new Text("Bonjour {{1}}, {{2}} Pour protéger vos données, le bulletin s'ouvre uniquement dans HR Pay, après connexion.",
                            "Voir le bulletin", "Le bulletin de octobre 2026 peut être consulté dans HR Pay."),
                    "en", new Text("Hello {{1}}, {{2}} To protect your data, the payslip only opens in HR Pay, after you sign in.",
                            "View payslip", "The payslip for October 2026 can be read in HR Pay."))),
            new Template("hrpay_request_update", Group.DECISIONS, Map.of(
                    "fr", new Text("Bonjour {{1}}, du nouveau sur votre demande : {{2}} Le détail est dans HR Pay.",
                            "Ouvrir HR Pay", "Votre congé du 3 au 7 novembre 2026 est approuvé."),
                    "en", new Text("Hello {{1}}, there is news on your request: {{2}} The details are in HR Pay.",
                            "Open HR Pay", "Your leave from 3 to 7 November 2026 is approved."))),
            new Template("hrpay_action_needed", Group.ACTIONS, Map.of(
                    "fr", new Text("Bonjour {{1}}, une action vous attend dans HR Pay : {{2}} Merci de vous connecter pour la traiter.",
                            "Ouvrir HR Pay", "Jean Mukendi demande un congé du 3 au 7 novembre 2026."),
                    "en", new Text("Hello {{1}}, something is waiting for you in HR Pay: {{2}} Please sign in to deal with it.",
                            "Open HR Pay", "Jean Mukendi asks for leave from 3 to 7 November 2026."))),
            new Template("hrpay_checkout_reminder", Group.REMINDERS, Map.of(
                    "fr", new Text("Bonjour {{1}}, {{2}} Pensez à pointer votre départ dans HR Pay.",
                            "Pointer", "Votre journée s'est terminée à 17:00."),
                    "en", new Text("Hello {{1}}, {{2}} Remember to check out in HR Pay.",
                            "Check out", "Your day ended at 17:00."))));

    private static final Map<String, Group> KINDS = Map.ofEntries(
            Map.entry("payslip.ready", Group.PAYSLIP),
            Map.entry("leave.approved", Group.DECISIONS), Map.entry("leave.rejected", Group.DECISIONS),
            Map.entry("advance.approved", Group.DECISIONS), Map.entry("advance.rejected", Group.DECISIONS),
            Map.entry("certificate.issued", Group.DECISIONS), Map.entry("certificate.rejected", Group.DECISIONS),
            Map.entry("leave.to_approve", Group.ACTIONS), Map.entry("correction.to_approve", Group.ACTIONS),
            Map.entry("letter.sent", Group.ACTIONS), Map.entry("letter.reminder", Group.ACTIONS),
            Map.entry("letter.signed", Group.ACTIONS), Map.entry("letter.declined", Group.ACTIONS),
            Map.entry("advance.requested", Group.ACTIONS), Map.entry("advance.to-pay", Group.ACTIONS),
            Map.entry("document.expiring", Group.ACTIONS), Map.entry("certificate.requested", Group.ACTIONS),
            Map.entry("reminder.check_out", Group.REMINDERS));

    /** The kind of message a notification is, or null for those that never go by WhatsApp (security, support…). */
    public static Group groupOf(String kind) {
        return KINDS.get(kind);
    }

    public static Template forGroup(Group g) {
        return NOTIFICATIONS.stream().filter(t -> t.group() == g).findFirst().orElseThrow();
    }

    public static Set<String> names(String otpTemplate) {
        Set<String> names = new java.util.TreeSet<>();
        NOTIFICATIONS.forEach(t -> names.add(t.name()));
        names.add(otpTemplate);
        return names;
    }

    /** What Meta's "create template" call takes for one template in one language; links open under {@code baseUrl}. */
    static Map<String, Object> payload(Template t, String language, String baseUrl) {
        Text x = t.languages().get(language);
        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "BODY");
        body.put("text", x.body());
        body.put("example", Map.of("body_text", List.of(List.of(language.equals("fr") ? "Marie" : "Mary", x.exampleText()))));
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "URL");
        button.put("text", x.button());
        button.put("url", base + "{{1}}");
        button.put("example", List.of(base + (t.group() == Group.PAYSLIP ? "my/payslips" : t.group() == Group.REMINDERS ? "check-in" : "leave")));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", t.name());
        p.put("language", language);
        p.put("category", "UTILITY");
        p.put("components", List.of(body, Map.of("type", "BUTTONS", "buttons", List.of(button))));
        return p;
    }

    /** The one-time code template: Meta's authentication kind, with a "copy code" button and a 5-minute expiry note. */
    static Map<String, Object> otpPayload(String name, String language) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("language", language);
        p.put("category", "AUTHENTICATION");
        List<Object> components = new ArrayList<>();
        components.add(Map.of("type", "BODY", "add_security_recommendation", true));
        components.add(Map.of("type", "FOOTER", "code_expiration_minutes", 5));
        components.add(Map.of("type", "BUTTONS", "buttons", List.of(Map.of("type", "OTP", "otp_type", "COPY_CODE",
                "text", language.equals("fr") ? "Copier le code" : "Copy code"))));
        p.put("components", components);
        return p;
    }

    /** Meta refuses parameters with line breaks, tabs or more than four spaces in a row. */
    static String param(String s) {
        if (s == null || s.isBlank()) {
            return "-";
        }
        String clean = s.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" {2,}", " ").trim();
        return clean.length() > 900 ? clean.substring(0, 899) + "…" : clean;
    }
}
