package cd.hrpay.payroll.report;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.payroll.ExchangeRates;
import cd.hrpay.payroll.PayrollRuns;
import cd.hrpay.payroll.report.Report.Column;
import cd.hrpay.payroll.report.Report.Row;
import cd.hrpay.payroll.report.Report.Table;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.security.DataCrypto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The payroll general reports (états généraux de la paie) of one month: the summary, totals by any grouping (grade
 * category, grade, site, unit, payment method...), by pay element, a cross table, the payment methods, the
 * differences with the previous month, the quinzaine, and the livre de paie (DRC arrêté 12/CAB.MIN/ETPS/042 du 8 août
 * 2008, 33 mentions). Every figure comes from the stored payslips, so a report always matches what was paid.
 */
@Service
public class PayrollReports {

    /** Narrows a report: a branch of the structure, a site, a grade category; and the currency amounts are shown in. */
    public record Filters(Long unitId, Long siteId, Long categoryId, boolean complementary, String currency) {

        public Filters {
            currency = currency == null || currency.isBlank() ? "PAID" : currency;
            if (!List.of("PAID", "LOCAL", "USD").contains(currency)) {
                throw new BusinessRuleException("Choose the currency: PAID, LOCAL or USD.");
            }
        }
    }

    record Line(long payslipId, String code, String label, String kind, BigDecimal base, BigDecimal rate, BigDecimal amount,
                int displayOrder) {}

    record Slip(long id, long userId, String firstName, String lastName, String registrationNo, String number, String kind,
                String currency, BigDecimal gross, BigDecimal cnssBase, BigDecimal taxableBase, BigDecimal deductions,
                BigDecimal net, BigDecimal charges, int scheduledMinutes, int paidMinutes, int daysWorked, Long unitId,
                Long siteId, String siteName, Long categoryId, String categoryCode, String categoryName, Long gradeId,
                String gradeCode, String gradeName, String snapshotJson, String correctsNumber, String reason, String postnom) {}

    /** A payslip with its lines and readable snapshot. */
    record P(Slip s, Map<String, Object> snap, List<Line> lines) {

        BigDecimal sum(java.util.function.Predicate<Line> which) {
            return lines.stream().filter(which).map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        BigDecimal code(String... codes) {
            Set<String> c = Set.of(codes);
            return sum(l -> c.contains(l.code()));
        }

        String text(String key) {
            Object v = snap.get(key);
            return v == null ? null : v.toString();
        }

        String name() {
            return fullName();
        }

        /** Last name and postnom in capitals, then first names (mention 2 of the livre de paie). */
        String fullName() {
            return s.lastName().toUpperCase() + (s.postnom() == null || s.postnom().isBlank() ? "" : " " + s.postnom().toUpperCase())
                    + " " + s.firstName();
        }
    }

    private record UnitRow(long id, String name, Long parentId, int rank, String levelName) {}

    /** What one report reads: the run, its payslips after the filters, and the structure. */
    record Ctx(PayrollRuns.Run run, String local, String target, List<P> slips, Map<Long, UnitRow> units, Filters filters) {

        boolean provisional() {
            return !run.status().equals("CLOSED");
        }

        /** An amount in the report's currency: as paid, or converted at the month's rate. */
        BigDecimal amount(P p, BigDecimal v) {
            return target == null ? v : ExchangeRates.convert(v, p.s().currency(), target, run.usdCdf());
        }

        String currencyOf(P p) {
            return target == null ? p.s().currency() : target;
        }
    }

    /** A group a payslip falls in: a key for drilling down, and a label. */
    record Group(String key, String label) {}

    public static final List<String> DIMENSIONS = List.of("CATEGORY", "GRADE", "SITE", "UNIT", "COST_CENTRE", "OCCUPATION",
            "CONTRACT_TYPE", "NATIONALITY", "CURRENCY", "PAYMENT_METHOD");

    private static final Set<String> BASIC = Set.of("BASIC", "COMPLEMENT");
    private static final Set<String> FAMILY = Set.of("FAMILY_ALLOWANCE");
    private static final Set<String> ADVANCES = Set.of("QUINZAINE", "ADVANCE", "LEAVE_ADVANCE", "EARNED_ADVANCE");
    private static final Set<String> COMPENSATION = Set.of("NOTICE_OWED");

    private final JdbcClient jdbc;
    private final PayrollRuns runs;
    private final DataCrypto crypto;
    private final JsonMapper json = JsonMapper.builder().build();

    public PayrollReports(JdbcClient jdbc, PayrollRuns runs, DataCrypto crypto) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.crypto = crypto;
    }

    // ---- loading -----------------------------------------------------------------------------------------------

    private String localCurrency() {
        return jdbc.sql("select local_currency from company where id = current_company_id()").query(String.class).single();
    }

    Ctx context(YearMonth month, Filters f) {
        PayrollRuns.Run run = runs.run(month).orElseThrow(() -> new NotFoundException("No payroll for %s yet.".formatted(month)));
        return context(run, f);
    }

    @SuppressWarnings("unchecked")
    Ctx context(PayrollRuns.Run run, Filters f) {
        String local = localCurrency();
        Map<Long, UnitRow> units = units();
        Set<Long> branch = f.unitId() == null ? null : branch(units, f.unitId());
        List<Slip> rows = jdbc.sql("""
                select p.id, p.user_id, u.first_name, u.last_name, u.registration_no, p.number, p.kind, trim(p.currency) as currency,
                       p.gross, p.cnss_base, p.taxable_base, p.employee_deductions as deductions, p.net,
                       p.employer_charges as charges, p.scheduled_minutes, p.paid_minutes, p.days_worked, p.unit_id,
                       p.site_id, s.name as site_name, p.category_id, c.code as category_code, c.name as category_name,
                       p.grade_id, g.code as grade_code, g.name as grade_name, p.snapshot::text as snapshot_json,
                       (select o.number from payslip o where o.id = p.corrects_id) as corrects_number, p.reason, u.middle_name as postnom
                from payslip p join app_user u on u.id = p.user_id
                left join site s on s.id = p.site_id
                left join grade_category c on c.id = p.category_id
                left join grade g on g.id = p.grade_id
                where p.run_id = ? and (p.kind = 'REGULAR' or (? and p.number is not null))
                order by u.last_name, u.first_name, p.id""").params(run.id(), f.complementary()).query(Slip.class).list();
        Map<Long, List<Line>> lines = new HashMap<>();
        jdbc.sql("""
                select l.payslip_id, l.element_code as code, l.label, l.kind, l.base, l.rate, l.amount, l.display_order
                from payslip_line l join payslip p on p.id = l.payslip_id where p.run_id = ? order by l.display_order, l.id""")
                .param(run.id()).query(Line.class).list()
                .forEach(l -> lines.computeIfAbsent(l.payslipId(), k -> new ArrayList<>()).add(l));
        List<P> slips = new ArrayList<>();
        for (Slip s : rows) {
            if (branch != null && (s.unitId() == null || !branch.contains(s.unitId()))) {
                continue;
            }
            if (f.siteId() != null && !f.siteId().equals(s.siteId())) {
                continue;
            }
            if (f.categoryId() != null && !f.categoryId().equals(s.categoryId())) {
                continue;
            }
            slips.add(new P(s, json.readValue(s.snapshotJson(), Map.class), lines.getOrDefault(s.id(), List.of())));
        }
        String target = switch (f.currency()) {
            case "LOCAL" -> local;
            case "USD" -> "USD";
            default -> null;
        };
        return new Ctx(run, local, target, slips, units, f);
    }

    private Map<Long, UnitRow> units() {
        Map<Long, UnitRow> out = new LinkedHashMap<>();
        jdbc.sql("""
                select u.id, u.name, u.parent_id, l.rank, l.name as level_name
                from org_unit u join org_level l on l.id = u.level_id order by l.rank, u.name""")
                .query(UnitRow.class).list().forEach(u -> out.put(u.id(), u));
        return out;
    }

