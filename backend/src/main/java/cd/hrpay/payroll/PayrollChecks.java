package cd.hrpay.payroll;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.security.DataCrypto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks on a calculated month before it is approved and closed. Each one names the person (or the whole run), says
 * what is wrong and how serious it is:
 * <ul>
 *   <li>LEGAL: against the law, it must be fixed (net pay below zero; basic pay below the grade's legal minimum,
 *       DRC Décret 25/22 art. 4 and 7, Congo SMIG). Deductions for debts are kept within Code du travail art. 114 by the
 *       calculation itself, so they never reach this list.</li>
 *   <li>WARNING: fixed, or accepted by HR with a reason that is kept (and audited).</li>
 * </ul>
 * The month cannot be approved, nor closed, while a legal one is left or a warning is neither fixed nor accepted.
 */
@Service
public class PayrollChecks {

    public enum Level { LEGAL, WARNING }

    public record Check(String code, Level level, Long userId, String name, String registrationNo, String message,
                        String acceptedBy, String reason, OffsetDateTime acceptedAt) {

        public boolean open() {
            return level == Level.LEGAL || acceptedBy == null;
        }
    }

    public record Settings(BigDecimal netChangePercent, int rateMaxDays) {}

    public record Report(String month, String status, int legal, int warnings, int accepted, boolean clear,
                         List<Check> checks, Settings settings) {}

    public record AcceptInput(String code, List<Long> userIds, String reason) {}

    /** Codes HR may accept with a reason; the others are LEGAL. */
    static final Set<String> LEGAL = Set.of("NET_NEGATIVE", "BELOW_MINIMUM");

    private record Slip(long id, long userId, String name, String registrationNo, String currency, BigDecimal net,
                        String warnings, String snapshot) {}

    private record Acceptance(String code, long userId, String reason, String acceptedBy, OffsetDateTime acceptedAt) {}

    private final JdbcClient jdbc;
    private final DataCrypto crypto;
    private final AuditService audit;
    private final JsonMapper json = JsonMapper.builder().build();
    private boolean required;

    public PayrollChecks(JdbcClient jdbc, DataCrypto crypto, AuditService audit,
                         @org.springframework.beans.factory.annotation.Value("${app.payroll.checks-required:true}") boolean required) {
        this.required = required;
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.audit = audit;
    }

    // ---- settings ---------------------------------------------------------------------------------------------------

    private String setting(String key, String fallback) {
        return jdbc.sql("select value from payroll_setting where key = ?").param(key).query(String.class).optional()
                .orElse(fallback);
    }

    public Settings settings() {
        return new Settings(new BigDecimal(setting("CHECK_NET_CHANGE_PERCENT", "20")),
                Integer.parseInt(setting("CHECK_RATE_MAX_DAYS", "7")));
    }

    @Transactional
    public Settings saveSettings(Settings s) {
        if (s.netChangePercent() == null || s.netChangePercent().compareTo(BigDecimal.ONE) < 0
                || s.netChangePercent().compareTo(BigDecimal.valueOf(500)) > 0) {
            throw new BusinessRuleException("The change in net pay that is flagged goes from 1% to 500%.");
        }
        if (s.rateMaxDays() < 1 || s.rateMaxDays() > 90) {
            throw new BusinessRuleException("The age of the exchange rate goes from 1 to 90 days.");
        }
        Map<String, String> values = Map.of("CHECK_NET_CHANGE_PERCENT", s.netChangePercent().stripTrailingZeros().toPlainString(),
                "CHECK_RATE_MAX_DAYS", String.valueOf(s.rateMaxDays()));
        values.forEach((k, v) -> jdbc.sql("""
                insert into payroll_setting (key, value) values (?, ?)
                on conflict (company_id, key) do update set value = excluded.value""").params(k, v).update());
        audit.record("payroll.check_settings", "payroll_setting", null, Map.copyOf(values));
        return settings();
    }

    // ---- the checks -------------------------------------------------------------------------------------------------

    public Report report(YearMonth month) {
        PayrollRuns.Run run = jdbc.sql("""
                select id, month, status, usd_cdf, rate_date, rate_source, employee_count, projected, calculated_by,
                       calculated_at, approved_by, approved_at, closed_by, closed_at from payroll_run where month = ?""")
                .param(month.atDay(1)).query(PayrollRuns.Run.class).optional()
                .orElseThrow(() -> new NotFoundException("No payroll for %s yet.".formatted(month)));
        Settings settings = settings();
        List<Check> checks = run.status().equals("CLOSED") ? List.of() : checks(run, month, settings);
        int legal = (int) checks.stream().filter(c -> c.level() == Level.LEGAL).count();
        int accepted = (int) checks.stream().filter(c -> !c.open()).count();
        int warnings = (int) checks.stream().filter(c -> c.level() == Level.WARNING && c.open()).count();
        return new Report(month.toString(), run.status(), legal, warnings, accepted, legal == 0 && warnings == 0, checks, settings);
    }

