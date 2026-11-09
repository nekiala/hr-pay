package cd.hrpay.user;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.security.CurrentUser;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The employee ID (matricule) HR Pay gives new people, from a format the administrator sets. The format is fixed text
 * with tokens: {YYYY} or {YY} the year the person is created, {SITE} the code of their site, and {SEQ:n} a counter
 * padded to n digits (once, required). The counter goes on for ever, or starts again at 1 each year. Numbers already
 * used are skipped; existing employee IDs never change. The law sets no format (DRC and Congo).
 */
@Service
public class Matricules {

    public record Format(boolean enabled, String pattern, boolean resetYearly, int nextSeq, String next,
                         String updatedBy) {}

    static final Pattern TOKEN = Pattern.compile("\\{(YYYY|YY|SITE|SEQ(?::(\\d))?)}");
    static final int MAX_LENGTH = 32;

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final AuditService audit;
    private Clock clock = Clock.systemDefaultZone();

    public Matricules(JdbcClient jdbc, UserRepository users, AuditService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setClock(Clock clock) {
        this.clock = clock;
    }

    private record Row(boolean enabled, String pattern, boolean resetYearly, int nextSeq, Integer seqYear,
                       String updatedBy) {}

    private java.util.Optional<Row> row(boolean lock) {
        return jdbc.sql("select enabled, pattern, reset_yearly, next_seq, seq_year, updated_by from matricule_format"
                + (lock ? " for update" : "")).query(Row.class).optional();
    }

    public boolean enabled() {
        return row(false).map(Row::enabled).orElse(false);
    }

    public Format format() {
        Row r = row(false).orElse(new Row(false, "{SEQ:5}", false, 1, null, null));
        int year = LocalDate.now(clock).getYear();
        int seq = counter(r, year);
        return new Format(r.enabled(), r.pattern(), r.resetYearly(), seq, render(r.pattern(), year, "SITE", seq), r.updatedBy());
    }

    /** What the format gives, for the settings page's preview. */
    public String preview(String pattern, int seq, String site) {
        check(pattern);
        return render(pattern.trim(), LocalDate.now(clock).getYear(), site == null || site.isBlank() ? "SITE" : site, seq);
    }

    @Transactional
    public Format save(boolean enabled, String pattern, boolean resetYearly, int nextSeq) {
        String clean = pattern.trim();
        check(clean);
        if (nextSeq < 1) {
            throw new BusinessRuleException("The next number must be 1 or more.");
        }
        int year = LocalDate.now(clock).getYear();
        int updated = jdbc.sql("""
                update matricule_format set enabled = ?, pattern = ?, reset_yearly = ?, next_seq = ?, seq_year = ?,
                       updated_by = ?, updated_at = now()""")
                .params(enabled, clean, resetYearly, nextSeq, year, CurrentUser.email()).update();
        if (updated == 0) {
            jdbc.sql("""
                    insert into matricule_format (enabled, pattern, reset_yearly, next_seq, seq_year, updated_by)
                    values (?, ?, ?, ?, ?, ?)""")
                    .params(enabled, clean, resetYearly, nextSeq, year, CurrentUser.email()).update();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("enabled", enabled);
        details.put("pattern", clean);
        details.put("resetYearly", resetYearly);
        details.put("nextSeq", nextSeq);
        audit.record("matricule.format_saved", "company", null, details);
        return format();
    }

    /** The next free employee ID, taken now (the counter moves on); empty when HR Pay does not number people. */
    @Transactional
    public java.util.Optional<String> next(String siteCode) {
        Row r = row(true).filter(Row::enabled).orElse(null);
        if (r == null) {
            return java.util.Optional.empty();
        }
        int year = LocalDate.now(clock).getYear();
        int seq = counter(r, year);
        String site = siteCode == null || siteCode.isBlank() ? "" : siteCode.trim().toUpperCase();
        if (r.pattern().contains("{SITE}") && site.isEmpty()) {
            throw new BusinessRuleException("The employee ID format uses the site code: choose the person's site.");
        }
        String value;
        int tries = 0;
        do {
            value = render(r.pattern(), year, site, seq++);
            if (value.length() > MAX_LENGTH) {
                throw new BusinessRuleException(Texts.t("The employee ID %s is longer than %s characters.", value, MAX_LENGTH));
            }
            if (++tries > 10_000) {
                throw new BusinessRuleException("No free employee ID found: change the format or the next number.");
            }
        } while (users.findByRegistrationNoIgnoreCase(value).isPresent());
        jdbc.sql("update matricule_format set next_seq = ?, seq_year = ?").params(seq, year).update();
        return java.util.Optional.of(value);
    }

    /** The counter for this year: back to 1 in a new year when it starts again each year. */
    private static int counter(Row r, int year) {
        return r.resetYearly() && r.seqYear() != null && r.seqYear() != year ? 1 : r.nextSeq();
    }

    static void check(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new BusinessRuleException("Enter the employee ID format.");
        }
        Matcher m = TOKEN.matcher(pattern);
        int seq = 0;
        StringBuilder rest = new StringBuilder();
        int last = 0;
        while (m.find()) {
            rest.append(pattern, last, m.start());
            last = m.end();
            if (m.group(1).startsWith("SEQ")) {
                seq++;
            }
        }
        rest.append(pattern.substring(last));
        if (seq != 1) {
            throw new BusinessRuleException("The format needs the counter {SEQ} exactly once, for example {SEQ:5}.");
        }
        if (rest.toString().contains("{") || rest.toString().contains("}")) {
            throw new BusinessRuleException("Unknown token in the format: use {YYYY}, {YY}, {SITE} and {SEQ:n}.");
        }
        if (!rest.toString().matches("[A-Za-z0-9/_.-]*")) {
            throw new BusinessRuleException("The fixed text can hold letters, digits and - _ / . only.");
        }
    }

    static String render(String pattern, int year, String site, int seq) {
        Matcher m = TOKEN.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String token = m.group(1);
            String value = switch (token) {
                case "YYYY" -> String.valueOf(year);
                case "YY" -> String.format("%02d", year % 100);
                case "SITE" -> site;
                default -> {
                    int width = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
                    yield String.format("%0" + Math.max(width, 1) + "d", seq);
                }
            };
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString().toUpperCase();
    }
}
