package cd.hrpay.filing;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.payment.Company;
import cd.hrpay.payroll.report.Report;
import cd.hrpay.payroll.report.Report.Column;
import cd.hrpay.payroll.report.Report.Row;
import cd.hrpay.payroll.report.Report.Table;
import cd.hrpay.platform.CountryPacks;
import cd.hrpay.security.DataCrypto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The year's pay, person by person: the year-to-date ledger (each month's gross, taxable base, tax, CNSS and net), the
 * DRC annual IPR summary with its nominal list and individual sheets (Loi 004/2003 art. 18), and the Republic of the
 * Congo's annual statement of salaries (DAS, CGI art. 176). Closed months only, plus the totals paid before HR Pay.
 * Amounts in the local currency use each month's payroll rate, as the monthly declarations do.
 */
@Service
public class AnnualPayFilings {

    /** One person's month (or the opening totals, month 0), with the tax and contributions in the local currency. */
    record Line(long userId, String registrationNo, String name, String taxNumber, String cnssNumber, boolean expatriate,
                int month, String currency, BigDecimal gross, BigDecimal net, BigDecimal taxableLocal, BigDecimal taxLocal,
                BigDecimal cnssEmployee, BigDecimal cnssEmployer, BigDecimal iereLocal, BigDecimal grossLocal) {}

    /** The closed payslips of the year and the numbered leave allowance payslips (bulletins d'allocation de congé). */
    private static final String MONTHS = """
            select * from (
              select p.user_id, u.registration_no, person_name(u.first_name, u.middle_name, u.last_name) as name,
                     u.last_name, u.first_name, pr.tax_number, pr.cnss_number,
                     coalesce((p.snapshot ->> 'expatriate')::boolean, false) as expatriate,
                     extract(month from r.month)::int as month, trim(p.currency) as currency, p.gross, p.net,
                     p.taxable_base_cdf as taxable_local,
                     case when trim(p.currency) = :local then 1 else coalesce(r.usd_cdf, 1) end as rate,
                     coalesce((select sum(amount) from payslip_line l where l.payslip_id = p.id and l.element_code in ('IPR', 'ITS')), 0) as tax,
                     coalesce((select sum(amount) from payslip_line l where l.payslip_id = p.id and l.element_code = 'CNSS_EMPLOYEE'), 0) as cnss_employee,
                     coalesce((select sum(amount) from payslip_line l where l.payslip_id = p.id and l.element_code like 'CNSS_EMPLOYER%%'), 0) as cnss_employer,
                     coalesce((select sum(amount) from payslip_line l where l.payslip_id = p.id and l.element_code = 'IERE'), 0) as iere
              from payslip p join payroll_run r on r.id = p.run_id join app_user u on u.id = p.user_id
              left join employee_profile pr on pr.user_id = p.user_id
              where r.status = 'CLOSED' and p.number is not null and extract(year from r.month) = :year %1$s
              union all
              select a.user_id, u.registration_no, person_name(u.first_name, u.middle_name, u.last_name),
                     u.last_name, u.first_name, pr.tax_number, pr.cnss_number,
                     coalesce((a.snapshot ->> 'expatriate')::boolean, false),
                     extract(month from a.pay_by)::int, trim(a.currency), a.gross, coalesce(a.net, a.gross),
                     coalesce(a.taxable_base_cdf, 0),
                     case when trim(a.currency) = :local then 1 else coalesce(a.usd_cdf, 1) end,
                     coalesce((select sum((e ->> 'amount')::numeric) from jsonb_array_elements(a.lines) e where e ->> 'code' in ('IPR', 'ITS')), 0),
                     coalesce((select sum((e ->> 'amount')::numeric) from jsonb_array_elements(a.lines) e where e ->> 'code' = 'CNSS_EMPLOYEE'), 0),
                     coalesce((select sum((e ->> 'amount')::numeric) from jsonb_array_elements(a.lines) e where e ->> 'code' like 'CNSS_EMPLOYER%%'), 0),
                     coalesce((select sum((e ->> 'amount')::numeric) from jsonb_array_elements(a.lines) e where e ->> 'code' = 'IERE'), 0)
              from leave_allowance a join app_user u on u.id = a.user_id
              left join employee_profile pr on pr.user_id = a.user_id
              where a.status in ('APPROVED', 'PAID') and a.number is not null and extract(year from a.pay_by) = :year %2$s
            ) x order by x.last_name, x.first_name, x.month""";