    /** Refuses approval or closing while something is left. */
    public void requireClear(YearMonth month) {
        if (!required) {
            return;
        }
        Report r = report(month);
        if (r.legal() > 0) {
            throw new BusinessRuleException(Texts.t("%s payslip(s) break the law (net pay below zero or basic pay below the legal minimum): fix them and calculate again.",
                    r.legal()));
        }
        if (r.warnings() > 0) {
            throw new BusinessRuleException(Texts.t("%s check(s) are still open: fix them, or accept them with a reason, on the payroll page.",
                    r.warnings()));
        }
    }

    /** Tests turn the rule on and off; the application always has it on unless app.payroll.checks-required is false. */
    public void setRequired(boolean required) {
        this.required = required;
    }

    /** A new calculation starts the acceptances over. */
    void reset(long runId) {
        jdbc.sql("delete from payroll_check_acceptance where run_id = ?").param(runId).update();
    }

    @Transactional
    public Report accept(YearMonth month, AcceptInput in, String by) {
        if (in.code() == null || LEGAL.contains(in.code())) {
            throw new BusinessRuleException("This check is required by law: it must be fixed, not accepted.");
        }
        String reason = in.reason() == null ? "" : in.reason().trim();
        if (reason.length() < 5 || reason.length() > 500) {
            throw new BusinessRuleException("Give the reason in 5 to 500 characters.");
        }
        Report r = report(month);
        if (!r.status().equals("CALCULATED")) {
            throw new BusinessRuleException("Checks are accepted before the payroll is approved.");
        }
        List<Check> chosen = r.checks().stream().filter(c -> c.code().equals(in.code()) && c.open())
                .filter(c -> in.userIds() == null || in.userIds().contains(c.userId() == null ? 0L : c.userId())).toList();
        if (chosen.isEmpty()) {
            throw new BusinessRuleException("Nothing to accept.");
        }
        long runId = jdbc.sql("select id from payroll_run where month = ?").param(month.atDay(1)).query(Long.class).single();
        for (Check c : chosen) {
            jdbc.sql("""
                    insert into payroll_check_acceptance (run_id, code, user_id, reason, accepted_by) values (?, ?, ?, ?, ?)
                    on conflict (run_id, code, user_id) do update set reason = excluded.reason, accepted_by = excluded.accepted_by,
                        accepted_at = now()""")
                    .params(runId, c.code(), c.userId() == null ? 0L : c.userId(), reason, by).update();
        }
        audit.record("payroll.check_accepted", "payroll_run", runId, Map.of("month", month.toString(), "code", in.code(),
                "count", chosen.size(), "reason", reason));
        return report(month);
    }