    private static Set<Long> branch(Map<Long, UnitRow> units, long top) {
        Set<Long> out = new HashSet<>();
        out.add(top);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (UnitRow u : units.values()) {
                if (u.parentId() != null && out.contains(u.parentId()) && out.add(u.id())) {
                    grew = true;
                }
            }
        }
        return out;
    }

    /** The unit, then its parents up to the top. */
    private static List<UnitRow> chain(Map<Long, UnitRow> units, Long unitId) {
        List<UnitRow> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (UnitRow u = unitId == null ? null : units.get(unitId); u != null && seen.add(u.id());
             u = u.parentId() == null ? null : units.get(u.parentId())) {
            out.add(u);
        }
        return out;
    }

    // ---- groups ------------------------------------------------------------------------------------------------

    static String dimensionLabel(String dim) {
        return switch (dim) {
            case "CATEGORY" -> Texts.t("Grade category");
            case "GRADE" -> Texts.t("Grade");
            case "SITE" -> Texts.t("Site");
            case "UNIT" -> Texts.t("Unit");
            case "COST_CENTRE" -> Texts.t("Cost centre");
            case "OCCUPATION" -> Texts.t("Occupation");
            case "CONTRACT_TYPE" -> Texts.t("Contract type");
            case "NATIONALITY" -> Texts.t("Nationals and expatriates");
            case "CURRENCY" -> Texts.t("Pay currency");
            case "PAYMENT_METHOD" -> Texts.t("Payment method");
            default -> throw new BusinessRuleException("Unknown grouping " + dim + ".");
        };
    }

    static String method(String m) {
        return switch (m == null ? "" : m) {
            case "BANK" -> Texts.t("Bank transfer");
            case "MOBILE_MONEY" -> Texts.t("Mobile money");
            case "CASH" -> Texts.t("Cash");
            default -> Texts.t("Not set");
        };
    }

    static String contractType(String c) {
        return switch (c == null ? "" : c) {
            case "PERMANENT" -> Texts.t("Permanent");
            case "FIXED_TERM" -> Texts.t("Fixed term");
            case "INTERN" -> Texts.t("Intern");
            case "CONSULTANT" -> Texts.t("Consultant");
            case "DAILY" -> Texts.t("Day worker");
            default -> Texts.t("Not set");
        };
    }

    /** The group a payslip falls in. For UNIT, level is the rank of the level to group at (0: the person's own unit). */
    Function<P, Group> grouping(Ctx ctx, String dim, int level) {
        String none = Texts.t("Not set");
        return switch (dim) {
            case "CATEGORY" -> p -> p.s().categoryId() == null ? new Group("", none)
                    : new Group(p.s().categoryId().toString(), p.s().categoryCode() + " · " + p.s().categoryName());
            case "GRADE" -> p -> p.s().gradeId() == null
                    ? new Group("", Objects.requireNonNullElse(p.text("grade"), none))
                    : new Group(p.s().gradeId().toString(), p.s().gradeCode() + " · " + p.s().gradeName());
            case "SITE" -> p -> p.s().siteId() == null ? new Group("", none) : new Group(p.s().siteId().toString(), p.s().siteName());
            case "UNIT" -> p -> {
                List<UnitRow> chain = chain(ctx.units(), p.s().unitId());
                if (chain.isEmpty()) {
                    return new Group("", Texts.t("No unit"));
                }
                UnitRow at = level <= 0 ? chain.getFirst()
                        : chain.stream().filter(u -> u.rank() <= level).findFirst().orElse(chain.getLast());
                return new Group(String.valueOf(at.id()), at.name());
            };
            case "COST_CENTRE" -> p -> text(p, "costCentre", none);
            case "OCCUPATION" -> p -> text(p, "occupation", none);
            case "CONTRACT_TYPE" -> p -> new Group(Objects.requireNonNullElse(p.text("contractType"), ""), contractType(p.text("contractType")));
            case "NATIONALITY" -> p -> Boolean.TRUE.equals(p.snap().get("expatriate"))
                    ? new Group("EXPATRIATE", Texts.t("Expatriates")) : new Group("NATIONAL", Texts.t("Nationals"));
            case "CURRENCY" -> p -> new Group(p.s().currency(), p.s().currency());
            case "PAYMENT_METHOD" -> p -> {
                String m = p.text("paymentMethod");
                String provider = "BANK".equals(m) ? p.text("bankName") : "MOBILE_MONEY".equals(m) ? p.text("mobileMoneyOperator") : null;
                String key = (m == null ? "" : m) + (provider == null ? "" : "|" + provider);
                return new Group(key, method(m) + (provider == null || provider.isBlank() ? "" : " · " + provider));
            };
            default -> throw new BusinessRuleException("Unknown grouping " + dim + ".");
        };
    }

    private static Group text(P p, String key, String none) {
        String v = p.text(key);
        return v == null || v.isBlank() ? new Group("", none) : new Group(v, v);
    }

    // ---- totals ------------------------------------------------------------------------------------------------

    /** The standard columns of a group: headcount, earnings, deductions, net, employer charges and cost. */
    static final class Acc {
        final Set<Long> people = new HashSet<>();
        BigDecimal basic = BigDecimal.ZERO, overtime = BigDecimal.ZERO, other = BigDecimal.ZERO, gross = BigDecimal.ZERO,
                cnss = BigDecimal.ZERO, ipr = BigDecimal.ZERO, otherDeductions = BigDecimal.ZERO, deductions = BigDecimal.ZERO,
                net = BigDecimal.ZERO, charges = BigDecimal.ZERO;

        void add(Ctx ctx, P p) {
            people.add(p.s().userId());
            BigDecimal b = ctx.amount(p, p.code("BASIC", "COMPLEMENT"));
            BigDecimal o = ctx.amount(p, p.code("OVERTIME"));
            BigDecimal g = ctx.amount(p, p.s().gross());
            BigDecimal c = ctx.amount(p, p.code("CNSS_EMPLOYEE"));
            BigDecimal i = ctx.amount(p, p.code("IPR"));
            BigDecimal d = ctx.amount(p, p.s().deductions());
            basic = basic.add(b);
            overtime = overtime.add(o);
            gross = gross.add(g);
            other = other.add(g.subtract(b).subtract(o));
            cnss = cnss.add(c);
            ipr = ipr.add(i);
            deductions = deductions.add(d);
            otherDeductions = otherDeductions.add(d.subtract(c).subtract(i));
            net = net.add(ctx.amount(p, p.s().net()));
            charges = charges.add(ctx.amount(p, p.s().charges()));
        }

        void add(Acc a) {
            people.addAll(a.people);
            basic = basic.add(a.basic);
            overtime = overtime.add(a.overtime);
            other = other.add(a.other);
            gross = gross.add(a.gross);
            cnss = cnss.add(a.cnss);
            ipr = ipr.add(a.ipr);
            otherDeductions = otherDeductions.add(a.otherDeductions);
            deductions = deductions.add(a.deductions);
            net = net.add(a.net);
            charges = charges.add(a.charges);
        }

        /** The same totals converted to another currency (one currency's totals into the local one). */
        Acc converted(String from, String to, BigDecimal usdCdf) {
            Acc x = new Acc();
            x.people.addAll(people);
            Function<BigDecimal, BigDecimal> c = v -> ExchangeRates.convert(v, from, to, usdCdf);
            x.basic = c.apply(basic);
            x.overtime = c.apply(overtime);
            x.other = c.apply(other);
            x.gross = c.apply(gross);
            x.cnss = c.apply(cnss);
            x.ipr = c.apply(ipr);
            x.otherDeductions = c.apply(otherDeductions);
            x.deductions = c.apply(deductions);
            x.net = c.apply(net);
            x.charges = c.apply(charges);
            return x;
        }

        List<Object> cells() {
            return List.of(people.size(), basic, overtime, other, gross, cnss, ipr, otherDeductions, deductions, net, charges,
                    gross.add(charges));
        }
    }

    static List<Column> accColumns() {
        return List.of(Column.number(Texts.t("Employees")), Column.money(Texts.t("Basic and complement")),
                Column.money(Texts.t("Overtime")), Column.money(Texts.t("Allowances and bonuses")), Column.money(Texts.t("Gross")),
                Column.money(Texts.t("CNSS (employee)")), Column.money(Texts.t("IPR")), Column.money(Texts.t("Other deductions")),
                Column.money(Texts.t("Total deductions")), Column.money(Texts.t("Net")), Column.money(Texts.t("Employer charges")),
                Column.money(Texts.t("Total cost")));
    }

    private static List<Object> concat(List<?> a, List<?> b) {
        List<Object> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static List<Object> cells(Object... v) {
        return new ArrayList<>(Arrays.asList(v));
    }

    // ---- report headings ---------------------------------------------------------------------------------------

    private String subtitle(Ctx ctx) {
        List<String> parts = new ArrayList<>();
        parts.add(Texts.t("Payroll of %s", Texts.month(ctx.run().month())));
        parts.add(Texts.t("%s payslips", ctx.slips().size()));
        if (ctx.filters().unitId() != null && ctx.units().containsKey(ctx.filters().unitId())) {
            parts.add(Texts.t("unit %s and below", ctx.units().get(ctx.filters().unitId()).name()));
        }
        if (ctx.filters().siteId() != null) {
            jdbc.sql("select name from site where id = ?").param(ctx.filters().siteId()).query(String.class).optional()
                    .ifPresent(s -> parts.add(Texts.t("site %s", s)));
        }
        if (ctx.filters().categoryId() != null) {
            jdbc.sql("select code from grade_category where id = ?").param(ctx.filters().categoryId()).query(String.class).optional()
                    .ifPresent(s -> parts.add(Texts.t("category %s", s)));
        }
        parts.add(ctx.target() == null ? Texts.t("amounts as paid") : Texts.t("amounts in %s at %s per USD", ctx.target(),
                ctx.run().usdCdf().stripTrailingZeros().toPlainString()));
        return String.join(" · ", parts);
    }

    private Report report(Ctx ctx, String code, String title, List<Table> tables, List<String> notes) {
        List<String> all = new ArrayList<>();
        if (ctx.provisional()) {
            all.add(Texts.t("PROVISIONAL: the payroll of this month is not closed; figures can still change."));
        }
        all.addAll(notes);
        return new Report(code, title, subtitle(ctx), ctx.provisional(), tables, all);
    }

    /** Total rows: one per currency, and when amounts are as paid in several currencies, their total in the local one. */
    private List<Row> totalRows(Ctx ctx, Map<String, Acc> byCurrency, int blanks) {
        List<Row> out = new ArrayList<>();
        List<Object> pad = new ArrayList<>();
        for (int i = 0; i < blanks; i++) {
            pad.add("");
        }
        for (var e : byCurrency.entrySet()) {
            List<Object> c = cells(Texts.t("Total"));
            c.addAll(pad);
            if (ctx.target() == null) {
                c.add(e.getKey());
            }
            out.add(new Row(concat(c, e.getValue().cells()), "TOTAL", 0, null));
        }
        if (ctx.target() == null && byCurrency.size() > 1) {
            Acc all = new Acc();
            byCurrency.forEach((cur, a) -> all.add(a.converted(cur, ctx.local(), ctx.run().usdCdf())));
            List<Object> c = cells(Texts.t("Total in %s", ctx.local()));
            c.addAll(pad);
            c.add(ctx.local());
            out.add(new Row(concat(c, all.cells()), "TOTAL", 0, null));
        }
        return out;
    }

    // ---- 1. summary --------------------------------------------------------------------------------------------

    public Report summary(YearMonth month, Filters f) {
        Ctx ctx = context(month, f);
        List<String> currencies = ctx.slips().stream().map(ctx::currencyOf).distinct().sorted().toList();
        boolean addLocal = ctx.target() == null && currencies.size() > 1;
        List<Column> cols = new ArrayList<>();
        cols.add(Column.text(Texts.t("Item")));
        currencies.forEach(c -> cols.add(Column.money(c)));
        if (addLocal) {
            cols.add(Column.money(Texts.t("Total in %s", ctx.local())));
        }
        record Item(String label, Function<P, BigDecimal> value, boolean count) {}
        List<Item> items = List.of(
                new Item(Texts.t("Employees"), p -> BigDecimal.ONE, true),
                new Item(Texts.t("Gross"), p -> p.s().gross(), false),
                new Item(Texts.t("CNSS base"), p -> p.s().cnssBase(), false),
                new Item(Texts.t("Taxable base"), p -> p.s().taxableBase(), false),
                new Item(Texts.t("Employee deductions"), p -> p.s().deductions(), false),
                new Item(Texts.t("Net"), p -> p.s().net(), false),
                new Item(Texts.t("Employer charges"), p -> p.s().charges(), false),
                new Item(Texts.t("Total cost"), p -> p.s().gross().add(p.s().charges()), false));
        List<Row> rows = new ArrayList<>();
        for (Item it : items) {
            List<Object> c = cells(it.label());
            BigDecimal localTotal = BigDecimal.ZERO;
            for (String cur : currencies) {
                List<P> in = ctx.slips().stream().filter(p -> ctx.currencyOf(p).equals(cur)).toList();
                if (it.count()) {
                    c.add(in.stream().map(p -> p.s().userId()).distinct().count());
                    continue;
                }
                BigDecimal v = in.stream().map(p -> ctx.amount(p, it.value().apply(p))).reduce(BigDecimal.ZERO, BigDecimal::add);
                c.add(v);
                localTotal = localTotal.add(ExchangeRates.convert(v, cur, ctx.local(), ctx.run().usdCdf()));
            }
            if (addLocal) {
                c.add(it.count() ? (Object) ctx.slips().stream().map(p -> p.s().userId()).distinct().count() : localTotal);
            }
            rows.add(new Row(c, it.count() ? "COUNT" : it.label().equals(Texts.t("Net")) ? "SUBTOTAL" : null, 0, null));
        }
        long complementary = ctx.slips().stream().filter(p -> p.s().kind().equals("COMPLEMENTARY")).count();
        List<String> notes = new ArrayList<>();
        if (complementary > 0) {
            notes.add(Texts.t("Includes %s complementary payslips (corrections) of this month.", complementary));
        }
        List<Table> tables = new ArrayList<>();
        tables.add(new Table(Texts.t("The month at a glance"), cols, rows));
        tables.addAll(elementTables(ctx));
        Table leave = leaveAllowanceSummary(ctx);
        if (leave != null) {
            tables.add(leave);
        }
        return report(ctx, "summary", Texts.t("General payroll summary"), tables, notes);
    }

    private Table leaveAllowanceSummary(Ctx ctx) {
        record L(String currency, int count, BigDecimal gross, BigDecimal deductions, BigDecimal net) {}
        YearMonth m = YearMonth.from(ctx.run().month());
        List<L> rows = jdbc.sql("""
                select trim(a.currency) as currency, count(*) as count, sum(a.gross) as gross,
                       sum(coalesce(a.employee_deductions, 0)) as deductions, sum(coalesce(a.net, a.gross)) as net
                from leave_allowance a where a.status in ('APPROVED', 'PAID') and a.pay_by between ? and ?
                group by a.currency order by a.currency""").params(m.atDay(1), m.atEndOfMonth()).query(L.class).list();
        if (rows.isEmpty() || ctx.filters().unitId() != null || ctx.filters().siteId() != null || ctx.filters().categoryId() != null) {
            return null;
        }
        return new Table(Texts.t("Leave allowances paid this month (their own payslips)"),
                List.of(Column.text(Texts.t("Currency")), Column.number(Texts.t("Allowances")), Column.money(Texts.t("Gross")),
                        Column.money(Texts.t("Employee deductions")), Column.money(Texts.t("Net"))),
                rows.stream().map(l -> Row.of(cells(l.currency(), l.count(), l.gross(), l.deductions(), l.net()))).toList());
    }

    // ---- 2. by grouping ----------------------------------------------------------------------------------------

    /** Totals by one grouping, optionally split by a second one, with subtotals. */
    public Report byDimension(YearMonth month, Filters f, String dim, String then, int level, int thenLevel) {
        Ctx ctx = context(month, f);
        return report(ctx, "by", Texts.t("Payroll by %s", dimensionLabel(dim).toLowerCase(java.util.Locale.ROOT)),
                List.of(dimensionTable(ctx, dim, then, level, thenLevel)), List.of());
    }

    Table dimensionTable(Ctx ctx, String dim, String then, int level, int thenLevel) {
        if (then != null && then.isBlank()) {
            then = null;
        }
        Function<P, Group> g1 = grouping(ctx, dim, level);
        Function<P, Group> g2 = then == null ? null : grouping(ctx, then, thenLevel);
        // group label -> (subgroup label -> currency -> totals)
        record K(String label, String key) {}
        Map<K, Map<K, Map<String, Acc>>> tree = new TreeMap<>(Comparator.comparing(K::label, String.CASE_INSENSITIVE_ORDER).thenComparing(K::key));
        Map<String, Acc> totals = new TreeMap<>();
        for (P p : ctx.slips()) {
            Group a = g1.apply(p);
            Group b = g2 == null ? new Group("", "") : g2.apply(p);
            String cur = ctx.currencyOf(p);
            tree.computeIfAbsent(new K(a.label(), a.key()), k -> new TreeMap<>(Comparator.comparing(K::label, String.CASE_INSENSITIVE_ORDER).thenComparing(K::key)))
                    .computeIfAbsent(new K(b.label(), b.key()), k -> new TreeMap<>())
                    .computeIfAbsent(cur, k -> new Acc()).add(ctx, p);
            totals.computeIfAbsent(cur, k -> new Acc()).add(ctx, p);
        }
        List<Column> cols = new ArrayList<>();
        cols.add(Column.text(dimensionLabel(dim)));
        if (then != null) {
            cols.add(Column.text(dimensionLabel(then)));
        }
        if (ctx.target() == null) {
            cols.add(Column.text(Texts.t("Currency")));
        }
        cols.addAll(accColumns());
        List<Row> rows = new ArrayList<>();
        for (var e1 : tree.entrySet()) {
            Map<String, Acc> sub = new TreeMap<>();
            for (var e2 : e1.getValue().entrySet()) {
                for (var e3 : e2.getValue().entrySet()) {
                    sub.computeIfAbsent(e3.getKey(), k -> new Acc()).add(e3.getValue());
                    if (then == null) {
                        continue;
                    }
                    List<Object> c = cells("", e2.getKey().label());
                    if (ctx.target() == null) {
                        c.add(e3.getKey());
                    }
                    rows.add(new Row(concat(c, e3.getValue().cells()), null, 1, drill(dim, e1.getKey().key(), level, then,
                            e2.getKey().key(), thenLevel)));
                }
            }
            for (var s : sub.entrySet()) {
                List<Object> c = cells(e1.getKey().label());
                if (then != null) {
                    c.add(Texts.t("Subtotal"));
                }
                if (ctx.target() == null) {
                    c.add(s.getKey());
                }
                rows.add(new Row(concat(c, s.getValue().cells()), then == null ? null : "SUBTOTAL", 0,
                        drill(dim, e1.getKey().key(), level, null, null, 0)));
            }
        }
        rows.addAll(totalRows(ctx, totals, then == null ? 0 : 1));
        return new Table(Texts.t("By %s", dimensionLabel(dim).toLowerCase(java.util.Locale.ROOT))
                + (then == null ? "" : Texts.t(" then by %s", dimensionLabel(then).toLowerCase(java.util.Locale.ROOT))), cols, rows);
    }

    private static Map<String, String> drill(String dim, String key, int level, String then, String thenKey, int thenLevel) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("dim", dim);
        d.put("key", key);
        if (level > 0) {
            d.put("level", String.valueOf(level));
        }
        if (then != null) {
            d.put("then", then);
            d.put("thenKey", thenKey);
            if (thenLevel > 0) {
                d.put("thenLevel", String.valueOf(thenLevel));
            }
        }
        return d;
    }

    /** The structure as a tree: each unit with the totals of its whole branch. */
    public Report unitTree(YearMonth month, Filters f) {
        Ctx ctx = context(month, f);
        return report(ctx, "tree", Texts.t("Payroll by organisational unit"), List.of(treeTable(ctx)), List.of());
    }

    Table treeTable(Ctx ctx) {
        Map<Long, Map<String, Acc>> branch = new HashMap<>();
        Map<String, Acc> noUnit = new TreeMap<>();
        Map<String, Acc> totals = new TreeMap<>();
        for (P p : ctx.slips()) {
            String cur = ctx.currencyOf(p);
            List<UnitRow> chain = chain(ctx.units(), p.s().unitId());
            if (chain.isEmpty()) {
                noUnit.computeIfAbsent(cur, k -> new Acc()).add(ctx, p);
            }
            for (UnitRow u : chain) {
                branch.computeIfAbsent(u.id(), k -> new TreeMap<>()).computeIfAbsent(cur, k -> new Acc()).add(ctx, p);
            }
            totals.computeIfAbsent(cur, k -> new Acc()).add(ctx, p);
        }
        Map<Long, List<UnitRow>> children = new HashMap<>();
        List<UnitRow> roots = new ArrayList<>();
        for (UnitRow u : ctx.units().values()) {
            if (u.parentId() == null || !ctx.units().containsKey(u.parentId())) {
                roots.add(u);
            } else {
                children.computeIfAbsent(u.parentId(), k -> new ArrayList<>()).add(u);
            }
        }
        List<Column> cols = new ArrayList<>(List.of(Column.text(Texts.t("Unit")), Column.text(Texts.t("Level"))));
        if (ctx.target() == null) {
            cols.add(Column.text(Texts.t("Currency")));
        }
        cols.addAll(accColumns());
        List<Row> rows = new ArrayList<>();
        java.util.ArrayDeque<Object[]> stack = new java.util.ArrayDeque<>();
        for (int i = roots.size() - 1; i >= 0; i--) {
            stack.push(new Object[] {roots.get(i), 0});
        }
        while (!stack.isEmpty()) {
            Object[] top = stack.pop();
            UnitRow u = (UnitRow) top[0];
            int depth = (Integer) top[1];
            Map<String, Acc> accs = branch.get(u.id());
            if (accs == null) {
                continue;
            }
            boolean leaf = children.getOrDefault(u.id(), List.of()).stream().noneMatch(c -> branch.containsKey(c.id()));
            for (var e : accs.entrySet()) {
                List<Object> c = cells(u.name(), u.levelName());
                if (ctx.target() == null) {
                    c.add(e.getKey());
                }
                rows.add(new Row(concat(c, e.getValue().cells()), leaf ? null : "SUBTOTAL", depth,
                        Map.of("dim", "UNIT_BRANCH", "key", String.valueOf(u.id()))));
            }
            List<UnitRow> kids = children.getOrDefault(u.id(), List.of());
            for (int i = kids.size() - 1; i >= 0; i--) {
                stack.push(new Object[] {kids.get(i), depth + 1});
            }
        }
        for (var e : noUnit.entrySet()) {
            List<Object> c = cells(Texts.t("No unit"), "");
            if (ctx.target() == null) {
                c.add(e.getKey());
            }
            rows.add(new Row(concat(c, e.getValue().cells()), null, 0, Map.of("dim", "UNIT", "key", "")));
        }
        rows.addAll(totalRows(ctx, totals, 1));
        return new Table(Texts.t("By organisational unit (each unit with its whole branch)"), cols, rows);
    }

    // ---- 3. by pay element -------------------------------------------------------------------------------------

    public Report byElement(YearMonth month, Filters f) {
        Ctx ctx = context(month, f);
        return report(ctx, "elements", Texts.t("Payroll by pay element"), elementTables(ctx), List.of());
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "EARNING" -> Texts.t("Earnings");
            case "DEDUCTION" -> Texts.t("Employee deductions");
            default -> Texts.t("Employer charges");
        };
    }

    List<Table> elementTables(Ctx ctx) {
        record E(String code, String label, String kind, int order) {}
        record K(String code, String currency) {}
        Map<K, BigDecimal[]> sums = new HashMap<>();   // amount, base
        Map<K, Set<Long>> people = new HashMap<>();
        Map<String, E> elements = new LinkedHashMap<>();
        for (P p : ctx.slips()) {
            for (Line l : p.lines()) {
                String code = l.code();
                // Loan repayments are labelled with the loan number: one row for all.
                elements.putIfAbsent(code, new E(code, code.equals("LOAN") || code.equals("ADVANCE") ? l.label().replaceAll("\\s*#\\d+$", "") : l.label(),
                        l.kind(), l.displayOrder()));
                K k = new K(code, ctx.currencyOf(p));
                BigDecimal[] s = sums.computeIfAbsent(k, x -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                s[0] = s[0].add(ctx.amount(p, l.amount()));
                if (l.base() != null && l.rate() != null) {
                    s[1] = s[1].add(ctx.amount(p, l.base()));
                }
                people.computeIfAbsent(k, x -> new HashSet<>()).add(p.s().userId());
            }
        }
        List<Table> out = new ArrayList<>();
        for (String kind : List.of("EARNING", "DEDUCTION", "EMPLOYER_CHARGE")) {
            List<E> list = elements.values().stream().filter(e -> e.kind().equals(kind))
                    .sorted(Comparator.comparingInt(E::order).thenComparing(E::code)).toList();
            if (list.isEmpty()) {
                continue;
            }
            List<Column> cols = new ArrayList<>(List.of(Column.text(Texts.t("Code")), Column.text(Texts.t("Element"))));
            if (ctx.target() == null) {
                cols.add(Column.text(Texts.t("Currency")));
            }
            cols.addAll(List.of(Column.number(Texts.t("Employees")), Column.money(Texts.t("Base")), Column.money(Texts.t("Amount"))));
            List<Row> rows = new ArrayList<>();
            Map<String, BigDecimal> total = new TreeMap<>();
            for (E e : list) {
                for (String cur : sums.keySet().stream().filter(k -> k.code().equals(e.code())).map(K::currency).sorted().toList()) {
                    K k = new K(e.code(), cur);
                    BigDecimal[] s = sums.get(k);
                    List<Object> c = cells(e.code(), e.label());
                    if (ctx.target() == null) {
                        c.add(cur);
                    }
                    c.addAll(List.of(people.get(k).size(), s[1].signum() == 0 ? "" : s[1], s[0]));
                    rows.add(new Row(c, null, 0, Map.of("element", e.code())));
                    total.merge(cur, s[0], BigDecimal::add);
                }
            }
            for (var t : total.entrySet()) {
                List<Object> c = cells(Texts.t("Total"), "");
                if (ctx.target() == null) {
                    c.add(t.getKey());
                }
                c.addAll(List.of("", "", t.getValue()));
                rows.add(new Row(c, "TOTAL", 0, null));
            }
            out.add(new Table(kindLabel(kind), cols, rows));
        }
        return out;
    }

    // ---- 4. cross table ----------------------------------------------------------------------------------------

    /** Groups down the side, pay elements across. */
    public Report cross(YearMonth month, Filters f, String dim, int level) {
        Ctx ctx = context(month, f);
        return report(ctx, "cross", Texts.t("Pay elements by %s", dimensionLabel(dim).toLowerCase(java.util.Locale.ROOT)),
                List.of(crossTable(ctx, dim, level)), List.of());
    }

    Table crossTable(Ctx ctx, String dim, int level) {
        Function<P, Group> g = grouping(ctx, dim, level);
        record E(String code, String label, String kind, int order) {}
        Map<String, E> elements = new HashMap<>();
        record K(String label, String key, String currency) {}
        Map<K, Map<String, BigDecimal>> table = new TreeMap<>(Comparator.comparing(K::label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(K::key).thenComparing(K::currency));
        Map<K, Set<Long>> people = new HashMap<>();
        Map<String, Map<String, BigDecimal>> totals = new TreeMap<>();
        for (P p : ctx.slips()) {
            Group grp = g.apply(p);
            K k = new K(grp.label(), grp.key(), ctx.currencyOf(p));
            Map<String, BigDecimal> row = table.computeIfAbsent(k, x -> new HashMap<>());
            Map<String, BigDecimal> tot = totals.computeIfAbsent(k.currency(), x -> new HashMap<>());
            people.computeIfAbsent(k, x -> new HashSet<>()).add(p.s().userId());
            for (Line l : p.lines()) {
                elements.putIfAbsent(l.code(), new E(l.code(), l.code().equals("LOAN") || l.code().equals("ADVANCE")
                        ? l.label().replaceAll("\\s*#\\d+$", "") : l.label(), l.kind(), l.displayOrder()));
                BigDecimal v = ctx.amount(p, l.amount());
                row.merge(l.code(), v, BigDecimal::add);
                tot.merge(l.code(), v, BigDecimal::add);
            }
            BigDecimal gross = ctx.amount(p, p.s().gross());
            BigDecimal net = ctx.amount(p, p.s().net());
            row.merge("=GROSS", gross, BigDecimal::add);
            row.merge("=NET", net, BigDecimal::add);
            tot.merge("=GROSS", gross, BigDecimal::add);
            tot.merge("=NET", net, BigDecimal::add);
        }
        Map<String, Integer> kindOrder = Map.of("EARNING", 0, "DEDUCTION", 1, "EMPLOYER_CHARGE", 2);
        List<E> order = elements.values().stream().sorted(Comparator.comparingInt((E e) -> kindOrder.get(e.kind()))
                .thenComparingInt(E::order).thenComparing(E::code)).toList();
        List<Column> cols = new ArrayList<>(List.of(Column.text(dimensionLabel(dim))));
        if (ctx.target() == null) {
            cols.add(Column.text(Texts.t("Currency")));
        }
        cols.add(Column.number(Texts.t("Employees")));
        List<String> codes = new ArrayList<>();
        for (E e : order) {
            if (e.kind().equals("DEDUCTION") && !codes.contains("=GROSS")) {
                codes.add("=GROSS");
                cols.add(Column.money(Texts.t("Gross")));
            }
            if (e.kind().equals("EMPLOYER_CHARGE") && !codes.contains("=NET")) {
                if (!codes.contains("=GROSS")) {
                    codes.add("=GROSS");
                    cols.add(Column.money(Texts.t("Gross")));
                }
                codes.add("=NET");
                cols.add(Column.money(Texts.t("Net")));
            }
            codes.add(e.code());
            cols.add(Column.money(e.label()));
        }
        if (!codes.contains("=GROSS")) {
            codes.add("=GROSS");
            cols.add(Column.money(Texts.t("Gross")));
        }
        if (!codes.contains("=NET")) {
            codes.add("=NET");
            cols.add(Column.money(Texts.t("Net")));
        }
        List<Row> rows = new ArrayList<>();
        for (var e : table.entrySet()) {
            List<Object> c = cells(e.getKey().label());
            if (ctx.target() == null) {
                c.add(e.getKey().currency());
            }
            c.add(people.get(e.getKey()).size());
            for (String code : codes) {
                c.add(e.getValue().getOrDefault(code, BigDecimal.ZERO));
            }
            rows.add(new Row(c, null, 0, drill(dim, e.getKey().key(), level, null, null, 0)));
        }
        for (var t : totals.entrySet()) {
            List<Object> c = cells(Texts.t("Total"));
            if (ctx.target() == null) {
                c.add(t.getKey());
            }
            c.add(ctx.slips().stream().filter(p -> ctx.currencyOf(p).equals(t.getKey())).map(p -> p.s().userId()).distinct().count());
            for (String code : codes) {
                c.add(t.getValue().getOrDefault(code, BigDecimal.ZERO));
            }
            rows.add(new Row(c, "TOTAL", 0, null));
        }
        return new Table(Texts.t("Pay elements by %s", dimensionLabel(dim).toLowerCase(java.util.Locale.ROOT)), cols, rows);
    }

    // ---- 5. payment methods ------------------------------------------------------------------------------------

    public Report paymentMethods(YearMonth month, Filters f) {
        Ctx ctx = context(month, f);
        List<Table> tables = new ArrayList<>();
        Function<P, Group> g = grouping(ctx, "PAYMENT_METHOD", 0);
        record K(String label, String key, String currency) {}
        Map<K, BigDecimal> net = new TreeMap<>(Comparator.comparing(K::label).thenComparing(K::currency));
        Map<K, Set<Long>> people = new HashMap<>();
        Map<String, BigDecimal> total = new TreeMap<>();
        for (P p : ctx.slips()) {
            Group grp = g.apply(p);
            K k = new K(grp.label(), grp.key(), ctx.currencyOf(p));
            BigDecimal v = ctx.amount(p, p.s().net());
            net.merge(k, v, BigDecimal::add);
            people.computeIfAbsent(k, x -> new HashSet<>()).add(p.s().userId());
            total.merge(k.currency(), v, BigDecimal::add);
        }
        List<Row> rows = new ArrayList<>();
        net.forEach((k, v) -> rows.add(new Row(cells(k.label(), k.currency(), people.get(k).size(), v), null, 0,
                Map.of("dim", "PAYMENT_METHOD", "key", k.key()))));
        total.forEach((cur, v) -> rows.add(new Row(cells(Texts.t("Total"), cur,
                ctx.slips().stream().filter(p -> ctx.currencyOf(p).equals(cur)).map(p -> p.s().userId()).distinct().count(), v),
                "TOTAL", 0, null)));
        tables.add(new Table(Texts.t("Net to pay by payment method"), List.of(Column.text(Texts.t("Payment method")),
                Column.text(Texts.t("Currency")), Column.number(Texts.t("Employees")), Column.money(Texts.t("Net"))), rows));
        if (CurrentUser.has("payslip.read.all")) {
            List<Row> people2 = new ArrayList<>();
            ctx.slips().stream().sorted(Comparator.comparing((P p) -> g.apply(p).label()).thenComparing(P::name)).forEach(p -> {
                String m = p.text("paymentMethod");
                String account = "BANK".equals(m) ? masked(p.text("bankAccount")) : "MOBILE_MONEY".equals(m) ? masked(p.text("mobileMoneyNumber")) : "";
                people2.add(Row.of(cells(g.apply(p).label(), p.s().registrationNo(), p.name(), account, ctx.currencyOf(p),
                        ctx.amount(p, p.s().net()))));
            });
            tables.add(new Table(Texts.t("Employees by payment method"), List.of(Column.text(Texts.t("Payment method")),
                    Column.text(Texts.t("Matricule")), Column.text(Texts.t("Employee")), Column.text(Texts.t("Account")),
                    Column.text(Texts.t("Currency")), Column.money(Texts.t("Net"))), people2));
        }
        return report(ctx, "payment", Texts.t("Payroll by payment method"), tables, List.of());
    }

    /** Only the last four characters of an account or phone number. */
    private String masked(String stored) {
        if (stored == null || stored.isBlank()) {
            return "";
        }
        String plain;
        try {
            plain = crypto.decrypt(stored);
        } catch (RuntimeException e) {
            plain = stored;
        }
        if (plain == null) {
            return "";
        }
        String s = plain.replaceAll("\\s", "");
        return s.length() <= 4 ? "••••" : "••••" + s.substring(s.length() - 4);
    }

    // ---- 6. differences with the previous month ----------------------------------------------------------------

    public Report variance(YearMonth month, Filters f, BigDecimal threshold) {
        requireNamed();
        Ctx ctx = context(month, f);
        BigDecimal limit = threshold != null ? threshold : new BigDecimal(jdbc.sql(
                "select coalesce((select value from payroll_setting where key = 'REPORT_VARIANCE_PERCENT'), '10')").query(String.class).single());
        YearMonth before = month.minusMonths(1);
        PayrollRuns.Run prevRun = runs.run(before).orElse(null);
        List<Table> tables = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (prevRun == null) {
            notes.add(Texts.t("No payroll for %s: nothing to compare with.", Texts.month(before)));
            return report(ctx, "variance", Texts.t("Differences with the previous month"), tables, notes);
        }
        Ctx prev = context(prevRun, new Filters(f.unitId(), f.siteId(), f.categoryId(), false, f.currency()));
        // Compared in one currency: the report's, or the local one when amounts are as paid.
        String cur = ctx.target() == null ? ctx.local() : ctx.target();
        Map<Long, P> now = regular(ctx);
        Map<Long, P> then = regular(prev);
        List<Column> person = List.of(Column.text(Texts.t("Matricule")), Column.text(Texts.t("Employee")));
        List<Row> arrivals = new ArrayList<>();
        List<Row> departures = new ArrayList<>();
        List<Row> changes = new ArrayList<>();
        for (P p : now.values()) {
            if (!then.containsKey(p.s().userId())) {
                arrivals.add(Row.of(cells(p.s().registrationNo(), p.name(), cur, conv(ctx, p, p.s().net(), cur))));
            }
        }
        for (P p : then.values()) {
            if (!now.containsKey(p.s().userId())) {
                departures.add(Row.of(cells(p.s().registrationNo(), p.name(), cur, conv(prev, p, p.s().net(), cur))));
            }
        }
        for (P p : now.values()) {
            P q = then.get(p.s().userId());
            if (q == null) {
                continue;
            }
            BigDecimal a = conv(prev, q, q.s().net(), cur);
            BigDecimal b = conv(ctx, p, p.s().net(), cur);
            BigDecimal diff = b.subtract(a);
            BigDecimal pct = a.signum() == 0 ? null : diff.multiply(BigDecimal.valueOf(100)).divide(a.abs(), 1, RoundingMode.HALF_UP);
            if (diff.signum() == 0 || (pct != null && pct.abs().compareTo(limit) <= 0)) {
                continue;
            }
            changes.add(Row.of(cells(p.s().registrationNo(), p.name(), cur, a, b, diff, pct == null ? "" : pct,
                    reasons(prev, q, ctx, p, cur))));
        }
        List<Column> money = List.of(Column.text(Texts.t("Currency")), Column.money(Texts.t("Net")));
        tables.add(new Table(Texts.t("Changes of more than %s%% in the net", limit.stripTrailingZeros().toPlainString()),
                concat(person, List.of(Column.text(Texts.t("Currency")), Column.money(Texts.t("Net, %s", Texts.month(before))),
                        Column.money(Texts.t("Net, %s", Texts.month(month))), Column.money(Texts.t("Difference")),
                        Column.number(Texts.t("Change %")), Column.text(Texts.t("Main changes")))).stream().map(Column.class::cast).toList(),
                changes));
        tables.add(new Table(Texts.t("Arrivals (no payslip in %s)", Texts.month(before)),
                concat(person, money).stream().map(Column.class::cast).toList(), arrivals));
        tables.add(new Table(Texts.t("Departures (no payslip in %s)", Texts.month(month)),
                concat(person, money).stream().map(Column.class::cast).toList(), departures));
        Acc a = new Acc();
        then.values().forEach(p -> a.add(prev, p));
        Acc b = new Acc();
        now.values().forEach(p -> b.add(ctx, p));
        notes.add(Texts.t("%s employees in %s, %s in %s.", a.people.size(), Texts.month(before), b.people.size(), Texts.month(month)));
        return report(ctx, "variance", Texts.t("Differences with the previous month"), tables, notes);
    }

    private static BigDecimal conv(Ctx ctx, P p, BigDecimal v, String cur) {
        return ExchangeRates.convert(v, p.s().currency(), cur, ctx.run().usdCdf());
    }

    private static Map<Long, P> regular(Ctx ctx) {
        Map<Long, P> out = new LinkedHashMap<>();
        ctx.slips().stream().filter(p -> p.s().kind().equals("REGULAR")).forEach(p -> out.put(p.s().userId(), p));
        return out;
    }

    /** The three pay elements that moved most between the two payslips. */
    private static String reasons(Ctx prev, P q, Ctx ctx, P p, String cur) {
        Map<String, BigDecimal> diff = new HashMap<>();
        Map<String, String> label = new HashMap<>();
        for (Line l : p.lines()) {
            BigDecimal v = conv(ctx, p, l.amount(), cur);
            diff.merge(l.code(), l.kind().equals("EARNING") ? v : l.kind().equals("DEDUCTION") ? v.negate() : BigDecimal.ZERO, BigDecimal::add);
            label.putIfAbsent(l.code(), l.label().replaceAll("\\s*#\\d+$", ""));
        }
        for (Line l : q.lines()) {
            BigDecimal v = conv(prev, q, l.amount(), cur);
            diff.merge(l.code(), l.kind().equals("EARNING") ? v.negate() : l.kind().equals("DEDUCTION") ? v : BigDecimal.ZERO, BigDecimal::add);
            label.putIfAbsent(l.code(), l.label().replaceAll("\\s*#\\d+$", ""));
        }
        return diff.entrySet().stream().filter(e -> e.getValue().signum() != 0)
                .sorted(Comparator.comparing((Map.Entry<String, BigDecimal> e) -> e.getValue().abs()).reversed()).limit(3)
                .map(e -> label.get(e.getKey()) + " " + (e.getValue().signum() > 0 ? "+" : "") + e.getValue().toPlainString())
                .reduce((x, y) -> x + "; " + y).orElse("");
    }

    // ---- 7. employees of a group -------------------------------------------------------------------------------

    private static void requireNamed() {
        if (!CurrentUser.has("payslip.read.all")) {
            throw new AccessDeniedException("Lists of employees' pay need the right to read all payslips.");
        }
    }

    /** The employees behind one row of a report: a group, a unit's branch, or a pay element. */
    public Report employees(YearMonth month, Filters f, Map<String, String> drill) {
        requireNamed();
        Ctx ctx = context(month, f);
        java.util.function.Predicate<P> keep;
        String what;
        String element = drill.get("element");
        if (element != null) {
            keep = p -> p.lines().stream().anyMatch(l -> l.code().equals(element));
            what = element;
        } else if ("UNIT_BRANCH".equals(drill.get("dim"))) {
            long unit = Long.parseLong(drill.get("key"));
            Set<Long> branch = branch(ctx.units(), unit);
            keep = p -> p.s().unitId() != null && branch.contains(p.s().unitId());
            what = ctx.units().containsKey(unit) ? ctx.units().get(unit).name() : "";
        } else {
            String dim = drill.get("dim");
            if (dim == null || !DIMENSIONS.contains(dim) || (drill.get("then") != null && !DIMENSIONS.contains(drill.get("then")))) {
                throw new BusinessRuleException("Unknown grouping.");
            }
            Function<P, Group> g1 = grouping(ctx, dim, level(drill.get("level")));
            String key = Objects.requireNonNullElse(drill.get("key"), "");
            String then = drill.get("then");
            Function<P, Group> g2 = then == null ? null : grouping(ctx, then, level(drill.get("thenLevel")));
            String thenKey = Objects.requireNonNullElse(drill.get("thenKey"), "");
            keep = p -> g1.apply(p).key().equals(key) && (g2 == null || g2.apply(p).key().equals(thenKey));
            what = ctx.slips().stream().filter(keep).findFirst().map(p -> g1.apply(p).label()
                    + (g2 == null ? "" : " · " + g2.apply(p).label())).orElse("");
        }
        List<Column> cols = new ArrayList<>(List.of(Column.text(Texts.t("Matricule")), Column.text(Texts.t("Employee")),
                Column.text(Texts.t("Payslip")), Column.text(Texts.t("Grade")), Column.text(Texts.t("Unit")), Column.text(Texts.t("Currency"))));
        if (element != null) {
            cols.add(Column.money(Texts.t("Base")));
            cols.add(Column.money(Texts.t("Amount")));
        }
        cols.addAll(List.of(Column.money(Texts.t("Gross")), Column.money(Texts.t("Total deductions")), Column.money(Texts.t("Net")),
                Column.money(Texts.t("Employer charges"))));
        List<Row> rows = new ArrayList<>();
        Map<String, BigDecimal[]> totals = new TreeMap<>();
        for (P p : ctx.slips()) {
            if (!keep.test(p)) {
                continue;
            }
            String cur = ctx.currencyOf(p);
            List<Object> c = cells(p.s().registrationNo(), p.name(), Objects.requireNonNullElse(p.s().number(), ""),
                    p.s().gradeCode() == null ? Objects.requireNonNullElse(p.text("grade"), "") : p.s().gradeCode(),
                    Objects.requireNonNullElse(p.text("unit"), ""), cur);
            BigDecimal[] t = totals.computeIfAbsent(cur, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO});
            if (element != null) {
                BigDecimal amount = ctx.amount(p, p.code(element));
                BigDecimal base = p.lines().stream().filter(l -> l.code().equals(element) && l.base() != null).map(Line::base)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                c.add(base.signum() == 0 ? "" : ctx.amount(p, base));
                c.add(amount);
                t[4] = t[4].add(amount);
            }
            BigDecimal[] v = {ctx.amount(p, p.s().gross()), ctx.amount(p, p.s().deductions()), ctx.amount(p, p.s().net()),
                    ctx.amount(p, p.s().charges())};
            for (int i = 0; i < 4; i++) {
                c.add(v[i]);
                t[i] = t[i].add(v[i]);
            }
            rows.add(Row.of(c));
        }
        totals.forEach((cur, t) -> {
            List<Object> c = cells(Texts.t("Total"), "", "", "", "", cur);
            if (element != null) {
                c.add("");
                c.add(t[4]);
            }
            c.addAll(List.of(t[0], t[1], t[2], t[3]));
            rows.add(new Row(c, "TOTAL", 0, null));
        });
        return report(ctx, "employees", Texts.t("Employees: %s", what), List.of(new Table(what, cols, rows)), List.of());
    }

    private static int level(String v) {
        return v == null || v.isBlank() ? 0 : Integer.parseInt(v);
    }

    // ---- 8. livre de paie --------------------------------------------------------------------------------------

    public static final int LIVRE_ROWS_PER_PAGE = 24;

    /**
     * The livre de paie: one line per payslip with the 33 mentions of the DRC model (arrêté 12/CAB.MIN/ETPS/042 du 8
     * août 2008), and the leave allowances of the month on their own lines. Closed months only: it is a legal record
     * and does not change once written.
     */
    public Report livre(YearMonth month, Filters given) {
        requireNamed();
        // One currency, so that page totals add up: the local one unless dollars are asked for.
        Filters f = given.currency().equals("PAID")
                ? new Filters(given.unitId(), given.siteId(), given.categoryId(), given.complementary(), "LOCAL") : given;
        Ctx ctx = context(month, f);
        if (ctx.provisional()) {
            throw new BusinessRuleException("The livre de paie is made from a closed payroll: close %s first.".formatted(month));
        }
        Map<String, String> leaveCodes = new HashMap<>();
        jdbc.sql("select name, code from leave_type").query((rs, i) -> leaveCodes.put(rs.getString(1), rs.getString(2))).list();
        List<Column> cols = livreColumns();
        List<Row> rows = new ArrayList<>();
        int order = 0;
        for (P p : ctx.slips()) {
            Map<String, Integer> leaveDays = new HashMap<>();
            if (p.snap().get("leaveDays") instanceof Map<?, ?> m) {
                m.forEach((name, days) -> leaveDays.merge(leaveCodes.getOrDefault(String.valueOf(name), ""),
                        days instanceof Number n ? n.intValue() : 0, Integer::sum));
            }
            BigDecimal basicMonthly = p.snap().get("basicMonthly") == null ? null : ctx.amount(p, new BigDecimal(p.snap().get("basicMonthly").toString()));
            int scheduledDays = p.snap().get("scheduledDays") instanceof Number n ? n.intValue() : 0;
            BigDecimal paidDays = scheduledDays > 0 && p.s().scheduledMinutes() > 0
                    ? BigDecimal.valueOf(p.s().paidMinutes()).multiply(BigDecimal.valueOf(scheduledDays))
                            .divide(BigDecimal.valueOf(p.s().scheduledMinutes()), 1, RoundingMode.HALF_UP)
                    : BigDecimal.valueOf(p.s().daysWorked());
            BigDecimal remuneration = ctx.amount(p, p.code("BASIC", "COMPLEMENT"));
            BigDecimal otHours = p.lines().stream().filter(l -> l.code().equals("OVERTIME") && l.base() != null).map(Line::base)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal otAmount = ctx.amount(p, p.code("OVERTIME"));
            BigDecimal otRate = otHours.signum() == 0 ? null : otAmount.divide(otHours, 2, RoundingMode.HALF_UP);
            BigDecimal bonuses = ctx.amount(p, p.sum(l -> l.kind().equals("EARNING") && isBonus(l.code())));
            BigDecimal leaveAllowance = ctx.amount(p, p.code("LEAVE_ALLOWANCE"));
            BigDecimal family = ctx.amount(p, p.code("FAMILY_ALLOWANCE"));
            BigDecimal gross = ctx.amount(p, p.s().gross());
            BigDecimal livreGross = gross.subtract(family);  // mention 20: family allowances are paid beside it (30)
            BigDecimal various = livreGross.subtract(remuneration).subtract(otAmount).subtract(bonuses).subtract(leaveAllowance);
            List<String> variousLabels = p.lines().stream().filter(l -> l.kind().equals("EARNING") && !BASIC.contains(l.code())
                    && !l.code().equals("OVERTIME") && !isBonus(l.code()) && !l.code().equals("LEAVE_ALLOWANCE") && !FAMILY.contains(l.code())
                    && l.amount().signum() != 0).map(Line::label).distinct().toList();
            BigDecimal pension = ctx.amount(p, p.code("CNSS_EMPLOYEE"));
            BigDecimal compensation = ctx.amount(p, p.sum(l -> l.kind().equals("DEDUCTION") && COMPENSATION.contains(l.code())));
            BigDecimal advances = ctx.amount(p, p.sum(l -> l.kind().equals("DEDUCTION") && ADVANCES.contains(l.code())));
            BigDecimal tax = ctx.amount(p, p.code("IPR"));
            BigDecimal deductions = ctx.amount(p, p.s().deductions());
            BigDecimal otherDeductions = deductions.subtract(pension).subtract(compensation).subtract(advances).subtract(tax);
            List<String> otherLabels = p.lines().stream().filter(l -> l.kind().equals("DEDUCTION") && !l.code().equals("CNSS_EMPLOYEE")
                    && !COMPENSATION.contains(l.code()) && !ADVANCES.contains(l.code()) && !l.code().equals("IPR") && l.amount().signum() != 0)
                    .map(l -> l.label().replaceAll("\\s*#\\d+$", "")).distinct().toList();
            int children = p.snap().get("childrenInCharge") instanceof Number n ? n.intValue() : 0;
            BigDecimal familyRate = children > 0 && family.signum() != 0 ? family.divide(BigDecimal.valueOf(children), 2, RoundingMode.HALF_UP) : null;
            List<String> notes = new ArrayList<>();
            if (p.s().kind().equals("COMPLEMENTARY")) {
                notes.add(Texts.t("Correction of payslip %s", Objects.requireNonNullElse(p.s().correctsNumber(), "")));
            }
            if (!variousLabels.isEmpty()) {
                notes.add(Texts.t("Various: %s", String.join(", ", variousLabels)));
            }
            if (!otherLabels.isEmpty()) {
                notes.add(Texts.t("Deductions: %s", String.join(", ", otherLabels)));
            }
            if (leaveDays.getOrDefault("SICK", 0) > 0) {
                notes.add(Texts.t("Sick days paid within the remuneration"));
            }
            if (p.text("lastDay") != null) {
                notes.add(Texts.t("Leaves on %s", p.text("lastDay")));
            }
            order++;
            rows.add(Row.of(cells(Objects.requireNonNullElse(p.s().registrationNo(), String.valueOf(order)),
                    p.fullName(),
                    Objects.requireNonNullElse(p.text("occupation"), "") + " · "
                            + (p.s().categoryCode() == null ? Objects.requireNonNullElse(p.text("grade"), "") : p.s().categoryCode() + " " + Objects.requireNonNullElse(p.s().gradeCode(), "")),
                    Objects.requireNonNullElse(decrypt(p.text("cnssNumber")), ""),
                    basicMonthly == null ? "" : basicMonthly, paidDays, remuneration,
                    otHours.signum() == 0 ? "" : otHours, otRate == null ? "" : otRate, otAmount,
                    BigDecimal.ZERO.setScale(2), bonuses, various,
                    leaveDays.getOrDefault("ANNUAL", 0), "", leaveAllowance,
                    leaveDays.getOrDefault("SICK", 0), "", BigDecimal.ZERO.setScale(2),
                    livreGross, pension, compensation, advances, otherDeductions, tax, deductions,
                    children, children > 0 ? paidDays : "", familyRate == null ? "" : familyRate, family,
                    ctx.amount(p, p.s().net()), ctx.amount(p, p.s().cnssBase()), String.join("; ", notes), ctx.currencyOf(p))));
        }
        if (f.unitId() == null && f.siteId() == null && f.categoryId() == null) {
            rows.addAll(leaveAllowanceRows(ctx, month, order));
        }
        List<String> notes = List.of(
                Texts.t("Livre de paie under art. 213 of the Labour Code and arrêté 12/CAB.MIN/ETPS/042 of 8 August 2008 (33 mentions)."),
                Texts.t("Column 20 is the gross of the payslip without family allowances, which are paid beside it (30); 31 = 20 - 26 + 30."),
                Texts.t("Annual leave and sick days paid in the month are within the remuneration (7); separate leave allowances are on their own lines."),
                Texts.t("Closed on %s by %s. Data fingerprint %s.", ctx.run().closedAt() == null ? "" : Texts.day(ctx.run().closedAt().toLocalDate()),
                        Objects.requireNonNullElse(ctx.run().closedBy(), ""), fingerprint(rows)));
        return report(ctx, "livre", Texts.t("Livre de paie"), List.of(new Table(Texts.t("Pay sheet of %s", Texts.month(month)), cols, rows,
                LIVRE_ROWS_PER_PAGE, true)), notes);
    }

    private static boolean isBonus(String code) {
        return code.contains("BONUS") || code.equals("THIRTEENTH_MONTH") || code.startsWith("PRIME");
    }

    private String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            return crypto.decrypt(stored);
        } catch (RuntimeException e) {
            return stored;
        }
    }

    static List<Column> livreColumns() {
        return List.of(
                Column.text("1 " + Texts.t("No.")), Column.text("2 " + Texts.t("Name and first names")),
                Column.text("3 " + Texts.t("Job and category")), Column.text("4 " + Texts.t("CNSS no.")),
                Column.money("5 " + Texts.t("Monthly salary")), Column.number("6 " + Texts.t("Days paid")),
                Column.money("7 " + Texts.t("Remuneration of the period")), Column.number("8 " + Texts.t("Overtime hours")),
                Column.money("9 " + Texts.t("Overtime rate")), Column.money("10 " + Texts.t("Overtime amount")),
                Column.money("11 " + Texts.t("Saturday, Sunday, holiday supplements")), Column.money("12 " + Texts.t("Bonuses")),
                Column.money("13 " + Texts.t("Arrears and various")), Column.number("14 " + Texts.t("Paid leave days")),
                Column.money("15 " + Texts.t("Daily leave rate")), Column.money("16 " + Texts.t("Leave allowance")),
                Column.number("17 " + Texts.t("Sick days (2/3)")), Column.money("18 " + Texts.t("Daily sick rate")),
                Column.money("19 " + Texts.t("Sick pay")), Column.money("20 " + Texts.t("Gross remuneration")),
                Column.money("21 " + Texts.t("Pension contribution")), Column.money("22 " + Texts.t("Compensatory indemnities")),
                Column.money("23 " + Texts.t("Advances")), Column.money("24 " + Texts.t("Various deductions")),
                Column.money("25 " + Texts.t("Tax withheld")), Column.money("26 " + Texts.t("Total deductions")),
                Column.number("27 " + Texts.t("Children")), Column.number("28 " + Texts.t("Allowance days")),
                Column.money("29 " + Texts.t("Allowance rate")), Column.money("30 " + Texts.t("Family allowances")),
                Column.money("31 " + Texts.t("Net to pay")), Column.money("32 " + Texts.t("Social security base")),
                Column.text("33 " + Texts.t("Observations")), Column.text(Texts.t("Currency")));
    }

    private List<Row> leaveAllowanceRows(Ctx ctx, YearMonth month, int order) {
        record A(long userId, String firstName, String lastName, String registrationNo, String number, String currency,
                 BigDecimal days, BigDecimal dailyRate, BigDecimal gross, BigDecimal cnssBase, BigDecimal deductions, BigDecimal net,
                 String lines, String snapshot) {}
        List<A> list = jdbc.sql("""
                select a.user_id, u.first_name, upper(u.last_name) || coalesce(' ' || upper(nullif(trim(u.middle_name), '')), '') as last_name, u.registration_no, a.number, trim(a.currency) as currency, a.days,
                       a.daily_rate, a.gross, coalesce(a.cnss_base, 0) as cnss_base, coalesce(a.employee_deductions, 0) as deductions,
                       coalesce(a.net, a.gross) as net, a.lines::text as lines, coalesce(a.snapshot::text, '{}') as snapshot
                from leave_allowance a join app_user u on u.id = a.user_id
                where a.status in ('APPROVED', 'PAID') and a.number is not null and a.pay_by between ? and ?
                order by u.last_name, u.first_name, a.id""").params(month.atDay(1), month.atEndOfMonth()).query(A.class).list();
        List<Row> out = new ArrayList<>();
        String target = ctx.target();
        BigDecimal rate = ctx.run().usdCdf();
        for (A a : list) {
            Function<BigDecimal, BigDecimal> c = v -> target == null ? v : ExchangeRates.convert(v, a.currency(), target, rate);
            BigDecimal pension = BigDecimal.ZERO;
            BigDecimal tax = BigDecimal.ZERO;
            for (Object o : json.readValue(a.lines(), List.class)) {
                if (o instanceof Map<?, ?> l && l.get("amount") != null && "DEDUCTION".equals(l.get("kind"))) {
                    BigDecimal v = new BigDecimal(l.get("amount").toString());
                    if ("CNSS_EMPLOYEE".equals(l.get("code"))) {
                        pension = pension.add(v);
                    } else if ("IPR".equals(l.get("code"))) {
                        tax = tax.add(v);
                    }
                }
            }
            BigDecimal other = a.deductions().subtract(pension).subtract(tax);
            Map<?, ?> snap = json.readValue(a.snapshot(), Map.class);
            order++;
            out.add(Row.of(cells(Objects.requireNonNullElse(a.registrationNo(), String.valueOf(order)),
                    a.lastName().toUpperCase() + " " + a.firstName(), Objects.requireNonNullElse(snap.get("occupation"), "").toString(),
                    Objects.requireNonNullElse(decrypt(snap.get("cnssNumber") == null ? null : snap.get("cnssNumber").toString()), ""),
                    "", "", BigDecimal.ZERO.setScale(2), "", "", BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                    BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                    a.days(), c.apply(a.dailyRate().setScale(2, RoundingMode.HALF_UP)), c.apply(a.gross()), "", "", BigDecimal.ZERO.setScale(2),
                    c.apply(a.gross()), c.apply(pension), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), c.apply(other), c.apply(tax),
                    c.apply(a.deductions()), "", "", "", BigDecimal.ZERO.setScale(2), c.apply(a.net()), c.apply(a.cnssBase()),
                    Texts.t("Leave allowance %s", a.number()), target == null ? a.currency() : target)));
        }
        return out;
    }

    /** A short fingerprint of the register's lines, printed on it so a paper copy can be matched to the system. */
    static String fingerprint(List<Row> rows) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (Row r : rows) {
                for (Object c : r.cells()) {
                    d.update((c instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : String.valueOf(c)).getBytes(StandardCharsets.UTF_8));
                    d.update((byte) 31);
                }
                d.update((byte) 30);
            }
            return java.util.HexFormat.of().formatHex(d.digest()).substring(0, 16).toUpperCase();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- 9. quinzaine ------------------------------------------------------------------------------------------

    public record QuinzaineRunRef(long id, LocalDate month, LocalDate payDate, String status) {}

    public List<QuinzaineRunRef> quinzaineRuns() {
        return jdbc.sql("select id, month, pay_date, status from quinzaine_run where status <> 'CANCELLED' order by month desc, id desc")
                .query(QuinzaineRunRef.class).list();
    }

    /** A quinzaine run by grade category, site, unit and payment method; and how the month's payslips took it back. */
    @SuppressWarnings("unchecked")
    public Report quinzaine(long runId, Filters f, int level) {
        record Q(long userId, String firstName, String lastName, String registrationNo, String currency, String category, BigDecimal amount,
                 Long unitId, Long siteId, String siteName, String method, String bank, String operator) {}
        QuinzaineRunRef run = jdbc.sql("select id, month, pay_date, status from quinzaine_run where id = ?").param(runId)
                .query(QuinzaineRunRef.class).optional().orElseThrow(() -> new NotFoundException("Quinzaine not found."));
        Map<Long, UnitRow> units = units();
        Set<Long> branch = f.unitId() == null ? null : branch(units, f.unitId());
        Long categoryId = f.categoryId();
        String categoryCode = categoryId == null ? null
                : jdbc.sql("select code from grade_category where id = ?").param(categoryId).query(String.class).optional().orElse("?");
        List<Q> lines = jdbc.sql("""
                select l.user_id, u.first_name, upper(u.last_name) || coalesce(' ' || upper(nullif(trim(u.middle_name), '')), '') as last_name, u.registration_no, trim(l.currency) as currency, l.category, l.amount,
                       l.unit_id, u.site_id, s.name as site_name, e.payment_method as method, e.bank_name as bank,
                       e.mobile_money_operator as operator
                from quinzaine_line l join app_user u on u.id = l.user_id left join site s on s.id = u.site_id
                left join employee_profile e on e.user_id = l.user_id
                where l.run_id = ? order by u.last_name, u.first_name""").param(runId).query(Q.class).list().stream()
                .filter(q -> branch == null || (q.unitId() != null && branch.contains(q.unitId())))
                .filter(q -> f.siteId() == null || f.siteId().equals(q.siteId()))
                .filter(q -> categoryCode == null || categoryCode.equals(q.category()))
                .toList();
        String local = localCurrency();
        BigDecimal rate = runs.run(YearMonth.from(run.month())).map(PayrollRuns.Run::usdCdf).orElseGet(() -> jdbc.sql("""
                select cdf_per_unit from exchange_rate where currency = 'USD' and rate_date <= ? order by rate_date desc limit 1""")
                .param(run.payDate()).query(BigDecimal.class).optional().orElse(null));
        String target = switch (f.currency()) {
            case "LOCAL" -> local;
            case "USD" -> "USD";
            default -> null;
        };
        if (target != null && rate == null) {
            throw new BusinessRuleException("No dollar rate for this quinzaine: show the amounts as paid.");
        }
        Function<Q, BigDecimal> amount = q -> target == null ? q.amount() : ExchangeRates.convert(q.amount(), q.currency(), target, rate);
        Function<Q, String> cur = q -> target == null ? q.currency() : target;
        record Dim(String title, Function<Q, String> label) {}
        List<Dim> dims = List.of(
                new Dim(Texts.t("Grade category"), Q::category),
                new Dim(Texts.t("Site"), q -> Objects.requireNonNullElse(q.siteName(), Texts.t("Not set"))),
                new Dim(Texts.t("Unit"), q -> {
                    List<UnitRow> chain = chain(units, q.unitId());
                    if (chain.isEmpty()) {
                        return Texts.t("No unit");
                    }
                    return (level <= 0 ? chain.getFirst() : chain.stream().filter(u -> u.rank() <= level).findFirst().orElse(chain.getLast())).name();
                }),
                new Dim(Texts.t("Payment method"), q -> method(q.method()) + ("BANK".equals(q.method()) && q.bank() != null ? " · " + q.bank()
                        : "MOBILE_MONEY".equals(q.method()) && q.operator() != null ? " · " + q.operator() : "")));
        List<Table> tables = new ArrayList<>();
        for (Dim d : dims) {
            record K(String label, String currency) {}
            Map<K, BigDecimal> sums = new TreeMap<>(Comparator.comparing(K::label, String.CASE_INSENSITIVE_ORDER).thenComparing(K::currency));
            Map<K, Integer> counts = new HashMap<>();
            Map<String, BigDecimal> total = new TreeMap<>();
            Map<String, Integer> totalCount = new TreeMap<>();
            for (Q q : lines) {
                K k = new K(d.label().apply(q), cur.apply(q));
                sums.merge(k, amount.apply(q), BigDecimal::add);
                counts.merge(k, 1, Integer::sum);
                total.merge(k.currency(), amount.apply(q), BigDecimal::add);
                totalCount.merge(k.currency(), 1, Integer::sum);
            }
            List<Row> rows = new ArrayList<>();
            sums.forEach((k, v) -> rows.add(Row.of(cells(k.label(), k.currency(), counts.get(k), v))));
            total.forEach((c, v) -> rows.add(new Row(cells(Texts.t("Total"), c, totalCount.get(c), v), "TOTAL", 0, null)));
            tables.add(new Table(Texts.t("By %s", d.title().toLowerCase(java.util.Locale.ROOT)), List.of(Column.text(d.title()),
                    Column.text(Texts.t("Currency")), Column.number(Texts.t("Employees")), Column.money(Texts.t("Quinzaine"))), rows));
        }
        List<String> notes = new ArrayList<>();
        String skipped = jdbc.sql("select skipped::text from quinzaine_run where id = ?").param(runId).query(String.class).single();
        List<Object> left = json.readValue(skipped, List.class);
        if (!left.isEmpty()) {
            tables.add(new Table(Texts.t("Left out"), List.of(Column.text(Texts.t("Reason"))),
                    left.stream().map(s -> Row.of(cells(String.valueOf(s)))).toList()));
        }
        if (!List.of("CLOSED", "PAID").contains(run.status())) {
            notes.add(Texts.t("PROVISIONAL: this quinzaine is not closed; figures can still change."));
        }
        // How the month-end payslips took it back.
        PayrollRuns.Run month = runs.run(YearMonth.from(run.month())).orElse(null);
        if (month != null && CurrentUser.has("payslip.read.all")) {
            record T(long userId, BigDecimal taken, String currency) {}
            Map<Long, T> taken = new HashMap<>();
            jdbc.sql("""
                    select p.user_id, coalesce(sum(l.amount), 0) as taken, trim(p.currency) as currency from payslip p
                    left join payslip_line l on l.payslip_id = p.id and l.element_code = 'QUINZAINE'
                    where p.run_id = ? and p.kind = 'REGULAR' group by p.user_id, p.currency""").param(month.id()).query(T.class).list()
                    .forEach(t -> taken.put(t.userId(), t));
            List<Row> rows = new ArrayList<>();
            for (Q q : lines) {
                T t = taken.get(q.userId());
                BigDecimal back = t == null ? BigDecimal.ZERO : ExchangeRates.convert(t.taken(), t.currency(), q.currency(), month.usdCdf());
                BigDecimal rest = q.amount().subtract(back);
                rows.add(Row.of(cells(q.registrationNo(), q.lastName().toUpperCase() + " " + q.firstName(), q.currency(), q.amount(), back,
                        rest.signum() > 0 ? rest : BigDecimal.ZERO.setScale(2), t == null ? Texts.t("No payslip this month") : "")));
            }
            tables.add(new Table(Texts.t("Taken back by the payslips of %s", Texts.month(run.month())), List.of(Column.text(Texts.t("Matricule")),
                    Column.text(Texts.t("Employee")), Column.text(Texts.t("Currency")), Column.money(Texts.t("Paid")),
                    Column.money(Texts.t("Taken back")), Column.money(Texts.t("Carried as an advance")), Column.text(Texts.t("Note"))), rows));
            if (!month.status().equals("CLOSED")) {
                notes.add(Texts.t("The payroll of the month is not closed: what is not taken back becomes an advance when it closes."));
            }
        }
        String sub = Texts.t("Quinzaine of %s, paid on %s · %s employees", Texts.month(run.month()), Texts.day(run.payDate()), lines.size());
        return new Report("quinzaine", Texts.t("Quinzaine report"), sub, !List.of("CLOSED", "PAID").contains(run.status()), tables, notes);
    }

    // ---- the whole month ---------------------------------------------------------------------------------------

    /** Every report of the month, one table per sheet: for the Excel workbook. */
    public List<Report> all(YearMonth month, Filters f) {
        Ctx ctx = context(month, f);
        List<Report> out = new ArrayList<>();
        out.add(summary(month, f));
        out.add(report(ctx, "by", Texts.t("Payroll by grade category"), List.of(dimensionTable(ctx, "CATEGORY", "GRADE", 0, 0)), List.of()));
        out.add(report(ctx, "by", Texts.t("Payroll by site"), List.of(dimensionTable(ctx, "SITE", null, 0, 0)), List.of()));
        out.add(report(ctx, "tree", Texts.t("Payroll by organisational unit"), List.of(treeTable(ctx)), List.of()));
        out.add(report(ctx, "payment", Texts.t("Payroll by payment method"), List.of(dimensionTable(ctx, "PAYMENT_METHOD", null, 0, 0)), List.of()));
        out.add(report(ctx, "cross", Texts.t("Pay elements by unit"), List.of(crossTable(ctx, "UNIT", 2)), List.of()));
        if (CurrentUser.has("payslip.read.all")) {
            out.add(variance(month, f, null));
            if (!ctx.provisional()) {
                out.add(livre(month, f));
            }
        }
        return out;
    }
}
