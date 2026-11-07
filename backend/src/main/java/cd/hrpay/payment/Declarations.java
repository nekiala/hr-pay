package cd.hrpay.payment;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The monthly declarations, computed from the payslips of a closed month and stated in CDF at the payroll's rate:
 * CNSS (employee and employer shares), IPR withheld (DGI), INPP, ONEM and IERE (employer charges).
 */
@Service
public class Declarations {

    public static final List<String> KINDS = List.of("CNSS", "IPR", "INPP", "ONEM", "IERE");

    public record Row(long userId, String registrationNo, String name, String idNumber, String currency, BigDecimal base,
                      BigDecimal baseCdf, BigDecimal employeeCdf, BigDecimal employerCdf, BigDecimal totalCdf,
                      String directionName, String unitName) {}

    public record Filed(LocalDate filedOn, String reference, String note, String filedBy, OffsetDateTime filedAt) {}

    public record Declaration(String month, String kind, String runStatus, BigDecimal usdCdf, LocalDate rateDate,
                              List<Row> rows, BigDecimal baseCdf, BigDecimal employeeCdf, BigDecimal employerCdf,
                              BigDecimal totalCdf, List<String> warnings, Filed filed) {}

    public record Summary(String kind, int employees, BigDecimal totalCdf, Filed filed) {}

    private record RunRow(long id, String status, BigDecimal usdCdf, LocalDate rateDate) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final Clock clock;
    private cd.hrpay.org.OrgStructure org;

    @org.springframework.beans.factory.annotation.Autowired
    void setOrg(cd.hrpay.org.OrgStructure org) {
        this.org = org;
    }