    private List<Check> checks(PayrollRuns.Run run, YearMonth month, Settings settings) {
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        List<Slip> slips = jdbc.sql("""
                select p.id, p.user_id, u.first_name || ' ' || u.last_name as name, u.registration_no, trim(p.currency) as currency,
                       p.net, p.warnings::text as warnings, p.snapshot::text as snapshot
                from payslip p join app_user u on u.id = p.user_id
                where p.run_id = ? and p.kind = 'REGULAR' order by u.last_name, u.first_name""")
                .param(run.id()).query(Slip.class).list();
        Map<String, Acceptance> accepted = new HashMap<>();
        jdbc.sql("select code, user_id, reason, accepted_by, accepted_at from payroll_check_acceptance where run_id = ?")
                .param(run.id()).query(Acceptance.class).list()
                .forEach(a -> accepted.put(a.code() + "|" + a.userId(), a));
        List<Check> out = new ArrayList<>();
        Builder add = (code, slip, message) -> {
            Acceptance a = LEGAL.contains(code) ? null : accepted.get(code + "|" + (slip == null ? 0 : slip.userId()));
            out.add(new Check(code, LEGAL.contains(code) ? Level.LEGAL : Level.WARNING, slip == null ? null : slip.userId(),
                    slip == null ? null : slip.name(), slip == null ? null : slip.registrationNo(), message,
                    a == null ? null : a.acceptedBy(), a == null ? null : a.reason(), a == null ? null : a.acceptedAt()));
        };

        // The legal ones.
        for (Slip s : slips) {
            if (s.net().signum() < 0) {
                add.add("NET_NEGATIVE", s, Texts.t("Net pay is below zero (%s %s).", plain(s.net()), s.currency()));
            }
            for (JsonNode w : json.readTree(s.warnings())) {
                if (w.asString().startsWith(PayslipCalculator.BELOW_MINIMUM)) {
                    add.add("BELOW_MINIMUM", s, w.asString());
                }
            }
        }

        Map<Long, Slip> byUser = slips.stream().collect(Collectors.toMap(Slip::userId, s -> s, (a, b) -> a, LinkedHashMap::new));
        Set<Long> users = byUser.keySet();
        if (users.isEmpty()) {
            return out;
        }

        // Paid after leaving: a confirmed departure whose last day is before the month.
        jdbc.sql("select user_id, max(last_day) from departure where status = 'CONFIRMED' and last_day < ? group by user_id")
                .param(first).query((rs, i) -> {
                    Slip s = byUser.get(rs.getLong(1));
                    if (s != null) {
                        add.add("AFTER_LEAVING", s, Texts.t("Paid although they left on %s.", Texts.day(rs.getObject(2, LocalDate.class))));
                    }
                    return null;
                }).list();

        // Identifiers and payment details.
        record Profile(long userId, String cnssNumber, String taxNumber, String paymentMethod, String bankAccount,
                       String mobileMoneyNumber) {}
        Map<Long, Profile> profiles = new HashMap<>();
        jdbc.sql("""
                select user_id, cnss_number, tax_number, payment_method, bank_account, mobile_money_number
                from employee_profile""").query(Profile.class).list().forEach(p -> profiles.put(p.userId(), p));
        Map<String, List<Slip>> accounts = new LinkedHashMap<>();
        for (Slip s : slips) {
            Profile p = profiles.get(s.userId());
            List<String> missing = new ArrayList<>();
            if (p == null || blank(p.cnssNumber())) {
                missing.add(Texts.t("CNSS number"));
            }
            if (p == null || blank(p.taxNumber())) {
                missing.add(Texts.t("tax number (NIF)"));
            }
            String method = p == null ? "BANK" : p.paymentMethod();
            String account = p == null ? null : method.equals("BANK") ? decrypt(p.bankAccount())
                    : method.equals("MOBILE_MONEY") ? decrypt(p.mobileMoneyNumber()) : "CASH";
            if (blank(account)) {
                missing.add(method.equals("MOBILE_MONEY") ? Texts.t("mobile money number") : Texts.t("bank account"));
            } else if (!account.equals("CASH")) {
                accounts.computeIfAbsent(method + ":" + account.replaceAll("[\\s.-]", "").toUpperCase(java.util.Locale.ROOT),
                        k -> new ArrayList<>()).add(s);
            }
            if (!missing.isEmpty()) {
                add.add("MISSING_DETAILS", s, Texts.t("Missing in the file: %s.", String.join(", ", missing)));
            }
        }
        accounts.values().stream().filter(l -> l.size() > 1).forEach(group -> {
            for (Slip s : group) {
                String others = group.stream().filter(o -> o != s).map(Slip::name).collect(Collectors.joining(", "));
                add.add("SHARED_ACCOUNT", s, Texts.t("Paid into the same account as %s.", others));
            }
        });

        // Net pay against the month before.
        BigDecimal threshold = settings.netChangePercent();
        record Before(long userId, String currency, BigDecimal net, long payslipId) {}
        Map<Long, Before> before = new HashMap<>();
        jdbc.sql("""
                select p.user_id, trim(p.currency) as currency, p.net, p.id as payslip_id from payslip p
                join payroll_run r on r.id = p.run_id
                where r.month = (select max(month) from payroll_run where month < ?) and p.kind = 'REGULAR'""")
                .param(first).query(Before.class).list().forEach(b -> before.put(b.userId(), b));
        for (Slip s : slips) {
            Before b = before.get(s.userId());
            if (b == null || !b.currency().equals(s.currency()) || b.net().signum() <= 0) {
                continue;
            }
            BigDecimal change = s.net().subtract(b.net()).multiply(BigDecimal.valueOf(100)).divide(b.net(), 1, RoundingMode.HALF_UP);
            if (change.abs().compareTo(threshold) > 0) {
                add.add("NET_CHANGE", s, Texts.t("Net pay %s%s%% from last month (%s to %s %s)%s.", change.signum() > 0 ? "+" : "",
                        plain(change), plain(b.net()), plain(s.net()), s.currency(), cause(b.payslipId(), s.id())));
            }
        }

        // Attendance and overtime, as the calculation saw them.
        Integer weekLimit = jdbc.sql("""
                select max_weekly_overtime_minutes from overtime_policy
                where country_code = (select country_code from company where id = current_company_id()) and effective_from <= ?
                order by effective_from desc limit 1""").param(last).query(Integer.class).optional().orElse(null);
        for (Slip s : slips) {
            JsonNode snap = json.readTree(s.snapshot());
            int pending = snap.path("overtimePendingMinutes").asInt(0);
            if (pending > 0) {
                add.add("OVERTIME_PENDING", s, Texts.t("%s of overtime still wait for a decision and are not paid.", hours(pending)));
            }
            int week = snap.path("overtimeWeekMaxMinutes").asInt(0);
            if (weekLimit != null && week > weekLimit) {
                add.add("OVERTIME_LIMIT", s, Texts.t("%s of overtime in one week, above the limit of %s.", hours(week), hours(weekLimit)));
            }
            int absent = snap.path("absentDays").asInt(0);
            if (absent > 0) {
                add.add("ABSENT_DAYS", s, Texts.t("%s working day(s) without a check-in or leave.", absent));
            }
        }

        // Requests still waiting.
        jdbc.sql("""
                select user_id, count(*) from leave_request where status = 'PENDING' and start_date <= ? and end_date >= ?
                group by user_id""").params(last, first).query((rs, i) -> {
                    Slip s = byUser.get(rs.getLong(1));
                    if (s != null) {
                        add.add("LEAVE_PENDING", s, Texts.t("%s leave request(s) in this month still wait for a decision.", rs.getInt(2)));
                    }
                    return null;
                }).list();
        jdbc.sql("""
                select user_id, count(*) from correction_request where status in ('PENDING', 'PROPOSED') and work_date between ? and ?
                group by user_id""").params(first, last).query((rs, i) -> {
                    Slip s = byUser.get(rs.getLong(1));
                    if (s != null) {
                        add.add("CORRECTION_PENDING", s, Texts.t("%s attendance correction(s) in this month still wait for a decision.", rs.getInt(2)));
                    }
                    return null;
                }).list();

        // The exchange rate, when pay is converted.
        String local = jdbc.sql("select local_currency from company where id = current_company_id()").query(String.class)
                .optional().orElse("CDF");
        // Pay in another currency than the local one is converted (taxes and contributions are declared locally).
        boolean converted = slips.stream().anyMatch(s -> !s.currency().equals(local));
        LocalDate calculatedOn = run.calculatedAt() == null ? LocalDate.now() : run.calculatedAt().toLocalDate();
        long age = ChronoUnit.DAYS.between(run.rateDate(), calculatedOn);
        if (converted && age > settings.rateMaxDays()) {
            add.add("RATE_OLD", null, Texts.t("The exchange rate used is from %s, %s days before the calculation.",
                    Texts.day(run.rateDate()), age));
        }

        // A rate or the tax scale changed for this month after it was calculated (a published law change, or HR).
        if (run.calculatedAt() != null) {
            List<String> changed = jdbc.sql("""
                    select code from statutory_rate where created_at > ? and effective_from <= ?
                    union select 'IPR' from ipr_bracket where created_at > ? and effective_from <= ?
                    order by 1""").params(run.calculatedAt(), last, run.calculatedAt(), last).query(String.class).list();
            if (!changed.isEmpty()) {
                add.add("RATE_CHANGED", null, Texts.t("Changed since this payroll was calculated: %s. Recalculate it.",
                        String.join(", ", changed)));
            }
        }

        out.sort(Comparator.comparing((Check c) -> c.level()).thenComparing(Check::code)
                .thenComparing(c -> c.name() == null ? "" : c.name()));
        return out;
    }

