package cd.hrpay.payroll;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The parallel run: the old payroll's figures for a month (a CSV saved from the old software or a spreadsheet, one
 * row per person by registration number) set against HR Pay's payslips, person by person, with the differences.
 */
@Service
public class ParallelRun {

    public record Figures(BigDecimal gross, BigDecimal cnss, BigDecimal tax, BigDecimal net) {}

    public record Row(String registrationNo, String name, String currency, Figures ours, Figures theirs,
                      Figures difference, String status) {}

    public record Comparison(String month, String runStatus, BigDecimal tolerance, int matching, int different,
                             int onlyOurs, int onlyTheirs, Figures oursTotal, Figures theirsTotal, List<Row> rows,
                             List<String> warnings) {}

    /** Column names the file may use, in English or French. */
    private static final Map<String, List<String>> COLUMNS = Map.of(
            "registration", List.of("registration", "registration no", "registration number", "matricule", "employee no", "id"),
            "gross", List.of("gross", "brut", "salaire brut", "gross pay"),
            "cnss", List.of("cnss", "cnss employee", "cnss salarie", "cnss salarié", "inss", "social security"),
            "tax", List.of("ipr", "its", "tax", "impot", "impôt", "income tax"),
            "net", List.of("net", "net pay", "net a payer", "net à payer", "salaire net"));

    private final JdbcClient jdbc;