    public Declarations(JdbcClient jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    private RunRow run(YearMonth month) {
        return jdbc.sql("select id, status, usd_cdf, rate_date from payroll_run where month = ?").param(month.atDay(1))
                .query(RunRow.class).optional()
                .orElseThrow(() -> new NotFoundException("No payroll for " + month + " yet."));
    }

    private static void requireKind(String kind) {
        if (!KINDS.contains(kind)) {
            throw new NotFoundException("Unknown declaration " + kind + ".");
        }
    }

    private static BigDecimal cdf(BigDecimal amount, String currency, BigDecimal usdCdf) {
        if (amount == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        return (currency.equals("CDF") ? amount : amount.multiply(usdCdf)).setScale(2, RoundingMode.HALF_UP);
    }

    public Declaration declaration(YearMonth month, String kind) {
        requireKind(kind);
        RunRow run = run(month);
        String employeeCode = switch (kind) {
            case "CNSS" -> "CNSS_EMPLOYEE";
            case "IPR" -> "IPR";
            default -> null;
        };
        String employerCode = switch (kind) {
            case "CNSS" -> "CNSS_EMPLOYER";
            case "IPR" -> null;
            default -> kind;
        };
        String idKey = switch (kind) {
            case "CNSS" -> "cnssNumber";
            case "IPR" -> "taxNumber";
            default -> null;
        };
        List<Row> rows = new ArrayList<>();
        var units = org.placementsOfUnits();
        List<String> warnings = new ArrayList<>();
        jdbc.sql("""
                select p.user_id, u.registration_no, u.first_name || ' ' || u.last_name as name, trim(p.currency) as currency,
                       p.cnss_base, p.taxable_base, p.taxable_base_cdf, p.snapshot ->> ? as id_number,
                       (select sum(l.amount) from payslip_line l where l.payslip_id = p.id and l.element_code = ?) as employee_amount,
                       (select sum(l.amount) from payslip_line l where l.payslip_id = p.id and l.element_code = ?) as employer_amount,
                       (select max(l.base) from payslip_line l where l.payslip_id = p.id and l.element_code = ?) as employer_base
                , p.unit_id from payslip p join app_user u on u.id = p.user_id
                where p.run_id = ? order by u.last_name, u.first_name""")
                .params(idKey == null ? "-" : idKey, employeeCode == null ? "-" : employeeCode,
                        employerCode == null ? "-" : employerCode, employerCode == null ? "-" : employerCode, run.id())
                .query((rs, i) -> {
                    String currency = rs.getString("currency");
                    BigDecimal employee = rs.getBigDecimal("employee_amount");
                    BigDecimal employer = rs.getBigDecimal("employer_amount");
                    BigDecimal base = switch (kind) {
                        case "CNSS" -> rs.getBigDecimal("cnss_base");
                        case "IPR" -> rs.getBigDecimal("taxable_base");
                        default -> rs.getBigDecimal("employer_base");
                    };
                    if (employee == null && employer == null) {
                        return null;     // not concerned (for instance IERE for a national)
                    }
                    BigDecimal baseCdf = kind.equals("IPR") ? rs.getBigDecimal("taxable_base_cdf").setScale(2, RoundingMode.HALF_UP)
                            : cdf(base, currency, run.usdCdf());
                    BigDecimal e = cdf(employee, currency, run.usdCdf());
                    BigDecimal r = cdf(employer, currency, run.usdCdf());
                    var place = units.get(rs.getObject("unit_id", Long.class));
                    return new Row(rs.getLong("user_id"), rs.getString("registration_no"), rs.getString("name"),
                            rs.getString("id_number"), currency, base, baseCdf, e, r, e.add(r),
                            place == null ? null : place.directionName(), place == null ? null : place.unitName());
                }).list().stream().filter(java.util.Objects::nonNull).forEach(rows::add);
        if (idKey != null) {
            rows.stream().filter(r -> r.idNumber() == null || r.idNumber().isBlank())
                    .forEach(r -> warnings.add(r.name() + " has no " + (kind.equals("CNSS") ? "CNSS number" : "tax number (NIF)")
                            + " in the employee file."));
        }
        if (!run.status().equals("CLOSED")) {
            warnings.add(0, "The " + month + " payroll is not closed yet: these amounts can still change.");
        }
        return new Declaration(month.toString(), kind, run.status(), run.usdCdf(), run.rateDate(), rows,
                sum(rows, Row::baseCdf), sum(rows, Row::employeeCdf), sum(rows, Row::employerCdf), sum(rows, Row::totalCdf),
                warnings, filed(month, kind));
    }

    private static BigDecimal sum(List<Row> rows, java.util.function.Function<Row, BigDecimal> f) {
        return rows.stream().map(f).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private Filed filed(YearMonth month, String kind) {
        return jdbc.sql("select filed_on, reference, note, filed_by, filed_at from declaration where month = ? and kind = ?")
                .params(month.atDay(1), kind).query(Filed.class).optional().orElse(null);
    }

    public List<Summary> summary(YearMonth month) {
        return KINDS.stream().map(k -> {
            Declaration d = declaration(month, k);
            return new Summary(k, d.rows().size(), d.totalCdf(), d.filed());
        }).toList();
    }

    @Transactional
    public Declaration file(YearMonth month, String kind, LocalDate filedOn, String reference, String note) {
        Declaration d = declaration(month, kind);
        if (!d.runStatus().equals("CLOSED")) {
            throw new BusinessRuleException("Close the " + month + " payroll before recording its declarations.");
        }
        if (d.filed() != null) {
            throw new ConflictException("The " + kind + " declaration for " + month + " is already recorded.");
        }
        if (filedOn.isAfter(LocalDate.now(clock))) {
            throw new BusinessRuleException("The filing date cannot be in the future.");
        }
        jdbc.sql("""
                insert into declaration (month, kind, total_cdf, filed_on, reference, note, filed_by)
                values (?, ?, ?, ?, ?, ?, ?)""")
                .params(month.atDay(1), kind, d.totalCdf(), filedOn, blank(reference), blank(note), CurrentUser.email())
                .update();
        audit.record("declaration.filed", "declaration", month + " " + kind, Map.of("totalCdf", d.totalCdf(),
                "filedOn", filedOn.toString(), "reference", reference == null ? "" : reference.trim()));
        return declaration(month, kind);
    }

    /** Undoes a filing recorded by mistake. */
    @Transactional
    public Declaration unfile(YearMonth month, String kind) {
        requireKind(kind);
        int n = jdbc.sql("delete from declaration where month = ? and kind = ?").params(month.atDay(1), kind).update();
        if (n == 0) {
            throw new NotFoundException("The " + kind + " declaration for " + month + " is not recorded.");
        }
        audit.record("declaration.unfiled", "declaration", month + " " + kind, Map.of());
        return declaration(month, kind);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
