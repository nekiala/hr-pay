package cd.hrpay.payment;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * When each declaration is due, where it stands, and what lateness would cost. The due dates and penalties are the
 * company's declaration rules (data, one row per kind). A declaration of month M is due in month M + 1; a quarterly
 * one after the quarter's last month. Declared and paid are recorded apart.
 */
@Service
public class DeclarationCalendar {

    public static final Set<String> MODES = Set.of("CASH", "OV", "OP", "CHEQUE");

    /** Statuses, in the order a declaration goes through them; NIL is a month with nothing to declare. */
    public enum Status { TO_PREPARE, READY, DECLARED, PAID, NIL }

    public record Rule(String kind, String authority, int declareDay, int payDay, Integer quarterlyUpToStaff,
                       BigDecimal lateDeclarationPercent, BigDecimal lateDeclarationPercentLater, Integer laterAfterMonths,
                       BigDecimal latePaymentPercentMonth, BigDecimal latePaymentPercentDay, Integer dailyFromDay,
                       String legalBasis, boolean verified) {}

    public record Paid(LocalDate paidOn, BigDecimal amount, String reference, String mode, String paidBy, OffsetDateTime paidAt) {}

    public record Penalty(BigDecimal lateDeclaration, BigDecimal latePayment, BigDecimal total, List<String> how) {}

    public record Item(String month, String kind, String authority, LocalDate declareDue, LocalDate payDue,
                       boolean quarterly, Status status, boolean late, LocalDate nextDue, long daysLeft,
                       BigDecimal amount, Declarations.Filed filed, Paid paid, Penalty penalty, String annual,
                       String legalBasis, boolean verified) {
        /** Still waiting for something: to declare, or to pay. */
        public boolean open() {
            return status != Status.PAID && status != Status.NIL;
        }
    }

    public record Page(List<Item> items, String currency, List<Rule> rules) {}

    private record Recorded(LocalDate month, String kind, BigDecimal totalCdf, LocalDate filedOn, String reference,
                            String note, String filedBy, OffsetDateTime filedAt, LocalDate paidOn, BigDecimal paidAmount,
                            String paidReference, String paidMode, String paidBy, OffsetDateTime paidAt) {}

    private record RunRow(LocalDate month, String status, int staff) {}

    private final JdbcClient jdbc;
    private final Declarations declarations;
    private final AuditService audit;
    private final Clock clock;

    public DeclarationCalendar(JdbcClient jdbc, Declarations declarations, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.declarations = declarations;
        this.audit = audit;
        this.clock = clock;
    }

    public List<Rule> rules() {
        return jdbc.sql("""
                select kind, authority, declare_day, pay_day, quarterly_up_to_staff, late_declaration_percent,
                       late_declaration_percent_later, later_after_months, late_payment_percent_month,
                       late_payment_percent_day, daily_from_day, legal_basis, verified
                from declaration_rule order by id""").query(Rule.class).list();
    }

    private String country() {
        return jdbc.sql("select country_code from company where id = current_company_id()").query(String.class).optional().orElse("CD");
    }

    private String local() {
        return jdbc.sql("select local_currency from company where id = current_company_id()").query(String.class).optional().orElse("CDF");
    }

    /** The last months, the current one included (it is due next month), from the company's first payroll. */
    public Page page(int months) {
        YearMonth to = YearMonth.now(clock);
        return new Page(items(to.minusMonths(Math.max(1, months) - 1L), to), local(), rules());
    }

    /** The declaration that falls due first among those still open, if any. */
    public Optional<Item> next() {
        YearMonth to = YearMonth.now(clock);
        return items(to.minusMonths(3), to).stream().filter(Item::open)
                .min(Comparator.comparing(Item::nextDue).thenComparing(Item::kind));
    }

