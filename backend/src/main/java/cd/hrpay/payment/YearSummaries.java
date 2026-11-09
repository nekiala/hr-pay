package cd.hrpay.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** What each employee earned, paid in contributions and tax, and received over a year: closed months only. */
@Service
public class YearSummaries {

    public record Month(String month, long payslipId, String number, String currency, BigDecimal gross, BigDecimal cnss,
                        BigDecimal ipr, BigDecimal otherDeductions, BigDecimal net, BigDecimal taxableCdf,
                        BigDecimal iprCdf, LocalDate paidOn) {}

    public record Totals(String currency, int months, BigDecimal gross, BigDecimal cnss, BigDecimal ipr,
                         BigDecimal otherDeductions, BigDecimal net) {}

    public record Employee(long userId, String name, String registrationNo, int year, Map<String, Object> details,
                           List<Month> months, List<Totals> totals, BigDecimal taxableCdf, BigDecimal iprCdf) {}

    public record Row(long userId, String name, String registrationNo, String currency, int months, BigDecimal gross,
                      BigDecimal cnss, BigDecimal ipr, BigDecimal otherDeductions, BigDecimal net, BigDecimal iprCdf) {}

    private static final String MONTHS = """
            select p.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no, to_char(r.month, 'YYYY-MM') as month,
                   p.id, p.number, trim(p.currency) as currency, p.gross, p.employee_deductions, p.net, p.taxable_base_cdf,
                   r.usd_cdf, p.snapshot ->> 'cnssNumber' as cnss_number, p.snapshot ->> 'taxNumber' as tax_number,
                   coalesce((select sum(amount) from payslip_line l where l.payslip_id = p.id and l.element_code = 'CNSS_EMPLOYEE'), 0) as cnss,
                   coalesce((select sum(amount) from payslip_line l where l.payslip_id = p.id and l.element_code = 'IPR'), 0) as ipr,
                   (select b.paid_on from payment_line pl join payment_batch b on b.id = pl.batch_id
                    where pl.payslip_id = p.id and b.status = 'PAID') as paid_on
            from payslip p join payroll_run r on r.id = p.run_id join app_user u on u.id = p.user_id
            where r.status = 'CLOSED' and p.number is not null and extract(year from r.month) = ?""";

    private record Raw(long userId, String name, String registrationNo, String cnssNumber, String taxNumber, Month month) {}

    private final JdbcClient jdbc;
    private final cd.hrpay.security.DataCrypto crypto;