    public ParallelRun(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record Ours(String registrationNo, String name, String currency, BigDecimal gross, BigDecimal cnss,
                        BigDecimal tax, BigDecimal net) {}

    public Comparison compare(YearMonth month, String csv, BigDecimal tolerance) {
        var run = jdbc.sql("select id, status from payroll_run where month = ?").param(month.atDay(1))
                .query((rs, i) -> new Object[] {rs.getLong(1), rs.getString(2)}).optional()
                .orElseThrow(() -> new NotFoundException("No payroll for %s yet.".formatted(month)));
        BigDecimal tol = tolerance == null ? BigDecimal.ONE : tolerance.abs();
        Map<String, Ours> ours = new LinkedHashMap<>();
        jdbc.sql("""
                select u.registration_no, person_name(u.first_name, u.middle_name, u.last_name) as name, trim(min(p.currency)) as currency,
                       sum(p.gross) as gross,
                       coalesce(sum((select sum(l.amount) from payslip_line l where l.payslip_id = p.id and l.element_code = 'CNSS_EMPLOYEE')), 0) as cnss,
                       coalesce(sum((select sum(l.amount) from payslip_line l where l.payslip_id = p.id and l.element_code = 'IPR')), 0) as tax,
                       sum(p.net) as net
                from payslip p join app_user u on u.id = p.user_id
                where p.run_id = ? and (p.kind = 'REGULAR' or p.number is not null)
                group by u.registration_no, u.first_name, u.middle_name, u.last_name order by u.last_name, u.first_name""")
                .param(run[0]).query(Ours.class).list()
                .forEach(o -> ours.put(norm(o.registrationNo()), o));

        List<String> warnings = new ArrayList<>();
        Map<String, Figures> theirs = parse(csv, warnings);
        List<Row> rows = new ArrayList<>();
        int matching = 0;
        int different = 0;
        int onlyOurs = 0;
        int onlyTheirs = 0;
        for (Ours o : ours.values()) {
            Figures mine = new Figures(o.gross(), o.cnss(), o.tax(), o.net());
            Figures other = theirs.remove(norm(o.registrationNo()));
            if (other == null) {
                rows.add(new Row(o.registrationNo(), o.name(), o.currency(), mine, null, null, "ONLY_OURS"));
                onlyOurs++;
                continue;
            }
            Figures diff = new Figures(sub(mine.gross(), other.gross()), sub(mine.cnss(), other.cnss()),
                    sub(mine.tax(), other.tax()), sub(mine.net(), other.net()));
            boolean same = within(diff.gross(), tol) && within(diff.cnss(), tol) && within(diff.tax(), tol) && within(diff.net(), tol);
            rows.add(new Row(o.registrationNo(), o.name(), o.currency(), mine, other, diff, same ? "MATCH" : "DIFFERENT"));
            if (same) {
                matching++;
            } else {
                different++;
            }
        }
        for (Map.Entry<String, Figures> t : theirs.entrySet()) {
            rows.add(new Row(t.getKey(), null, null, null, t.getValue(), null, "ONLY_THEIRS"));
            onlyTheirs++;
        }
        if (!"CLOSED".equals(run[1])) {
            warnings.add(cd.hrpay.i18n.Texts.t("The %s payroll is not closed yet: these amounts can still change.", month));
        }
        return new Comparison(month.toString(), (String) run[1], tol, matching, different, onlyOurs, onlyTheirs,
                total(rows, true), total(rows, false), rows, warnings);
    }

    private static Figures total(List<Row> rows, boolean ours) {
        BigDecimal g = BigDecimal.ZERO, c = BigDecimal.ZERO, t = BigDecimal.ZERO, n = BigDecimal.ZERO;
        for (Row r : rows) {
            Figures f = ours ? r.ours() : r.theirs();
            if (f == null) {
                continue;
            }
            g = g.add(nz(f.gross()));
            c = c.add(nz(f.cnss()));
            t = t.add(nz(f.tax()));
            n = n.add(nz(f.net()));
        }
        return new Figures(g, c, t, n);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal sub(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? null : a.subtract(b);
    }

    private static boolean within(BigDecimal diff, BigDecimal tol) {
        return diff == null || diff.abs().compareTo(tol) <= 0;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    /** Reads the file: a header row naming the columns, then one row per person. */
    static Map<String, Figures> parse(String csv, List<String> warnings) {
        if (csv == null || csv.isBlank()) {
            throw new BusinessRuleException("The file is empty.");
        }
        String[] lines = csv.replace("﻿", "").split("\\r?\\n");
        String header = lines[0];
        char sep = header.chars().filter(ch -> ch == ';').count() >= 1 ? ';'
                : header.chars().filter(ch -> ch == '\t').count() >= 1 ? '\t' : ',';
        List<String> head = split(header, sep);
        Map<String, Integer> at = new HashMap<>();
        for (int i = 0; i < head.size(); i++) {
            String h = head.get(i).trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace(".", "");
            for (Map.Entry<String, List<String>> c : COLUMNS.entrySet()) {
                if (!at.containsKey(c.getKey()) && c.getValue().contains(h)) {
                    at.put(c.getKey(), i);
                }
            }
        }
        if (!at.containsKey("registration") || !at.containsKey("net")) {
            throw new BusinessRuleException("The first row must name the columns: registration number and net at least (gross, CNSS and IPR if you have them).");
        }
        Map<String, Figures> out = new LinkedHashMap<>();
        for (int n = 1; n < lines.length; n++) {
            if (lines[n].isBlank()) {
                continue;
            }
            List<String> cells = split(lines[n], sep);
            String reg = cell(cells, at.get("registration"));
            if (reg == null || reg.isBlank()) {
                continue;
            }
            try {
                Figures f = new Figures(number(cell(cells, at.get("gross")), sep), number(cell(cells, at.get("cnss")), sep),
                        number(cell(cells, at.get("tax")), sep), number(cell(cells, at.get("net")), sep));
                if (out.put(norm(reg), f) != null) {
                    warnings.add(cd.hrpay.i18n.Texts.t("Row %s: %s appears twice; the last row is used.", n + 1, reg));
                }
            } catch (NumberFormatException e) {
                warnings.add(cd.hrpay.i18n.Texts.t("Row %s: an amount cannot be read, the row is left out.", n + 1));
            }
        }
        return out;
    }

    private static String cell(List<String> cells, Integer i) {
        return i == null || i >= cells.size() ? null : cells.get(i).trim();
    }

    /** Numbers as spreadsheets write them: 1 234,56 or 1,234.56 or 1234.56. */
    static BigDecimal number(String s, char sep) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String v = s.replaceAll("[\\s  ]", "").replaceAll("[^0-9,.\\-]", "");
        int comma = v.lastIndexOf(',');
        int dot = v.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            v = comma > dot ? v.replace(".", "").replace(',', '.') : v.replace(",", "");
        } else if (comma >= 0) {
            v = v.indexOf(',') == comma && v.length() - comma - 1 != 3 ? v.replace(',', '.') : v.replace(",", "");
        }
        return new BigDecimal(v);
    }

    private static List<String> split(String line, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == sep && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