    public List<Item> items(YearMonth from, YearMonth to) {
        LocalDate first = jdbc.sql("select min(month) from payroll_run").query(LocalDate.class).optional().orElse(null);
        if (first == null) {
            return List.of();
        }
        YearMonth start = YearMonth.from(first).isAfter(from) ? YearMonth.from(first) : from;
        Map<String, Rule> rules = new HashMap<>();
        rules().forEach(r -> rules.put(r.kind(), r));
        List<String> kinds = declarations.kinds().stream().filter(rules::containsKey).toList();
        Map<YearMonth, RunRow> runs = new HashMap<>();
        jdbc.sql("""
                select r.month, r.status, (select count(*) from payslip p where p.run_id = r.id and p.kind = 'REGULAR') as staff
                from payroll_run r where r.month between ? and ?""").params(start.atDay(1), to.atDay(1))
                .query(RunRow.class).list().forEach(r -> runs.put(YearMonth.from(r.month()), r));
        Map<String, Recorded> recorded = new HashMap<>();
        jdbc.sql("""
                select month, kind, total_cdf, filed_on, reference, note, filed_by, filed_at, paid_on, paid_amount,
                       paid_reference, paid_mode, paid_by, paid_at
                from declaration where month between ? and ?""").params(start.atDay(1), to.atDay(1))
                .query(Recorded.class).list().forEach(r -> recorded.put(YearMonth.from(r.month()) + " " + r.kind(), r));
        String country = country();
        LocalDate today = LocalDate.now(clock);
        List<Item> out = new ArrayList<>();
        for (YearMonth m = start; !m.isAfter(to); m = m.plusMonths(1)) {
            RunRow run = runs.get(m);
            YearMonth month = m;
            Map<String, BigDecimal> computed = new HashMap<>();
            if (run != null && kinds.stream().anyMatch(k -> !recorded.containsKey(month + " " + k))) {
                for (Declarations.Summary s : declarations.summary(m)) {
                    computed.put(s.kind(), s.employees() == 0 && !s.kind().equals("IPR") ? null : s.totalCdf());
                }
            }
            for (String kind : kinds) {
                Rule rule = rules.get(kind);
                Recorded rec = recorded.get(m + " " + kind);
                boolean quarterly = rule.quarterlyUpToStaff() != null && run != null && run.staff() <= rule.quarterlyUpToStaff();
                YearMonth dueMonth = quarterly ? YearMonth.of(m.getYear(), ((m.getMonthValue() - 1) / 3 + 1) * 3).plusMonths(1) : m.plusMonths(1);
                LocalDate declareDue = dueMonth.atDay(rule.declareDay());
                LocalDate payDue = dueMonth.atDay(rule.payDay());
                Declarations.Filed filed = rec == null ? null
                        : new Declarations.Filed(rec.filedOn(), rec.reference(), rec.note(), rec.filedBy(), rec.filedAt());
                Paid paid = rec == null || rec.paidOn() == null ? null
                        : new Paid(rec.paidOn(), rec.paidAmount(), rec.paidReference(), rec.paidMode(), rec.paidBy(), rec.paidAt());
                BigDecimal amount = rec != null ? rec.totalCdf() : computed.get(kind);
                boolean nil = rec == null && run != null && computed.containsKey(kind) && computed.get(kind) == null;
                Status status = paid != null ? Status.PAID : filed != null ? Status.DECLARED : nil ? Status.NIL
                        : run != null && run.status().equals("CLOSED") ? Status.READY : Status.TO_PREPARE;
                LocalDate nextDue = filed == null ? declareDue : payDue;
                boolean late = (status != Status.PAID && status != Status.NIL && today.isAfter(nextDue))
                        || (filed != null && filed.filedOn().isAfter(declareDue))
                        || (paid != null && paid.paidOn().isAfter(payDue));
                Penalty penalty = status == Status.NIL ? null : penalty(rule, amount, declareDue, payDue,
                        filed == null ? null : filed.filedOn(), paid == null ? null : paid.paidOn(), today);
                out.add(new Item(m.toString(), kind, rule.authority(), declareDue, payDue, quarterly, status, late, nextDue,
                        ChronoUnit.DAYS.between(today, nextDue), amount, filed, paid, penalty, annual(country, m, kind),
                        rule.legalBasis(), rule.verified()));
            }
        }
        return out;
    }

    /** What lateness costs at the rule's rates, up to today or to the day it was declared and paid. An estimate. */
    static Penalty penalty(Rule rule, BigDecimal amount, LocalDate declareDue, LocalDate payDue, LocalDate declaredOn,
                           LocalDate paidOn, LocalDate today) {
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        if (amount == null || amount.signum() <= 0) {
            return new Penalty(zero, zero, zero, List.of());
        }
        List<String> how = new ArrayList<>();
        BigDecimal declaration = zero;
        LocalDate declared = declaredOn != null ? declaredOn : today;
        if (declared.isAfter(declareDue) && rule.lateDeclarationPercent() != null) {
            long months = startedMonths(declareDue, declared);
            BigDecimal pct = rule.lateDeclarationPercentLater() != null && rule.laterAfterMonths() != null
                    && months > rule.laterAfterMonths() ? rule.lateDeclarationPercentLater() : rule.lateDeclarationPercent();
            declaration = percent(amount, pct);
            how.add(Texts.t("Late declaration: %s %% of the amount.", plain(pct)));
        }
        BigDecimal payment = zero;
        LocalDate paid = paidOn != null ? paidOn : today;
        if (paid.isAfter(payDue)) {
            if (rule.latePaymentPercentMonth() != null) {
                long months = startedMonths(payDue, paid);
                payment = payment.add(percent(amount, rule.latePaymentPercentMonth().multiply(BigDecimal.valueOf(months))));
                how.add(Texts.t("Late payment: %s %% for each month started, %s month(s).", plain(rule.latePaymentPercentMonth()), months));
            }
            if (rule.latePaymentPercentDay() != null) {
                LocalDate from = rule.dailyFromDay() == null ? payDue.plusDays(1)
                        : payDue.withDayOfMonth(Math.min(rule.dailyFromDay(), payDue.lengthOfMonth()));
                long days = paid.isBefore(from) ? 0 : ChronoUnit.DAYS.between(from, paid) + 1;
                if (days > 0) {
                    payment = payment.add(percent(amount, rule.latePaymentPercentDay().multiply(BigDecimal.valueOf(days))));
                    how.add(Texts.t("Late payment: %s %% a day, %s day(s).", plain(rule.latePaymentPercentDay()), days));
                }
            }
        }
        return new Penalty(declaration, payment, declaration.add(payment), how);
    }

