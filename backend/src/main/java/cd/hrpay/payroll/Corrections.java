package cd.hrpay.payroll;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.salary.SalaryService;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Corrections of a closed month. A closed payslip is never edited: the person's payslip is worked out again from
 * today's data (salary, attendance, the month's entries, plus any line HR adds for the correction), and the
 * difference is either
 * <ul>
 *   <li>a complementary payslip of that month (its own number once Finance approves it, paid and declared with that
 *       month), or</li>
 *   <li>carried into the next open payroll as "Back pay, earlier month" (or "Overpaid earlier month"), taxed there.</li>
 * </ul>
 * A reversal is a complementary payslip that cancels the whole payslip; what the person then owes is recovered as a
 * salary advance. Back pay is found by comparing each closed payslip's basic salary with the salary on file today.
 */
@Service
public class Corrections {

    public record Extra(long elementId, BigDecimal amount, String note) {}

    public record DiffLine(String code, String label, String kind, BigDecimal before, BigDecimal after, BigDecimal change) {}

    public record Preview(long payslipId, String number, LocalDate month, long userId, String name, String currency,
                          List<DiffLine> lines, BigDecimal grossBefore, BigDecimal grossAfter, BigDecimal netBefore,
                          BigDecimal netAfter, BigDecimal netChange, BigDecimal carried, boolean pending,
                          boolean carriedBefore, BigDecimal basicPaid, BigDecimal basicNow, List<String> warnings) {}

    public record Correction(long id, long payslipId, String number, LocalDate month, long userId, String name,
                             String mode, BigDecimal grossChange, String currency, LocalDate targetMonth,
                             Long complementaryId, String complementaryNumber, BigDecimal net, String status,
                             String reason, String createdBy, OffsetDateTime createdAt) {}

    public record BackPayDue(long payslipId, String number, LocalDate month, long userId, String name, String currency,
                             BigDecimal basicPaid, BigDecimal basicNow, BigDecimal estimate) {}

    private final JdbcClient jdbc;
    private final PayrollRuns runs;
    private final PayElements pay;
    private final HolidayAdjustments adjustments;
    private final SalaryService salaries;
    private final AuditService audit;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public Corrections(JdbcClient jdbc, PayrollRuns runs, PayElements pay, HolidayAdjustments adjustments,
                       SalaryService salaries, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.pay = pay;
        this.adjustments = adjustments;
        this.salaries = salaries;
        this.audit = audit;
        this.clock = clock;
    }

    private record Original(long id, long runId, long userId, String number, String currency, LocalDate month,
                            String runStatus, String kind, BigDecimal usdCdf, String name, String snapshot) {}

    private Original original(long payslipId) {
        Original o = jdbc.sql("""
                select p.id, p.run_id, p.user_id, p.number, trim(p.currency) as currency, r.month, r.status as run_status,
                       p.kind, r.usd_cdf, u.first_name || ' ' || u.last_name as name, p.snapshot::text as snapshot
                from payslip p join payroll_run r on r.id = p.run_id join app_user u on u.id = p.user_id where p.id = ?""")
                .param(payslipId).query(Original.class).optional()
                .orElseThrow(() -> new NotFoundException("Payslip not found."));
        if (!o.kind().equals("REGULAR")) {
            throw new BusinessRuleException("Correct the month's payslip, not a complementary one.");
        }
        if (!o.runStatus().equals("CLOSED")) {
            throw new BusinessRuleException("This month is not closed: calculate its payroll again instead.");
        }
        return o;
    }

    private record SlipLine(String code, String label, String kind, BigDecimal amount, Long loanId) {}

