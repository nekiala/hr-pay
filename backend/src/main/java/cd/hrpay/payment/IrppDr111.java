package cd.hrpay.payment;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The DGI's monthly IRPP declaration for DR Congo, in the layout of its import template (IRPP DR 111): one sheet named
 * IRPPDR111, a header row of nineteen columns, then one row per employee. Amounts are in CDF at the payroll's rate.
 */
@Service
public class IrppDr111 {

    public static final List<String> COLUMNS = List.of("nif", "redevable", "exercice", "mois", "MatriculeEmploye", "ncnss",
            "Nom", "postnom", "Prenom", "TypeTravailleur", "Commune_Territoire", "PeriodeCotisee", "MontantCotise",
            "NbreJourDeTravail", "NbreDheureDetravail", "CategorieSalarie", "MontantBrutImposable", "nifemploye",
            "MontantRetIRPP");

    public record Line(String registrationNo, String cnssNumber, String lastName, String postnom, String firstName,
                       String workerType, String commune, String period, BigDecimal cnssCdf, int days, BigDecimal hours,
                       String category, BigDecimal taxableCdf, String taxNumber, BigDecimal irppCdf) {}

    private record Person(long userId, String lastName, String firstName, String postnom, String commune, String category,
                          boolean expatriate, int days, int minutes) {}

    private final Declarations declarations;
    private final JdbcClient jdbc;

    public IrppDr111(Declarations declarations, JdbcClient jdbc) {
        this.declarations = declarations;
        this.jdbc = jdbc;
    }

    public List<Line> lines(YearMonth month) {
        Declarations.Declaration irpp = declarations.declaration(month, "IPR");
        Map<Long, Declarations.Row> cnss = declarations.declaration(month, "CNSS").rows().stream()
                .collect(Collectors.toMap(Declarations.Row::userId, Function.identity()));
        Map<Long, Person> people = jdbc.sql("""
                select p.user_id, u.last_name, u.first_name, e.postnom, e.commune, k.name as category,
                       bool_or((p.snapshot ->> 'expatriate')::boolean) filter (where p.kind = 'REGULAR') as expatriate,
                       coalesce(sum(p.days_worked) filter (where p.kind = 'REGULAR'), 0) as days,
                       coalesce(sum(p.paid_minutes) filter (where p.kind = 'REGULAR'), 0) as minutes
                from payslip p join payroll_run r on r.id = p.run_id join app_user u on u.id = p.user_id
                left join employee_profile e on e.user_id = u.id left join grade_category k on k.id = u.grade_category_id
                where r.month = ? and (p.kind = 'REGULAR' or p.number is not null)
                group by p.user_id, u.last_name, u.first_name, e.postnom, e.commune, k.name""")
                .param(month.atDay(1))
                .query((rs, i) -> new Person(rs.getLong("user_id"), rs.getString("last_name"), rs.getString("first_name"),
                        rs.getString("postnom"), rs.getString("commune"), rs.getString("category"),
                        rs.getBoolean("expatriate"), rs.getInt("days"), rs.getInt("minutes")))
                .list().stream().collect(Collectors.toMap(Person::userId, Function.identity()));
        String period = String.format("%02d/%d", month.getMonthValue(), month.getYear());
        return irpp.rows().stream().map(r -> {
            Person p = people.get(r.userId());
            Declarations.Row c = cnss.get(r.userId());
            return new Line(r.registrationNo(), c == null ? null : c.idNumber(), p == null ? null : p.lastName(),
                    p == null ? null : p.postnom(), p == null ? null : p.firstName(),
                    p != null && p.expatriate() ? "EXPATRIE" : "NATIONAL", p == null ? null : p.commune(), period,
                    c == null ? BigDecimal.ZERO.setScale(2) : c.employeeCdf(), p == null ? 0 : p.days(),
                    p == null ? BigDecimal.ZERO : BigDecimal.valueOf(p.minutes()).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP),
                    p == null ? null : p.category(), r.baseCdf(), r.idNumber(), r.employeeCdf());
        }).toList();
    }

    /** The workbook (Excel 97-2003, as the DGI's template). */
    public void write(YearMonth month, String employerNif, String employerName, OutputStream out) throws IOException {
        List<Line> lines = lines(month);
        try (HSSFWorkbook wb = new HSSFWorkbook()) {
            var sheet = wb.createSheet("IRPPDR111");
            Row head = sheet.createRow(0);
            for (int i = 0; i < COLUMNS.size(); i++) {
                head.createCell(i).setCellValue(COLUMNS.get(i));
            }
            int n = 1;
            for (Line l : lines) {
                Row row = sheet.createRow(n++);
                Object[] cells = {employerNif, employerName, month.getYear(), month.getMonthValue(), l.registrationNo(),
                        l.cnssNumber(), l.lastName(), l.postnom(), l.firstName(), l.workerType(), l.commune(), l.period(),
                        l.cnssCdf(), l.days(), l.hours(), l.category(), l.taxableCdf(), l.taxNumber(), l.irppCdf()};
                for (int i = 0; i < cells.length; i++) {
                    Object v = cells[i];
                    var cell = row.createCell(i);
                    if (v instanceof BigDecimal b) {
                        cell.setCellValue(b.doubleValue());
                    } else if (v instanceof Integer x) {
                        cell.setCellValue(x);
                    } else if (v != null) {
                        cell.setCellValue(v.toString());
                    }
                }
            }
            wb.write(out);
        }
    }
}