    /** Months between two days, a month started counting as a whole one. */
    static long startedMonths(LocalDate due, LocalDate on) {
        if (!on.isAfter(due)) {
            return 0;
        }
        long months = ChronoUnit.MONTHS.between(due, on);
        return due.plusMonths(months).isBefore(on) ? months + 1 : months;
    }

    private static BigDecimal percent(BigDecimal amount, BigDecimal pct) {
        return amount.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal b) {
        return b.stripTrailingZeros().toPlainString();
    }

    /** The year-end duties that come with the December declaration. */
    private static String annual(String country, YearMonth month, String kind) {
        if (month.getMonthValue() != 12 || !kind.equals("IPR")) {
            return null;
        }
        return country.equals("CG")
                ? Texts.t("Year end: the annual statement of salaries (DAS) follows in January.")
                : Texts.t("December: add the annual summary, a sheet for each employee and the nominal list (Loi 004/2003 art. 18).");
    }

    // ---- paid ----------------------------------------------------------------------------------------------

    @Transactional
    public Item pay(YearMonth month, String kind, LocalDate paidOn, BigDecimal amount, String reference, String mode) {
        Recorded rec = recorded(month, kind);
        if (rec == null) {
            throw new BusinessRuleException("Record the declaration as filed before its payment.");
        }
        if (rec.paidOn() != null) {
            throw new cd.hrpay.common.ConflictException("The payment of this declaration is already recorded.");
        }
        if (paidOn == null || paidOn.isAfter(LocalDate.now(clock))) {
            throw new BusinessRuleException("The payment date cannot be in the future.");
        }
        if (paidOn.isBefore(month.atDay(1))) {
            throw new BusinessRuleException("The payment date cannot be before the month declared.");
        }
        if (amount == null || amount.signum() < 0) {
            throw new BusinessRuleException("Enter the amount paid.");
        }
        if (mode != null && !MODES.contains(mode)) {
            throw new BusinessRuleException("Unknown payment method.");
        }
        jdbc.sql("""
                update declaration set paid_on = ?, paid_amount = ?, paid_reference = ?, paid_mode = ?, paid_by = ?, paid_at = now()
                where month = ? and kind = ?""")
                .params(paidOn, amount.setScale(2, RoundingMode.HALF_UP), blank(reference), mode, CurrentUser.email(),
                        month.atDay(1), kind).update();
        audit.record("declaration.paid", "declaration", month + " " + kind, Map.of("paidOn", paidOn.toString(),
                "amount", amount, "reference", reference == null ? "" : reference.trim()));
        return item(month, kind);
    }

    @Transactional
    public Item unpay(YearMonth month, String kind) {
        Recorded rec = recorded(month, kind);
        if (rec == null || rec.paidOn() == null) {
            throw new NotFoundException("The payment of this declaration is not recorded.");
        }
        jdbc.sql("""
                update declaration set paid_on = null, paid_amount = null, paid_reference = null, paid_mode = null,
                       paid_by = null, paid_at = null where month = ? and kind = ?""").params(month.atDay(1), kind).update();
        audit.record("declaration.unpaid", "declaration", month + " " + kind, Map.of());
        return item(month, kind);
    }

    public Item item(YearMonth month, String kind) {
        return items(month, month).stream().filter(i -> i.kind().equals(kind)).findFirst()
                .orElseThrow(() -> new NotFoundException("Unknown declaration " + kind + "."));
    }

    private Recorded recorded(YearMonth month, String kind) {
        return jdbc.sql("""
                select month, kind, total_cdf, filed_on, reference, note, filed_by, filed_at, paid_on, paid_amount,
                       paid_reference, paid_mode, paid_by, paid_at
                from declaration where month = ? and kind = ?""").params(month.atDay(1), kind)
                .query(Recorded.class).optional().orElse(null);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