    /** The original payslip's lines and those of its complementary payslips; loan lines only when asked. */
    private List<SlipLine> paidLines(long payslipId, boolean withLoans) {
        return jdbc.sql("""
                select l.element_code as code, l.label, l.kind, l.amount, l.loan_id from payslip_line l join payslip p on p.id = l.payslip_id
                where (p.id = ? or p.corrects_id = ?) order by p.id, l.display_order, l.id""")
                .params(payslipId, payslipId).query(SlipLine.class).list().stream()
                .filter(l -> withLoans || l.loanId() == null).toList();
    }

    private BigDecimal carried(long payslipId) {
        return jdbc.sql("select coalesce(sum(gross_change), 0) from payroll_correction where payslip_id = ? and mode = 'NEXT_PAYROLL'")
                .param(payslipId).query(BigDecimal.class).single();
    }

    private boolean pendingComplementary(long payslipId) {
        return jdbc.sql("select count(*) from payslip where corrects_id = ? and number is null").param(payslipId)
                .query(Integer.class).single() > 0;
    }

    /** The basic salary the payslip was last worked out with (the original, or its latest correction). */
    private BigDecimal basicPaid(long payslipId, String snapshot) {
        return jdbc.sql("""
                select basic_monthly from payroll_correction where payslip_id = ? and basic_monthly is not null
                order by id desc limit 1""").param(payslipId).query(BigDecimal.class).optional()
                .orElseGet(() -> {
                    Object v = json.readValue(snapshot, Map.class).get("basicMonthly");
                    return v == null ? null : new BigDecimal(v.toString());
                });
    }

    private List<PayElements.Entry> extras(Original o, List<Extra> extra) {
        if (extra == null || extra.isEmpty()) {
            return List.of();
        }
        List<PayElements.Entry> out = new ArrayList<>();
        for (Extra x : extra) {
            PayElements.Element e = pay.element(x.elementId());
            if (e.kind().equals("EMPLOYER_CHARGE") || !e.active()) {
                throw new BusinessRuleException("%s cannot be added to a correction.".formatted(e.name()));
            }
            if (x.amount() == null || x.amount().signum() <= 0) {
                throw new BusinessRuleException("Give an amount above zero for %s.".formatted(e.name()));
            }
            out.add(new PayElements.Entry(0, o.userId(), o.name(), e.id(), e.code(), e.name(), e.kind(), o.month(),
                    x.amount(), o.currency(), x.note() == null || x.note().isBlank() ? Texts.t("correction") : x.note().trim(),
                    CurrentUser.email(), OffsetDateTime.now(clock)));
        }
        return out;
    }

    private PayrollRuns.Computed recompute(Original o, List<Extra> extra) {
        YearMonth month = YearMonth.from(o.month());
        var ctx = runs.context(month, runs.rates(month.atEndOfMonth(), o.usdCdf()), o.usdCdf());
        PayrollRuns.Computed c = runs.compute(ctx, o.userId(), false, extras(o, extra));
        if (c.skipped() != null) {
            throw new BusinessRuleException(c.skipped());
        }
        if (!c.result().currency().equals(o.currency())) {
            throw new BusinessRuleException("The pay currency changed since this payslip: correct it with a manual entry in a later month.");
        }
        return c;
    }

    private static String key(String code, String kind) {
        return kind + "|" + code;
    }

