package cd.hrpay.payroll;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.evaluation.ThirteenthMonth;
import cd.hrpay.i18n.Texts;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The 13th month as its own pay run, for companies that pay one. HR works it out for a year and chooses the month-end
 * payroll that pays it; Finance approves it (not the person who worked it out); that payroll then puts each approved
 * amount on the payslip, where CNSS and income tax are worked out with the month's pay (IPR is a monthly scale, so a
 * taxable payment is never taxed on its own). Closing that payroll marks the run paid.
 */
@Service
public class Thirteenths {

    public record Exclusion(long userId, String name, String reason) {}

    public record Line(long id, long userId, String employeeName, String registrationNo, String currency,
                       BigDecimal scorePercent, String scoreSource, int evaluations, BigDecimal base, int monthsWorked,
                       BigDecimal amount) {}

    public record Total(String currency, BigDecimal amount, int employees) {}

    public record Run(long id, int year, LocalDate month, String base, BigDecimal fallbackPercent, boolean prorate,
                      String status, List<String> skipped, List<Exclusion> excluded, String calculatedBy,
                      OffsetDateTime calculatedAt, String approvedBy, OffsetDateTime approvedAt, OffsetDateTime paidAt,
                      String cancelNote, List<Total> totals, List<Line> lines) {}

    record RunRow(long id, int year, LocalDate month, String base, BigDecimal fallbackPercent, boolean prorate,
                  String status, String skipped, String excluded, String calculatedBy, OffsetDateTime calculatedAt,
                  String approvedBy, OffsetDateTime approvedAt, OffsetDateTime paidAt, String cancelNote) {}

    /** What a month-end payslip takes from an approved run. */
    public record Due(BigDecimal amount, String currency, String note) {}

    private static final String RUN_SELECT = """
            select id, year, month, base, fallback_percent, prorate, status, skipped::text as skipped,
                   excluded::text as excluded, calculated_by, calculated_at, approved_by, approved_at, paid_at, cancel_note
            from thirteenth_run""";

    private final JdbcClient jdbc;
    private final ThirteenthMonth rules;
    private final OrgStructure org;
    private final AuditService audit;
    private final JsonMapper json;

    public Thirteenths(JdbcClient jdbc, ThirteenthMonth rules, OrgStructure org, AuditService audit, JsonMapper json) {
        this.jdbc = jdbc;
        this.rules = rules;
        this.org = org;
        this.audit = audit;
        this.json = json;
    }

    public ThirteenthMonth.Settings settings() {
        return rules.settings();
    }

    /** The payroll month the settings suggest for a year: the pay month of that year. */
    public YearMonth defaultMonth(int year) {
        return YearMonth.of(year, rules.settings().payMonth());
    }

    public List<Run> runs() {
        return jdbc.sql(RUN_SELECT + " order by year desc, id desc").query(RunRow.class).list().stream().map(this::view).toList();
    }

    public Run find(long id) {
        return jdbc.sql(RUN_SELECT + " where id = ?").param(id).query(RunRow.class).optional().map(this::view)
                .orElseThrow(() -> new NotFoundException("13th month run not found."));
    }

    private Optional<RunRow> open(int year) {
        return jdbc.sql(RUN_SELECT + " where year = ? and status <> 'CANCELLED'").param(year).query(RunRow.class).optional();
    }