    private static final String OPENINGS = """
            select o.user_id, u.registration_no, person_name(u.first_name, u.middle_name, u.last_name) as name,
                   pr.tax_number, pr.cnss_number, trim(o.currency) as currency, o.gross, o.net, o.cnss, o.taxable_cdf, o.tax_cdf
            from ytd_opening o join app_user u on u.id = o.user_id left join employee_profile pr on pr.user_id = o.user_id
            where o.year = :year""";

    private final JdbcClient jdbc;
    private final Company company;
    private final DataCrypto crypto;
    private final LegalFilings filings;

    public AnnualPayFilings(JdbcClient jdbc, Company company, DataCrypto crypto, LegalFilings filings) {
        this.jdbc = jdbc;
        this.company = company;
        this.crypto = crypto;
        this.filings = filings;
    }

    List<Line> lines(int year, Long userId) {
        String local = company.localCurrency();
        var spec = jdbc.sql(userId == null ? MONTHS.formatted("", "") : MONTHS.formatted("and p.user_id = :user", "and a.user_id = :user"))
                .param("local", local).param("year", year);
        if (userId != null) {
            spec = spec.param("user", userId);
        }
        List<Line> out = new ArrayList<>(spec.query((rs, i) -> {
            BigDecimal rate = rs.getBigDecimal("rate");
            Function<BigDecimal, BigDecimal> toLocal = a -> a.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            return new Line(rs.getLong("user_id"), rs.getString("registration_no"), rs.getString("name"),
                    crypto.decrypt(rs.getString("tax_number")), crypto.decrypt(rs.getString("cnss_number")), rs.getBoolean("expatriate"),
                    rs.getInt("month"), rs.getString("currency"), rs.getBigDecimal("gross"), rs.getBigDecimal("net"),
                    rs.getBigDecimal("taxable_local").setScale(2, RoundingMode.HALF_UP), toLocal.apply(rs.getBigDecimal("tax")),
                    rs.getBigDecimal("cnss_employee"), rs.getBigDecimal("cnss_employer"), toLocal.apply(rs.getBigDecimal("iere")),
                    toLocal.apply(rs.getBigDecimal("gross")));
        }).list());
        var open = jdbc.sql(OPENINGS + (userId == null ? "" : " and o.user_id = :user")).param("year", year);
        if (userId != null) {
            open = open.param("user", userId);
        }
        out.addAll(0, open.query((rs, i) -> new Line(rs.getLong("user_id"), rs.getString("registration_no"), rs.getString("name"),
                crypto.decrypt(rs.getString("tax_number")), crypto.decrypt(rs.getString("cnss_number")), false, 0,
                rs.getString("currency"), rs.getBigDecimal("gross"), rs.getBigDecimal("net"), rs.getBigDecimal("taxable_cdf"),
                rs.getBigDecimal("tax_cdf"), rs.getBigDecimal("cnss"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                rs.getString("currency").equals(local) ? rs.getBigDecimal("gross") : rs.getBigDecimal("taxable_cdf"))).list());
        return out;
    }

    // ---- A6: the year-to-date ledger ---------------------------------------------------------------------------------

    /** One table per amount: a row per person (and currency), a column per month, the opening totals and the year's total. */
    public Report ledger(int year) {
        String local = company.localCurrency();
        String tax = CountryPacks.taxName(company.country());
        List<Line> lines = lines(year, null);
        boolean openings = lines.stream().anyMatch(l -> l.month() == 0);
        List<Table> tables = new ArrayList<>();
        tables.add(grid(Texts.t("Gross pay"), lines, Line::gross, true, openings));
        tables.add(grid(Texts.t("Taxable base (%s)", local), lines, Line::taxableLocal, false, openings));
        tables.add(grid(Texts.t("%s withheld (%s)", tax, local), lines, Line::taxLocal, false, openings));
        tables.add(grid(Texts.t("CNSS, employee share"), lines, Line::cnssEmployee, true, openings));
        tables.add(grid(Texts.t("CNSS, employer share"), lines, Line::cnssEmployer, true, openings));
        tables.add(grid(Texts.t("Net pay"), lines, Line::net, true, openings));
        List<String> notes = List.of(Texts.t("Closed months only. Amounts in %s use each month's payroll rate.", local));
        return new Report("legal.ledger", Texts.t("Year-to-date payroll ledger %d", year),
                Texts.t("%d people paid", lines.stream().map(Line::userId).distinct().count()), false, tables, notes);
    }

    private static Table grid(String title, List<Line> lines, Function<Line, BigDecimal> value, boolean byCurrency, boolean openings) {
        Map<String, BigDecimal[]> by = new LinkedHashMap<>();
        Map<String, List<Object>> heads = new LinkedHashMap<>();
        BigDecimal[] total = zeros();
        for (Line l : lines) {
            String key = l.userId() + (byCurrency ? "|" + l.currency() : "");
            heads.computeIfAbsent(key, k -> List.of(l.registrationNo(), l.name(), byCurrency ? l.currency() : ""));
            BigDecimal[] row = by.computeIfAbsent(key, k -> zeros());
            row[l.month()] = row[l.month()].add(value.apply(l));
            row[13] = row[13].add(value.apply(l));
            if (!byCurrency) {
                total[l.month()] = total[l.month()].add(value.apply(l));
                total[13] = total[13].add(value.apply(l));
            }
        }
        List<Row> rows = new ArrayList<>();
        by.forEach((k, v) -> rows.add(Row.of(cells(heads.get(k), v, openings))));
        if (!byCurrency && !rows.isEmpty()) {
            rows.add(new Row(cells(List.of("", Texts.t("Total"), ""), total, openings), "TOTAL", 0, null));
        }
        List<Column> cols = new ArrayList<>(List.of(Column.text(Texts.t("Registration no.")), Column.text(Texts.t("Name")),
                Column.text(Texts.t("Currency"))));
        if (openings) {
            cols.add(Column.money(Texts.t("Before HR Pay")));
        }
        for (Month m : Month.values()) {
            cols.add(Column.money(m.getDisplayName(TextStyle.SHORT, Texts.locale())));
        }
        cols.add(Column.money(Texts.t("Year")));
        return new Table(title, cols, rows, 0, true);
    }

    private static List<Object> cells(List<Object> head, BigDecimal[] v, boolean openings) {
        List<Object> c = new ArrayList<>(head);
        for (int m = openings ? 0 : 1; m <= 13; m++) {
            c.add(v[m]);
        }
        return c;
    }

    private static BigDecimal[] zeros() {
        BigDecimal[] z = new BigDecimal[14];
        java.util.Arrays.fill(z, BigDecimal.ZERO.setScale(2));
        return z;
    }

    // ---- A7: the annual tax summary (DRC IPR) or the DAS (Congo) ----------------------------------------------------

    /** The nominal list of the year with each person's totals, the month-by-month summary, and the totals. */
    public Report taxSummary(int year) {
        String country = company.country();
        if (!country.equals("CD") && !country.equals("CG")) {
            throw new BusinessRuleException("The annual tax summary is made for the DRC and the Republic of the Congo.");
        }
        boolean drc = country.equals("CD");
        String local = company.localCurrency();
        String tax = CountryPacks.taxName(country);
        List<Line> lines = lines(year, null);
        record Acc(Line first, int[] months, BigDecimal[] sums) {}
        Map<Long, Acc> people = new LinkedHashMap<>();
        BigDecimal[][] byMonth = new BigDecimal[13][];
        int[] staff = new int[13];
        java.util.Set<String> counted = new java.util.HashSet<>();
        for (Line l : lines) {
            Acc a = people.computeIfAbsent(l.userId(), k -> new Acc(l, new int[1], zeros()));
            if (l.month() > 0 && counted.add(l.userId() + "|" + l.month())) {
                a.months()[0]++;
                staff[l.month()]++;
            } else if (l.month() == 0) {
                a.months()[0] += throughMonth(year, l.userId(), l.currency());
            }
            BigDecimal[] s = a.sums();
            s[0] = s[0].add(l.grossLocal());
            s[1] = s[1].add(l.taxableLocal());
            s[2] = s[2].add(l.taxLocal());
            s[3] = s[3].add(l.iereLocal());
            if (byMonth[l.month()] == null) {
                byMonth[l.month()] = zeros();
            }
            byMonth[l.month()][0] = byMonth[l.month()][0].add(l.grossLocal());
            byMonth[l.month()][1] = byMonth[l.month()][1].add(l.taxableLocal());
            byMonth[l.month()][2] = byMonth[l.month()][2].add(l.taxLocal());
            byMonth[l.month()][3] = byMonth[l.month()][3].add(l.iereLocal());
        }
        List<Row> nominal = new ArrayList<>();
        BigDecimal[] total = zeros();
        int n = 0;
        for (Acc a : people.values()) {
            Line f = a.first();
            BigDecimal[] s = a.sums();
            List<Object> cells = new ArrayList<>(List.of(++n, f.registrationNo(), f.name(), nz(f.taxNumber()), nz(f.cnssNumber()),
                    a.months()[0], s[0], s[1], s[2]));
            if (drc) {
                cells.add(s[3]);
            }
            nominal.add(Row.of(cells));
            for (int i = 0; i < 4; i++) {
                total[i] = total[i].add(s[i]);
            }
        }
        List<Object> totalCells = new ArrayList<>(List.of("", "", Texts.t("Total"), "", "", people.size(), total[0], total[1], total[2]));
        if (drc) {
            totalCells.add(total[3]);
        }
        if (!nominal.isEmpty()) {
            nominal.add(new Row(totalCells, "TOTAL", 0, null));
        }
        List<Column> cols = new ArrayList<>(List.of(Column.number(Texts.t("No.")), Column.text(Texts.t("Registration no.")),
                Column.text(Texts.t("Name")), Column.text(drc ? Texts.t("Tax number (NIF)") : Texts.t("Tax number (NIU)")),
                Column.text(Texts.t("CNSS no.")), Column.number(Texts.t("Months")), Column.money(Texts.t("Gross pay (%s)", local)),
                Column.money(Texts.t("Taxable (%s)", local)), Column.money(Texts.t("%s withheld (%s)", tax, local))));
        if (drc) {
            cols.add(Column.money(Texts.t("IERE (%s)", local)));
        }
        List<Row> monthly = new ArrayList<>();
        for (int m = 0; m <= 12; m++) {
            if (byMonth[m] == null) {
                continue;
            }
            List<Object> c = new ArrayList<>(List.of(m == 0 ? Texts.t("Before HR Pay") : Month.of(m).getDisplayName(TextStyle.FULL, Texts.locale()),
                    staff[m], byMonth[m][0], byMonth[m][1], byMonth[m][2]));
            if (drc) {
                c.add(byMonth[m][3]);
            }
            monthly.add(Row.of(c));
        }
        if (!monthly.isEmpty()) {
            List<Object> c = new ArrayList<>(List.of(Texts.t("Total"), people.size(), total[0], total[1], total[2]));
            if (drc) {
                c.add(total[3]);
            }
            monthly.add(new Row(c, "TOTAL", 0, null));
        }
        List<Column> mcols = new ArrayList<>(List.of(Column.text(Texts.t("Month")), Column.number(Texts.t("People")),
                Column.money(Texts.t("Gross pay (%s)", local)), Column.money(Texts.t("Taxable (%s)", local)),
                Column.money(Texts.t("%s withheld (%s)", tax, local))));
        if (drc) {
            mcols.add(Column.money(Texts.t("IERE (%s)", local)));
        }
        List<Table> tables = List.of(new Table(Texts.t("Employer"), LegalFilings.fieldColumns(), filings.employer()),
                new Table(Texts.t("Month by month"), mcols, monthly),
                new Table(Texts.t("Nominal list"), cols, nominal, 0, true));
        List<String> notes = new ArrayList<>();
        long noTax = people.values().stream().filter(a -> a.first().taxNumber() == null).count();
        if (noTax > 0) {
            notes.add(Texts.t("To complete: %d people have no tax number in their employee file.", noTax));
        }
        notes.add(drc ? Texts.t("Loi 004/2003 art. 18: sent with the December declaration, with the individual sheets.")
                : Texts.t("CGI art. 176: annual statement of salaries (DAS), filed in January."));
        notes.add(Texts.t("Closed months only. Amounts in %s use each month's payroll rate.", local));
        notes.add(filings.signature());
        return new Report("legal.tax", drc ? Texts.t("Annual IPR summary %d", year) : Texts.t("Annual statement of salaries (DAS) %d", year),
                Texts.t("%d people paid", people.size()), false, tables, notes);
    }

    private int throughMonth(int year, long userId, String currency) {
        return jdbc.sql("select through_month from ytd_opening where year = ? and user_id = ? and trim(currency) = ?")
                .params(year, userId, currency).query(Integer.class).optional().orElse(0);
    }

    /** The individual sheets: one table per person, month by month, with the year's totals. */
    public Report individualSheets(int year, Long userId) {
        String local = company.localCurrency();
        String tax = CountryPacks.taxName(company.country());
        Map<Long, List<Line>> by = new LinkedHashMap<>();
        for (Line l : lines(year, userId)) {
            by.computeIfAbsent(l.userId(), k -> new ArrayList<>()).add(l);
        }
        List<Table> tables = new ArrayList<>();
        for (List<Line> person : by.values()) {
            Line f = person.getFirst();
            List<Row> rows = new ArrayList<>();
            BigDecimal[] t = zeros();
            for (Line l : person) {
                rows.add(Row.of(List.of(l.month() == 0 ? Texts.t("Before HR Pay") : Month.of(l.month()).getDisplayName(TextStyle.FULL, Texts.locale()),
                        l.currency(), l.gross(), l.grossLocal(), l.taxableLocal(), l.taxLocal(), l.cnssEmployee(), l.net())));
                t[3] = t[3].add(l.grossLocal());
                t[4] = t[4].add(l.taxableLocal());
                t[5] = t[5].add(l.taxLocal());
            }
            rows.add(new Row(List.of(Texts.t("Total"), "", "", t[3], t[4], t[5], "", ""), "TOTAL", 0, null));
            String title = f.name() + " · " + f.registrationNo() + " · " + Texts.t("Tax number") + " " + nz(f.taxNumber())
                    + " · CNSS " + nz(f.cnssNumber());
            tables.add(new Table(title, List.of(Column.text(Texts.t("Month")), Column.text(Texts.t("Currency")),
                    Column.money(Texts.t("Gross pay")), Column.money(Texts.t("Gross pay (%s)", local)),
                    Column.money(Texts.t("Taxable (%s)", local)), Column.money(Texts.t("%s withheld (%s)", tax, local)),
                    Column.money(Texts.t("CNSS, employee share")), Column.money(Texts.t("Net pay"))), rows));
        }
        return new Report("legal.sheets", Texts.t("Individual tax sheets %d", year), Texts.t("%d people paid", by.size()),
                false, tables, List.of(Texts.t("Closed months only. Amounts in %s use each month's payroll rate.", local), filings.signature()));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
