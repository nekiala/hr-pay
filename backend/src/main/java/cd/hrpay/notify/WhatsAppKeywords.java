package cd.hrpay.notify;

import cd.hrpay.attendance.AttendanceDays;
import cd.hrpay.common.AppProperties;
import cd.hrpay.i18n.Texts;
import cd.hrpay.leave.LeaveService;
import cd.hrpay.user.AppUser;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.text.NumberFormat;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Words an employee can send to the HR Pay number: the answer comes back at once, in their language. Only people who
 * confirmed their number and agreed get an answer. Meta lets a business answer freely, without a template, within 24
 * hours of the person's own message. Pay figures still never travel by WhatsApp: BULLETIN and AVANCE answer with a
 * link that opens HR Pay after sign-in.
 */
@Service
public class WhatsAppKeywords {

    public enum Keyword { SOLDE, PRESENCE, BULLETIN, AVANCE, AIDE }

    /** Each word, in French and English, without accents; anything else gets the list of words. */
    static final Map<String, Keyword> WORDS = Map.ofEntries(
            Map.entry("SOLDE", Keyword.SOLDE), Map.entry("CONGE", Keyword.SOLDE), Map.entry("CONGES", Keyword.SOLDE),
            Map.entry("BALANCE", Keyword.SOLDE), Map.entry("LEAVE", Keyword.SOLDE),
            Map.entry("PRESENCE", Keyword.PRESENCE), Map.entry("POINTAGE", Keyword.PRESENCE),
            Map.entry("ATTENDANCE", Keyword.PRESENCE), Map.entry("HEURES", Keyword.PRESENCE), Map.entry("HOURS", Keyword.PRESENCE),
            Map.entry("BULLETIN", Keyword.BULLETIN), Map.entry("PAIE", Keyword.BULLETIN), Map.entry("PAYSLIP", Keyword.BULLETIN),
            Map.entry("AVANCE", Keyword.AVANCE), Map.entry("ADVANCE", Keyword.AVANCE),
            Map.entry("AIDE", Keyword.AIDE), Map.entry("HELP", Keyword.AIDE), Map.entry("MENU", Keyword.AIDE));

    private static final Set<String> GREETINGS = Set.of("BONJOUR", "HELLO", "HI", "SALUT", "MBOTE", "JAMBO");

    private final LeaveService leave;
    private final AttendanceDays attendance;
    private final AppProperties properties;

    public WhatsAppKeywords(LeaveService leave, AttendanceDays attendance, AppProperties properties) {
        this.leave = leave;
        this.attendance = attendance;
        this.properties = properties;
    }

    /** The first word of a message, upper case and without accents or punctuation: "Présence ?" gives PRESENCE. */
    static String firstWord(String text) {
        if (text == null) {
            return "";
        }
        String plain = Normalizer.normalize(text.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String first = plain.split("\\s+", 2)[0];
        return first.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
    }

    public static Keyword keyword(String text) {
        String w = firstWord(text);
        return WORDS.getOrDefault(w, Keyword.AIDE);
    }

    /** The answer, written in the language in effect. Runs inside the person's company. */
    public String answer(AppUser user, String text) {
        Keyword k = keyword(text);
        return switch (k) {
            case SOLDE -> balance(user);
            case PRESENCE -> presence(user);
            case BULLETIN -> Texts.t("Your payslips are in HR Pay, after you sign in: %s", link("my/payslips"));
            case AVANCE -> Texts.t("See how much you can ask for today and send your request in HR Pay: %s", link("my/advance"));
            case AIDE -> help(GREETINGS.contains(firstWord(text)));
        };
    }

    private String help(boolean greeting) {
        String words = Texts.t("Send one of these words:\nSOLDE: your leave days left\nPRÉSENCE: your hours this month\n"
                + "BULLETIN: your payslips\nAVANCE: a salary advance\nSTOP: no more HR Pay messages on WhatsApp");
        return greeting ? Texts.t("Hello! This is HR Pay.") + "\n" + words : words;
    }

    private String balance(AppUser user) {
        List<LeaveService.Balance> balances = leave.balances(user.getId());
        if (balances.isEmpty()) {
            return Texts.t("You have no leave balance in HR Pay yet.");
        }
        List<String> lines = new ArrayList<>();
        lines.add(Texts.t("Your leave days left:"));
        for (LeaveService.Balance b : balances) {
            String line = "• " + b.leaveType() + " : " + days(b.available());
            if (b.pending().signum() > 0) {
                line += " " + Texts.t("(%s waiting for approval)", days(b.pending()));
            }
            lines.add(line);
        }
        lines.add(Texts.t("Details and requests: %s", link("leave")));
        return String.join("\n", lines);
    }

    private String presence(AppUser user) {
        YearMonth month = YearMonth.now();
        AttendanceDays.MonthSummary m = attendance.month(user, month, false);
        List<String> lines = new ArrayList<>();
        lines.add(Texts.t("Your attendance in %s, up to today:", Texts.month(month)));
        lines.add("• " + Texts.t("Worked: %s of %s expected", hours(m.workedMinutesToDate()), hours(m.scheduledMinutesToDate())));
        lines.add("• " + Texts.t("Days late: %s", m.lateDays()));
        lines.add("• " + Texts.t("Days absent: %s", m.absentDays()));
        if (m.missingCheckOutDays() > 0) {
            lines.add("• " + Texts.t("Days without a check-out: %s", m.missingCheckOutDays()));
        }
        lines.add(Texts.t("Details: %s", link("check-in")));
        return String.join("\n", lines);
    }

    private static String days(BigDecimal d) {
        NumberFormat f = NumberFormat.getNumberInstance(Texts.locale());
        f.setMaximumFractionDigits(2);
        String n = f.format(d);
        return Texts.t(d.compareTo(BigDecimal.ONE) <= 0 && d.compareTo(BigDecimal.ONE.negate()) >= 0 ? "%s day" : "%s days", n);
    }

    private static String hours(int minutes) {
        return "%d h %02d".formatted(minutes / 60, Math.abs(minutes % 60));
    }

    private String link(String page) {
        String base = properties.publicUrl() == null ? "" : properties.publicUrl().replaceAll("/+$", "");
        return base + "/" + page;
    }
}
