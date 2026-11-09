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
    public enum Group { PAYSLIP, DECISIONS, ACTIONS, REMINDERS, ANNOUNCEMENTS }

    /** What a company chooses on its WhatsApp page; announcements are chosen one by one when HR sends them. */
    public static final List<Group> CHOSEN_BY_COMPANY = List.of(Group.PAYSLIP, Group.DECISIONS, Group.ACTIONS, Group.REMINDERS);

    /** {@code exampleText} is null for a template that takes only the first name. */
    public record Text(String body, String button, String exampleText) {

        public boolean takesDetail() {
            return body.contains("{{2}}");
        }
    }

    public record Template(String name, Group group, Map<String, Text> languages) {}

    public static final List<String> LANGUAGES = List.of("fr", "en");

    public static final List<Template> NOTIFICATIONS = List.of(
            // Only the first name: Meta refused a payslip notice whose sentence came from a parameter (INCORRECT_CATEGORY).
            new Template("hrpay_payslip_available", Group.PAYSLIP, Map.of(
                    "fr", new Text("Bonjour {{1}}, votre bulletin de paie est disponible dans HR Pay. Pour protéger vos données, il s'ouvre uniquement après connexion.",
                            "Voir le bulletin", null),
                    "en", new Text("Hello {{1}}, your payslip is available in HR Pay. To protect your data, it only opens after you sign in.",
                            "View payslip", null))),
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
            // Fixed words about the person's own record: Meta moved a "remember to check out" wording to MARKETING.
            new Template("hrpay_checkout_pending", Group.REMINDERS, Map.of(
                    "fr", new Text("Bonjour {{1}}, votre départ n'est pas encore pointé dans HR Pay. Merci de le pointer pour que vos heures de travail soient justes.",
                            "Pointer", null),
                    "en", new Text("Hello {{1}}, your check-out is not recorded in HR Pay yet. Please check out so that your working hours are right.",
                            "Check out", null))),
            // A notice in fixed words: the announcement itself is read in HR Pay.
            new Template("hrpay_announcement", Group.ANNOUNCEMENTS, Map.of(
                    "fr", new Text("Bonjour {{1}}, votre employeur a publié une annonce pour vous dans HR Pay. Merci de vous connecter pour la lire.",
                            "Lire l'annonce", null),
                    "en", new Text("Hello {{1}}, your employer has posted an announcement for you in HR Pay. Please sign in to read it.",
                            "Read it", null))));

    /** An announcement HR chose to send on WhatsApp too; plain "announcement" notifications stay in HR Pay. */
    public static final String ANNOUNCEMENT_KIND = "announcement.whatsapp";

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
            Map.entry("reminder.check_out", Group.REMINDERS),
            Map.entry(ANNOUNCEMENT_KIND, Group.ANNOUNCEMENTS));

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
        String name = language.equals("fr") ? "Marie" : "Mary";
        body.put("example", Map.of("body_text", List.of(x.exampleText() == null ? List.of(name) : List.of(name, x.exampleText()))));
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "URL");
        button.put("text", x.button());
        button.put("url", base + "{{1}}");
        button.put("example", List.of(base + (switch (t.group()) {
            case PAYSLIP -> "my/payslips";
            case REMINDERS -> "check-in";
            case ANNOUNCEMENTS -> "announcements/1";
            default -> "leave";
        })));
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