    public YearSummaries(JdbcClient jdbc, cd.hrpay.security.DataCrypto crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    /**
     * The totals paid before HR Pay (imported), as one line before the person's first month: "2026-01…2026-06".
     */
    private List<Raw> openings(int year, Long userId) {
        String sql = """
                select o.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no, o.through_month,
                       o.currency, o.gross, o.cnss, o.tax, o.other_deductions, o.net, o.taxable_cdf, o.tax_cdf,
                       p.cnss_number, p.tax_number
                from ytd_opening o join app_user u on u.id = o.user_id
                left join employee_profile p on p.user_id = o.user_id
                where o.year = ?""" + (userId == null ? "" : " and o.user_id = ?") + " order by u.last_name, u.first_name";
        var spec = jdbc.sql(sql).param(year);
        if (userId != null) {
            spec = spec.param(userId);
        }
        return spec.query((rs, i) -> new Raw(rs.getLong("user_id"), rs.getString("name"), rs.getString("registration_no"),
                crypto.decrypt(rs.getString("cnss_number")), crypto.decrypt(rs.getString("tax_number")),
                new Month("%d-01…%d-%02d".formatted(year, year, rs.getInt("through_month")), 0, null, rs.getString("currency").trim(),
                        rs.getBigDecimal("gross"), rs.getBigDecimal("cnss"), rs.getBigDecimal("tax"), rs.getBigDecimal("other_deductions"),
                        rs.getBigDecimal("net"), rs.getBigDecimal("taxable_cdf"), rs.getBigDecimal("tax_cdf"), null))).list();
    }

    private List<Raw> raw(int year, Long userId) {
        List<Raw> closed = closed(year, userId);
        List<Raw> openings = openings(year, userId);
        if (openings.isEmpty()) {
            return closed;
        }
        Map<Long, List<Raw>> by = new LinkedHashMap<>();
        openings.forEach(o -> by.computeIfAbsent(o.userId(), k -> new ArrayList<>()).add(o));
        closed.forEach(c -> by.computeIfAbsent(c.userId(), k -> new ArrayList<>()).add(c));
        return by.values().stream().flatMap(List::stream).toList();
    }

    private List<Raw> closed(int year, Long userId) {
        String sql = MONTHS + (userId == null ? "" : " and p.user_id = ?") + "\norder by u.last_name, u.first_name, r.month";
        var spec = jdbc.sql(sql).param(year);
        if (userId != null) {
            spec = spec.param(userId);
        }
        return spec.query((rs, i) -> {
            String currency = rs.getString("currency");
            BigDecimal ipr = rs.getBigDecimal("ipr");
            BigDecimal cnss = rs.getBigDecimal("cnss");
            BigDecimal iprCdf = (currency.equals("USD") ? ipr.multiply(rs.getBigDecimal("usd_cdf")) : ipr).setScale(2, RoundingMode.HALF_UP);
            return new Raw(rs.getLong("user_id"), rs.getString("name"), rs.getString("registration_no"),
                    crypto.decrypt(rs.getString("cnss_number")), crypto.decrypt(rs.getString("tax_number")),
                    new Month(rs.getString("month"), rs.getLong("id"), rs.getString("number"), currency,
                            rs.getBigDecimal("gross"), cnss, ipr, rs.getBigDecimal("employee_deductions").subtract(cnss).subtract(ipr),
                            rs.getBigDecimal("net"), rs.getBigDecimal("taxable_base_cdf").setScale(2, RoundingMode.HALF_UP),
                            iprCdf, rs.getObject("paid_on", LocalDate.class)));
        }).list();
    }

    /** A month of HR Pay counts one; the totals paid before HR Pay count the months they cover. */
    private static int span(Month m) {
        return m.payslipId() == 0 ? Integer.parseInt(m.month().substring(m.month().length() - 2)) : 1;
    }

    private static List<Totals> totals(List<Month> months) {
        Map<String, Totals> by = new LinkedHashMap<>();
        for (Month m : months) {
            Totals t = by.get(m.currency());
            by.put(m.currency(), t == null
                    ? new Totals(m.currency(), span(m), m.gross(), m.cnss(), m.ipr(), m.otherDeductions(), m.net())
                    : new Totals(m.currency(), t.months() + span(m), t.gross().add(m.gross()), t.cnss().add(m.cnss()),
                            t.ipr().add(m.ipr()), t.otherDeductions().add(m.otherDeductions()), t.net().add(m.net())));
        }
        return new ArrayList<>(by.values());
    }

    /** One employee's year, month by month: the annual pay statement. Empty months list if nothing was closed. */
    public Employee employee(long userId, int year) {
        List<Raw> raw = raw(year, userId);
        List<Month> months = raw.stream().map(Raw::month).toList();
        Map<String, Object> details = new LinkedHashMap<>();
        String name = null;
        String registration = null;
        if (!raw.isEmpty()) {
            Raw last = raw.getLast();
            name = last.name();
            registration = last.registrationNo();
            details.put("cnssNumber", last.cnssNumber());
            details.put("taxNumber", last.taxNumber());
        } else {
            var u = jdbc.sql("select person_name(first_name, middle_name, last_name), registration_no from app_user where id = ?").param(userId)
                    .query((rs, i) -> new String[] {rs.getString(1), rs.getString(2)}).optional();
            if (u.isPresent()) {
                name = u.get()[0];
                registration = u.get()[1];
            }
        }
        return new Employee(userId, name, registration, year, details, months, totals(months),
                months.stream().map(Month::taxableCdf).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add),
                months.stream().map(Month::iprCdf).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add));
    }

    /** Everyone paid in the year, one row per employee and currency. */
    public List<Row> year(int year) {
        Map<String, Row> by = new LinkedHashMap<>();
        for (Raw r : raw(year, null)) {
            Month m = r.month();
            String key = r.userId() + "|" + m.currency();
            Row t = by.get(key);
            by.put(key, t == null
                    ? new Row(r.userId(), r.name(), r.registrationNo(), m.currency(), span(m), m.gross(), m.cnss(), m.ipr(),
                            m.otherDeductions(), m.net(), m.iprCdf())
                    : new Row(t.userId(), t.name(), t.registrationNo(), t.currency(), t.months() + span(m), t.gross().add(m.gross()),
                            t.cnss().add(m.cnss()), t.ipr().add(m.ipr()), t.otherDeductions().add(m.otherDeductions()),
                            t.net().add(m.net()), t.iprCdf().add(m.iprCdf())));
        }
        return new ArrayList<>(by.values());
    }
}