    /** Works out (or again, while not approved) the 13th month of a year, paid with the payroll of the given month. */
    @Transactional
    public Run calculate(int year, YearMonth month) {
        ThirteenthMonth.Settings s = rules.settings();
        if (!s.enabled()) {
            throw new BusinessRuleException("The company does not pay a 13th month: turn it on in the 13th month settings.");
        }
        if (month.getYear() != year && month.getYear() != year + 1) {
            throw new BusinessRuleException("The 13th month of a year is paid with a payroll of that year or the next.");
        }
        PayrollRuns.requireOpen(jdbc, month);
        RunRow existing = open(year).orElse(null);
        long runId;
        List<Exclusion> excluded = List.of();
        if (existing != null) {
            if (!existing.status().equals("CALCULATED")) {
                throw new BusinessRuleException("The 13th month of this year is already approved.");
            }
            excluded = exclusions(existing.excluded());
            jdbc.sql("delete from thirteenth_line where run_id = ?").param(existing.id()).update();
            jdbc.sql("""
                    update thirteenth_run set month = ?, base = ?, fallback_percent = ?, prorate = ?, calculated_by = ?,
                           calculated_at = now() where id = ?""")
                    .params(month.atDay(1), s.base(), s.fallbackPercent(), s.prorate(), CurrentUser.email(), existing.id())
                    .update();
            runId = existing.id();
        } else {
            runId = jdbc.sql("""
                    insert into thirteenth_run (year, month, base, fallback_percent, prorate, calculated_by)
                    values (?, ?, ?, ?, ?, ?) returning id""")
                    .params(year, month.atDay(1), s.base(), s.fallbackPercent(), s.prorate(), CurrentUser.email())
                    .query(Long.class).single();
        }
        List<String> skipped = new ArrayList<>();
        // Those whose final settlement already paid their share of this year's 13th month.
        Map<Long, String> settled = new TreeMap<>();
        jdbc.sql("""
                select d.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name from departure d join app_user u on u.id = d.user_id
                where d.status = 'CONFIRMED' and d.thirteenth_amount > 0 and extract(year from d.last_day) = ?""")
                .param(year).query((rs, i) -> settled.put(rs.getLong(1), rs.getString(2))).list();
        Map<Long, OrgStructure.Placement> places = org.placementsOn(month.atEndOfMonth());
        List<Map.Entry<Long, String>> people = jdbc.sql("""
                select distinct u.id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.last_name, u.first_name
                from app_user u join employee_contract c on c.user_id = u.id
                where u.status = 'ACTIVE' and c.start_date <= ? and (c.end_date is null or c.end_date >= ?)
                order by u.last_name, u.first_name""").params(LocalDate.of(year, 12, 31), LocalDate.of(year, 1, 1))
                .query((rs, i) -> Map.entry(rs.getLong("id"), rs.getString("name"))).list();
        int done = 0;
        int n = 0;
        for (Map.Entry<Long, String> p : people) {
            cd.hrpay.job.Progress.report(++n, people.size(), Texts.t("Employee %s of %s", n, people.size()));
            long userId = p.getKey();
            Exclusion out = excluded.stream().filter(x -> x.userId() == userId).findFirst().orElse(null);
            if (out != null) {
                skipped.add(Texts.t("%s: left out by HR (%s).", p.getValue(), out.reason()));
                continue;
            }
            if (settled.containsKey(userId)) {
                skipped.add(Texts.t("%s: share already paid with the final settlement.", p.getValue()));
                continue;
            }
            ThirteenthMonth.Row row = rules.forEmployee(userId, p.getValue(), year, s).orElse(null);
            if (row == null) {
                skipped.add(Texts.t("%s: no salary on file for %s.", p.getValue(), String.valueOf(year)));
                continue;
            }
            if (row.amount().signum() <= 0) {
                skipped.add(Texts.t("%s: nothing to pay (score %s%%).", p.getValue(), row.scorePercent().toPlainString()));
                continue;
            }
            OrgStructure.Placement place = places.get(userId);
            jdbc.sql("""
                    insert into thirteenth_line (run_id, user_id, currency, score_percent, score_source, evaluations, base,
                                                 months_worked, amount, unit_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(runId, userId, row.currency(), row.scorePercent(), row.scoreSource(), row.evaluations(),
                            row.base(), row.monthsWorked(), row.amount(), place == null ? null : place.unitId())
                    .update();
            done++;
        }
        jdbc.sql("update thirteenth_run set skipped = ?::jsonb where id = ?").params(json.writeValueAsString(skipped), runId).update();
        audit.record("thirteenth.calculated", "thirteenth_run", runId, Map.of("year", year, "month", month.toString(),
                "employees", done, "skipped", skipped.size()));
        return find(runId);
    }

    /** HR leaves one person out, with a reason, before Finance approves; working it out again keeps them out. */
    @Transactional
    public Run exclude(long id, long userId, String reason) {
        Run r = find(id);
        if (!r.status().equals("CALCULATED")) {
            throw new BusinessRuleException("Only a 13th month not yet approved can change.");
        }
        Line line = r.lines().stream().filter(l -> l.userId() == userId).findFirst()
                .orElseThrow(() -> new NotFoundException("This person is not in the run."));
        List<Exclusion> out = new ArrayList<>(r.excluded());
        out.add(new Exclusion(userId, line.employeeName(), reason.trim()));
        List<String> skipped = new ArrayList<>(r.skipped());
        skipped.add(Texts.t("%s: left out by HR (%s).", line.employeeName(), reason.trim()));
        jdbc.sql("delete from thirteenth_line where id = ?").param(line.id()).update();
        jdbc.sql("update thirteenth_run set excluded = ?::jsonb, skipped = ?::jsonb where id = ?")
                .params(json.writeValueAsString(out), json.writeValueAsString(skipped), id).update();
        audit.record("thirteenth.excluded", "thirteenth_run", id, Map.of("user", userId, "reason", reason.trim()));
        return find(id);
    }

    /** Puts back someone HR left out; they come back when the run is worked out again. */
    @Transactional
    public Run include(long id, long userId) {
        Run r = find(id);
        if (!r.status().equals("CALCULATED")) {
            throw new BusinessRuleException("Only a 13th month not yet approved can change.");
        }
        List<Exclusion> out = r.excluded().stream().filter(x -> x.userId() != userId).toList();
        jdbc.sql("update thirteenth_run set excluded = ?::jsonb where id = ?").params(json.writeValueAsString(out), id).update();
        return find(id);
    }

    /** Finance approves (not the person who worked it out): the payroll of the month then pays each amount. */
    @Transactional
    public Run approve(long id) {
        Run r = find(id);
        if (!r.status().equals("CALCULATED")) {
            throw new BusinessRuleException("Only a calculated 13th month can be approved.");
        }
        if (r.calculatedBy().equalsIgnoreCase(CurrentUser.email())) {
            throw new BusinessRuleException("Someone other than the person who worked it out must approve it.");
        }
        if (r.lines().isEmpty()) {
            throw new BusinessRuleException("Nobody to pay in this 13th month.");
        }
        PayrollRuns.requireOpen(jdbc, YearMonth.from(r.month()));
        jdbc.sql("update thirteenth_run set status = 'APPROVED', approved_by = ?, approved_at = now() where id = ?")
                .params(CurrentUser.email(), id).update();
        audit.record("thirteenth.approved", "thirteenth_run", id, Map.of("year", r.year(), "employees", r.lines().size()));
        return find(id);
    }

    /** Takes back a run not yet paid; the month's payroll must be worked out again if it was. */
    @Transactional
    public Run cancel(long id, String note) {
        Run r = find(id);
        if (!r.status().equals("CALCULATED") && !r.status().equals("APPROVED")) {
            throw new BusinessRuleException("Only a 13th month not yet paid can be cancelled.");
        }
        if (r.status().equals("APPROVED")) {
            PayrollRuns.requireOpen(jdbc, YearMonth.from(r.month()));
        }
        jdbc.sql("update thirteenth_run set status = 'CANCELLED', cancelled_by = ?, cancelled_at = now(), cancel_note = ? where id = ?")
                .params(CurrentUser.email(), note == null || note.isBlank() ? null : note.trim(), id).update();
        audit.record("thirteenth.cancelled", "thirteenth_run", id, note == null ? Map.of() : Map.of("note", note));
        return find(id);
    }

    /** Each person's approved 13th month for a month-end payroll. */
    Map<Long, Due> dueIn(YearMonth month) {
        Map<Long, Due> out = new java.util.HashMap<>();
        jdbc.sql("""
                select l.user_id, l.amount, trim(l.currency) as currency, l.score_percent, l.score_source, l.months_worked, r.year
                from thirteenth_line l join thirteenth_run r on r.id = l.run_id
                where r.month = ? and r.status in ('APPROVED', 'PAID')""")
                .param(month.atDay(1))
                .query((rs, i) -> out.put(rs.getLong("user_id"), new Due(rs.getBigDecimal("amount"), rs.getString("currency"),
                        Texts.t("%s, score %s%%%s%s", String.valueOf(rs.getInt("year")),
                                rs.getBigDecimal("score_percent").stripTrailingZeros().toPlainString(),
                                rs.getString("score_source").equals("FALLBACK") ? Texts.t(", no evaluation") : "",
                                rs.getInt("months_worked") < 12 ? Texts.t(", %s/12 months", rs.getInt("months_worked")) : ""))))
                .list();
        return out;
    }

    /** A payroll closes only once the 13th month it pays is approved and on its payslips. */
    static void requireSettled(JdbcClient jdbc, YearMonth month, OffsetDateTime payrollCalculatedAt) {
        jdbc.sql("select status, approved_at from thirteenth_run where month = ? and status in ('CALCULATED', 'APPROVED')")
                .param(month.atDay(1))
                .query((rs, i) -> {
                    if (rs.getString(1).equals("CALCULATED")) {
                        throw new BusinessRuleException("Approve or cancel the 13th month paid with this payroll before closing it.");
                    }
                    OffsetDateTime approved = rs.getObject(2, OffsetDateTime.class);
                    if (approved != null && payrollCalculatedAt != null && approved.isAfter(payrollCalculatedAt)) {
                        throw new BusinessRuleException("The 13th month was approved after this payroll was calculated: calculate the payroll again.");
                    }
                    return null;
                }).list();
    }

    /** Closing the payroll pays the 13th month it carries. */
    static void markPaid(JdbcClient jdbc, YearMonth month) {
        jdbc.sql("update thirteenth_run set status = 'PAID', paid_at = now() where month = ? and status = 'APPROVED'")
                .param(month.atDay(1)).update();
    }

    private List<Exclusion> exclusions(String text) {
        return text == null ? List.of() : List.of(json.readValue(text, Exclusion[].class));
    }

    private List<String> strings(String text) {
        return text == null ? List.of() : List.of(json.readValue(text, String[].class));
    }

    private Run view(RunRow r) {
        List<Line> lines = jdbc.sql("""
                select l.id, l.user_id, person_name(u.first_name, u.middle_name, u.last_name) as employee_name, u.registration_no,
                       trim(l.currency) as currency, l.score_percent, l.score_source, l.evaluations, l.base, l.months_worked, l.amount
                from thirteenth_line l join app_user u on u.id = l.user_id
                where l.run_id = ? order by u.last_name, u.first_name, u.id""").param(r.id()).query(Line.class).list();
        Map<String, Total> totals = new TreeMap<>();
        for (Line l : lines) {
            totals.merge(l.currency(), new Total(l.currency(), l.amount(), 1),
                    (a, b) -> new Total(a.currency(), a.amount().add(b.amount()), a.employees() + 1));
        }
        return new Run(r.id(), r.year(), r.month(), r.base(), r.fallbackPercent(), r.prorate(), r.status(),
                strings(r.skipped()), exclusions(r.excluded()), r.calculatedBy(), r.calculatedAt(), r.approvedBy(),
                r.approvedAt(), r.paidAt(), r.cancelNote(), List.copyOf(totals.values()), lines);
    }
}