    @FunctionalInterface
    private interface Builder {
        void add(String code, Slip slip, String message);
    }

    /** The pay line that moved the most between the two payslips. */
    private String cause(long beforeId, long nowId) {
        record L(String code, String label, String kind, BigDecimal amount) {}
        Map<String, BigDecimal> diff = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (long id : List.of(beforeId, nowId)) {
            int sign = id == beforeId ? -1 : 1;
            jdbc.sql("""
                    select element_code as code, label, kind, sum(amount) as amount from payslip_line
                    where payslip_id = ? and kind <> 'EMPLOYER_CHARGE' group by element_code, label, kind""")
                    .param(id).query(L.class).list().forEach(l -> {
                        BigDecimal signed = l.kind().equals("DEDUCTION") ? l.amount().negate() : l.amount();
                        diff.merge(l.code(), signed.multiply(BigDecimal.valueOf(sign)), BigDecimal::add);
                        labels.putIfAbsent(l.code(), l.label());
                    });
        }
        return diff.entrySet().stream().filter(e -> e.getValue().signum() != 0)
                .max(Comparator.comparing(e -> e.getValue().abs()))
                .map(e -> Texts.t(", mostly %s (%s%s)", labels.get(e.getKey()), e.getValue().signum() > 0 ? "+" : "",
                        plain(e.getValue())))
                .orElse("");
    }

    private String decrypt(String stored) {
        return blank(stored) ? null : crypto.decrypt(stored);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String plain(BigDecimal b) {
        return b.stripTrailingZeros().toPlainString();
    }

    private static String hours(int minutes) {
        return "%d h %02d".formatted(minutes / 60, minutes % 60);
    }
}