    /** Lines grouped by element, before (what was paid) and after (worked out today). */
    private static List<DiffLine> diff(List<SlipLine> before, List<PayslipCalculator.Line> after) {
        Map<String, BigDecimal[]> sums = new LinkedHashMap<>();
        Map<String, String[]> names = new LinkedHashMap<>();
        for (SlipLine l : before) {
            String k = key(l.code(), l.kind());
            sums.computeIfAbsent(k, x -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO})[0] =
                    sums.get(k)[0].add(l.amount());
            names.putIfAbsent(k, new String[] {l.code(), l.label(), l.kind()});
        }
        for (PayslipCalculator.Line l : after) {
            if (l.loanId() != null) {
                continue;
            }
            String k = key(l.code(), l.kind());
            sums.computeIfAbsent(k, x -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO})[1] =
                    sums.get(k)[1].add(l.amount());
            names.put(k, new String[] {l.code(), l.label(), l.kind()});
        }
        List<DiffLine> out = new ArrayList<>();
        sums.forEach((k, v) -> {
            String[] n = names.get(k);
            out.add(new DiffLine(n[0], n[1], n[2], v[0], v[1], v[1].subtract(v[0])));
        });
        return out;
    }

    private static BigDecimal sum(List<DiffLine> lines, String kind, boolean after) {
        return lines.stream().filter(l -> l.kind().equals(kind)).map(l -> after ? l.after() : l.before())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public Preview preview(long payslipId, List<Extra> extra) {
        Original o = original(payslipId);
        PayrollRuns.Computed c = recompute(o, extra);
        List<DiffLine> lines = diff(paidLines(payslipId, false), c.result().lines());
        BigDecimal grossBefore = sum(lines, "EARNING", false);
        BigDecimal grossAfter = sum(lines, "EARNING", true);
        BigDecimal netBefore = grossBefore.subtract(sum(lines, "DEDUCTION", false));
        BigDecimal netAfter = grossAfter.subtract(sum(lines, "DEDUCTION", true));
        BigDecimal carried = carried(payslipId);
        BigDecimal basicNow = c.snapshot().get("basicMonthly") instanceof BigDecimal b ? b : null;
        return new Preview(payslipId, o.number(), o.month(), o.userId(), o.name(), o.currency(), lines, grossBefore,
                grossAfter, netBefore, netAfter, netAfter.subtract(netBefore), carried, pendingComplementary(payslipId),
                carried.signum() != 0, basicPaid(payslipId, o.snapshot()), basicNow, c.warnings());
    }

    /**
     * Records a correction. COMPLEMENTARY: a payslip of the month with the difference, waiting for Finance.
     * NEXT_PAYROLL: the difference in earnings goes into the next open payroll. REVERSAL: a complementary payslip
     * that cancels the whole payslip, loans included.
     */
    @Transactional
    public Correction create(long payslipId, String mode, String reason, List<Extra> extra) {
        Original o = original(payslipId);
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Give the reason for the correction.");
        }
        if (pendingComplementary(payslipId)) {
            throw new ConflictException("A complementary payslip for this payslip waits for approval: approve or discard it first.");
        }
        jdbc.sql("select id from payslip where id = ? for update").param(payslipId).query(Long.class).single();
        reason = reason.trim();
        return switch (mode) {
            case "COMPLEMENTARY" -> complementary(o, reason, extra);
            case "NEXT_PAYROLL" -> carry(o, reason, extra);
            case "REVERSAL" -> reversal(o, reason);
            default -> throw new BusinessRuleException("Unknown correction %s.".formatted(mode));
        };
    }

    private Correction complementary(Original o, String reason, List<Extra> extra) {
        if (carried(o.id()).signum() != 0) {
            throw new BusinessRuleException("This payslip was already corrected in a later payroll: correct it the same way.");
        }
        PayrollRuns.Computed c = recompute(o, extra);
        List<DiffLine> lines = diff(paidLines(o.id(), false), c.result().lines());
        if (lines.stream().allMatch(l -> l.change().abs().compareTo(new BigDecimal("0.01")) < 0)) {
            throw new BusinessRuleException("Worked out again today, this payslip does not change: there is nothing to correct.");
        }
        Totals before = totals(o.id());
        PayslipCalculator.Result r = c.result();
        List<PayslipCalculator.Line> diffLines = new ArrayList<>();
        int order = 0;
        for (DiffLine l : lines) {
            if (l.change().signum() != 0) {
                diffLines.add(new PayslipCalculator.Line(l.code(), l.label(), l.kind(), null, null, l.change(), order(l.code(), ++order), null));
            }
        }
        BigDecimal gross = sumLines(diffLines, "EARNING");
        BigDecimal deductions = sumLines(diffLines, "DEDUCTION");
        PayslipCalculator.Result d = new PayslipCalculator.Result(r.currency(), diffLines, gross,
                r.cnssBase().subtract(before.cnssBase()), r.taxableBase().subtract(before.taxableBase()),
                r.taxableBaseCdf().subtract(before.taxableBaseCdf()), deductions, gross.subtract(deductions),
                sumLines(diffLines, "EMPLOYER_CHARGE"), List.of());
        return storeComplementary(o, c, d, "COMPLEMENTARY", reason);
    }

    private int order(String code, int fallback) {
        return jdbc.sql("select display_order from pay_element where code = ?").param(code).query(Integer.class)
                .optional().orElse(fallback);
    }

    private static BigDecimal sumLines(List<PayslipCalculator.Line> lines, String kind) {
        return lines.stream().filter(l -> l.kind().equals(kind)).map(PayslipCalculator.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private record Totals(BigDecimal cnssBase, BigDecimal taxableBase, BigDecimal taxableBaseCdf) {}

    private Totals totals(long payslipId) {
        return jdbc.sql("""
                select coalesce(sum(cnss_base), 0) as cnss_base, coalesce(sum(taxable_base), 0) as taxable_base,
                       coalesce(sum(taxable_base_cdf), 0) as taxable_base_cdf
                from payslip where id = ? or corrects_id = ?""").params(payslipId, payslipId).query(Totals.class).single();
    }

    private Correction reversal(Original o, String reason) {
        if (carried(o.id()).signum() != 0) {
            throw new BusinessRuleException("This payslip was already corrected in a later payroll: correct it the same way.");
        }
        List<SlipLine> paid = paidLines(o.id(), true);
        Map<String, PayslipCalculator.Line> grouped = new LinkedHashMap<>();
        for (SlipLine l : paid) {
            String k = key(l.code(), l.kind()) + "|" + l.loanId();
            PayslipCalculator.Line prev = grouped.get(k);
            BigDecimal amount = (prev == null ? BigDecimal.ZERO : prev.amount()).subtract(l.amount());
            grouped.put(k, new PayslipCalculator.Line(l.code(), l.label(), l.kind(), null, null, amount,
                    order(l.code(), grouped.size()), l.loanId()));
        }
        List<PayslipCalculator.Line> lines = grouped.values().stream().filter(l -> l.amount().signum() != 0).toList();
        if (lines.isEmpty()) {
            throw new BusinessRuleException("This payslip is already cancelled.");
        }
        Totals before = totals(o.id());
        BigDecimal gross = sumLines(lines, "EARNING");
        BigDecimal deductions = sumLines(lines, "DEDUCTION");
        PayslipCalculator.Result d = new PayslipCalculator.Result(o.currency(), lines, gross, before.cnssBase().negate(),
                before.taxableBase().negate(), before.taxableBaseCdf().negate(), deductions, gross.subtract(deductions),
                sumLines(lines, "EMPLOYER_CHARGE"), List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = json.readValue(o.snapshot(), Map.class);
        var c = new PayrollRuns.Computed(o.userId(), o.name(), d, snapshot, List.of(), 0, 0, 0, null, null);
        return storeComplementary(o, c, d, "REVERSAL", reason);
    }

    private Correction storeComplementary(Original o, PayrollRuns.Computed c, PayslipCalculator.Result d, String mode, String reason) {
        var computed = new PayrollRuns.Computed(o.userId(), o.name(), d, c.snapshot(), List.of(), 0, 0, 0,
                jdbc.sql("select unit_id from payslip where id = ?").param(o.id()).query(Long.class).optional().orElse(null), null);
        long slip = runs.store(o.runId(), computed, "COMPLEMENTARY", o.id(), reason);
        BigDecimal basic = mode.equals("REVERSAL") ? null
                : c.snapshot().get("basicMonthly") instanceof BigDecimal b ? b : null;
        long id = jdbc.sql("""
                insert into payroll_correction (payslip_id, user_id, mode, gross_change, currency, basic_monthly,
                    complementary_id, reason, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(o.id(), o.userId(), mode, d.gross(), o.currency(), basic, slip, reason, CurrentUser.email())
                .query(Long.class).single();
        audit.record("payroll.correction_created", "payslip", o.id(), Map.of("mode", mode, "complementary", slip,
                "gross", d.gross().toPlainString(), "net", d.net().toPlainString(), "currency", o.currency(), "reason", reason));
        return correction(id);
    }

    private Correction carry(Original o, String reason, List<Extra> extra) {
        if (jdbc.sql("select count(*) from payslip where corrects_id = ?").param(o.id()).query(Integer.class).single() > 0) {
            throw new BusinessRuleException("This payslip was already corrected by a complementary payslip: correct it the same way.");
        }
        PayrollRuns.Computed c = recompute(o, extra);
        List<DiffLine> lines = diff(paidLines(o.id(), false), c.result().lines());
        BigDecimal change = sum(lines, "EARNING", true).subtract(sum(lines, "EARNING", false)).subtract(carried(o.id()))
                .setScale(2, RoundingMode.HALF_UP);
        if (change.abs().compareTo(new BigDecimal("0.01")) < 0) {
            throw new BusinessRuleException("Worked out again today, the earnings of this payslip do not change: there is nothing to carry.");
        }
        YearMonth target = adjustments.targetMonth();
        BigDecimal basic = c.snapshot().get("basicMonthly") instanceof BigDecimal b ? b : null;
        long id = jdbc.sql("""
                insert into payroll_correction (payslip_id, user_id, mode, gross_change, currency, basic_monthly,
                    target_month, reason, created_by)
                values (?, ?, 'NEXT_PAYROLL', ?, ?, ?, ?, ?, ?) returning id""")
                .params(o.id(), o.userId(), change, o.currency(), basic, target.atDay(1), reason, CurrentUser.email())
                .query(Long.class).single();
        rebuildEntries(o.userId(), target);
        audit.record("payroll.correction_created", "payslip", o.id(), Map.of("mode", "NEXT_PAYROLL",
                "gross", change.toPlainString(), "currency", o.currency(), "carriedTo", target.toString(), "reason", reason));
        return correction(id);
    }

    /** One pay entry per person for the target month: the sum of what corrections carry into it. */
    private void rebuildEntries(long userId, YearMonth target) {
        long more = elementId("BACK_PAY");
        long less = elementId("BACK_PAY_LESS");
        jdbc.sql("delete from pay_entry where user_id = ? and month = ? and element_id in (?, ?)")
                .params(userId, target.atDay(1), more, less).update();
        var sums = jdbc.sql("""
                select c.currency, sum(c.gross_change) as amount, string_agg(distinct to_char(r.month, 'YYYY-MM'), ', ') as months
                from payroll_correction c join payslip p on p.id = c.payslip_id join payroll_run r on r.id = p.run_id
                where c.user_id = ? and c.target_month = ? and c.mode = 'NEXT_PAYROLL' group by c.currency""")
                .params(userId, target.atDay(1))
                .query((rs, i) -> new Object[] {rs.getString(1), rs.getBigDecimal(2), rs.getString(3)}).list();
        for (Object[] sum : sums) {
            BigDecimal amount = (BigDecimal) sum[1];
            if (amount.signum() == 0) {
                continue;
            }
            jdbc.sql("""
                    insert into pay_entry (user_id, element_id, month, amount, currency, note, created_by)
                    values (?, ?, ?, ?, ?, ?, ?)""")
                    .params(userId, amount.signum() > 0 ? more : less, target.atDay(1), amount.abs(), sum[0],
                            (String) sum[2], CurrentUser.email() == null ? "system" : CurrentUser.email())
                    .update();
            break;   // One currency per person; a second one would clash with the first entry.
        }
    }

    private long elementId(String code) {
        return jdbc.sql("select id from pay_element where code = ?").param(code).query(Long.class).single();
    }

    private static final String SELECT = """
            select c.id, c.payslip_id, p.number, r.month, c.user_id, u.first_name || ' ' || u.last_name as name, c.mode,
                   c.gross_change, c.currency, c.target_month, c.complementary_id, k.number as complementary_number,
                   k.net, case when c.complementary_id is null then 'CARRIED' when k.number is null then 'WAITING'
                               else 'APPROVED' end as status, c.reason, c.created_by, c.created_at
            from payroll_correction c join payslip p on p.id = c.payslip_id join payroll_run r on r.id = p.run_id
            join app_user u on u.id = c.user_id left join payslip k on k.id = c.complementary_id""";

    public Correction correction(long id) {
        return jdbc.sql(SELECT + " where c.id = ?").param(id).query(Correction.class).optional()
                .orElseThrow(() -> new NotFoundException("Correction not found."));
    }

    public List<Correction> list() {
        return jdbc.sql(SELECT + " order by c.id desc limit 200").query(Correction.class).list();
    }

    /** Finance approves a complementary payslip: it gets its number; what the person owes becomes an advance to recover. */
    @Transactional
    public Correction approve(long id) {
        Correction c = correction(id);
        if (c.complementaryId() == null || !c.status().equals("WAITING")) {
            throw new BusinessRuleException("Only a complementary payslip waiting for approval can be approved.");
        }
        if (Objects.equals(c.createdBy(), CurrentUser.email())) {
            throw new AccessDeniedException("The person who made the correction cannot also approve it.");
        }
        int n = jdbc.sql("select count(*) from payslip where corrects_id = ? and number is not null").param(c.payslipId())
                .query(Integer.class).single() + 1;
        String number = c.number() + "-C" + n;
        jdbc.sql("update payslip set number = ?, approved_by = ?, approved_at = now() where id = ?")
                .params(number, CurrentUser.email(), c.complementaryId()).update();
        if (c.mode().equals("REVERSAL")) {
            // The loans the payslip repaid are owed again.
            var loans = jdbc.sql("select distinct loan_id from payslip_line where payslip_id = ? and loan_id is not null")
                    .param(c.payslipId()).query(Long.class).list();
            long runId = jdbc.sql("select run_id from payslip where id = ?").param(c.payslipId()).query(Long.class).single();
            for (long loan : loans) {
                jdbc.sql("delete from loan_repayment where loan_id = ? and run_id = ?").params(loan, runId).update();
                jdbc.sql("update loan set status = 'ACTIVE' where id = ? and status = 'REPAID'").param(loan).update();
            }
        }
        if (c.net() != null && c.net().signum() < 0) {
            YearMonth start = adjustments.targetMonth();
            BigDecimal owed = c.net().negate();
            jdbc.sql("""
                    insert into loan (user_id, kind, principal, currency, installment, start_month, granted_on, note, created_by)
                    values (?, 'ADVANCE', ?, ?, ?, ?, ?, ?, ?)""")
                    .params(c.userId(), owed, c.currency(), owed, start.atDay(1), LocalDate.now(clock),
                            Texts.t("Overpaid, payslip %s", number), CurrentUser.email())
                    .update();
        }
        audit.record("payroll.correction_approved", "payslip", c.complementaryId(), Map.of("number", number,
                "net", c.net() == null ? "" : c.net().toPlainString(), "currency", c.currency()));
        return correction(id);
    }

    /** Takes back a correction: a complementary payslip not yet approved, or a carry whose month is still open. */
    @Transactional
    public void discard(long id) {
        Correction c = correction(id);
        if (c.status().equals("APPROVED")) {
            throw new BusinessRuleException("An approved complementary payslip stays: correct it with a new correction.");
        }
        if (c.status().equals("CARRIED")) {
            YearMonth target = YearMonth.from(c.targetMonth());
            PayrollRuns.requireOpen(jdbc, target);
            jdbc.sql("delete from payroll_correction where id = ?").param(id).update();
            rebuildEntries(c.userId(), target);
        } else {
            jdbc.sql("delete from payroll_correction where id = ?").param(id).update();
            jdbc.sql("delete from payslip where id = ?").param(c.complementaryId()).update();
        }
        audit.record("payroll.correction_discarded", "payslip", c.payslipId(), Map.of("mode", c.mode()));
    }

    // ---- back pay ---------------------------------------------------------------------------------------------

    private record Closed(long id, String number, LocalDate month, long userId, String name, String currency,
                          int scheduledMinutes, int paidMinutes, String snapshot) {}

    /**
     * Closed payslips of the last twelve months whose basic salary is now different on file (a raise or grade
     * change dated in the past): what the basic part of each would change.
     */
    public List<BackPayDue> backPayDue() {
        LocalDate from = YearMonth.now(clock).minusMonths(12).atDay(1);
        List<Closed> slips = jdbc.sql("""
                select p.id, p.number, r.month, p.user_id, u.first_name || ' ' || u.last_name as name,
                       trim(p.currency) as currency, p.scheduled_minutes, p.paid_minutes, p.snapshot::text as snapshot
                from payslip p join payroll_run r on r.id = p.run_id join app_user u on u.id = p.user_id
                where r.status = 'CLOSED' and p.kind = 'REGULAR' and r.month >= ?
                  and not exists (select 1 from payslip k where k.corrects_id = p.id and k.number is null)
                order by u.last_name, u.first_name, r.month""").param(from).query(Closed.class).list();
        List<BackPayDue> due = new ArrayList<>();
        for (Closed s : slips) {
            BigDecimal paid = basicPaid(s.id(), s.snapshot());
            var now = salaries.on(s.userId(), s.month().withDayOfMonth(s.month().lengthOfMonth())).orElse(null);
            if (paid == null || now == null || !now.currency().equals(s.currency())
                    || now.grossMonthly().subtract(paid).abs().compareTo(new BigDecimal("0.01")) < 0) {
                continue;
            }
            BigDecimal share = s.scheduledMinutes() == 0 ? BigDecimal.ONE
                    : BigDecimal.valueOf(s.paidMinutes()).divide(BigDecimal.valueOf(s.scheduledMinutes()), 10, RoundingMode.HALF_UP);
            due.add(new BackPayDue(s.id(), s.number(), s.month(), s.userId(), s.name(), s.currency(), paid,
                    now.grossMonthly(), now.grossMonthly().subtract(paid).multiply(share).setScale(2, RoundingMode.HALF_UP)));
        }
        return due;
    }

    /** Carries the back pay of these payslips (all due, when none is named) into the next open payroll. */
    @Transactional
    public List<Correction> payBackPay(List<Long> payslipIds) {
        List<Correction> done = new ArrayList<>();
        for (BackPayDue d : backPayDue()) {
            if (payslipIds != null && !payslipIds.isEmpty() && !payslipIds.contains(d.payslipId())) {
                continue;
            }
            String reason = Texts.t("Back pay: basic salary %s instead of %s %s", d.basicNow().toPlainString(),
                    d.basicPaid().toPlainString(), d.currency());
            Original o = original(d.payslipId());
            if (jdbc.sql("select count(*) from payslip where corrects_id = ?").param(o.id()).query(Integer.class).single() > 0) {
                done.add(complementary(o, reason, List.of()));
            } else {
                try {
                    done.add(carry(o, reason, List.of()));
                } catch (BusinessRuleException nothing) {
                    // the earnings do not change after all (for instance an unpaid month): nothing to carry
                }
            }
        }
        return done;
    }
}
