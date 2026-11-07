package cd.hrpay.integration;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.payment.Company;
import cd.hrpay.payment.Payments;
import cd.hrpay.payroll.Accounting;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Layouts of the files the customer's own tools import: the accounting journal for the accounting software and the
 * payment files for the bank or mobile money operator. A layout names the columns in order (each a field of HR Pay
 * or a fixed text), the separator, the decimal mark, the date format and the character set.
 */
@Service
public class ExportLayouts {

    public record Column(String field, String header, String value) {}

    public record Layout(long id, String target, String name, List<Column> columns, String separator, boolean decimalComma,
                         String dateFormat, boolean headerRow, String encoding, String extension, String createdBy,
                         OffsetDateTime createdAt) {}

    public record File(String name, byte[] content, String contentType) {}

    /** The fields of each kind of file, with their description. */
    public static final Map<String, Map<String, String>> FIELDS = fields();

    private static Map<String, Map<String, String>> fields() {
        Map<String, String> accounting = new LinkedHashMap<>();
        accounting.put("journal", "Journal code");
        accounting.put("date", "Entry date (last day of the month)");
        accounting.put("reference", "Reference");
        accounting.put("line_no", "Line number");
        accounting.put("account", "Account");
        accounting.put("label", "Label");
        accounting.put("cost_centre", "Cost centre");
        accounting.put("debit", "Debit");
        accounting.put("credit", "Credit");
        accounting.put("amount", "Amount, debit positive and credit negative");
        accounting.put("direction", "D or C");
        accounting.put("unsigned_amount", "Amount without sign");
        accounting.put("currency", "Currency");
        accounting.put("month", "Month (YYYY-MM)");
        accounting.put("text", "Fixed text");
        Map<String, String> payment = new LinkedHashMap<>();
        payment.put("line_no", "Line number");
        payment.put("registration_no", "Registration no.");
        payment.put("beneficiary", "Beneficiary");
        payment.put("provider", "Bank or operator");
        payment.put("account", "Account or phone number");
        payment.put("amount", "Amount");
        payment.put("currency", "Currency");
        payment.put("narration", "Narration (Salary YYYY-MM)");
        payment.put("month", "Month (YYYY-MM)");
        payment.put("date", "Payment date");
        payment.put("ordering_account", "Company's account");
        payment.put("direction", "Direction of the person");
        payment.put("unit", "Unit of the person");
        payment.put("text", "Fixed text");
        Map<String, Map<String, String>> m = new LinkedHashMap<>();
        m.put("ACCOUNTING", accounting);
        m.put("BANK", payment);
        m.put("MOBILE_MONEY", payment);
        m.put("CASH", payment);
        return m;
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AuditService audit;
    private final Accounting accounting;
    private final Payments payments;
    private final Company company;

    public ExportLayouts(JdbcClient jdbc, JsonMapper json, AuditService audit, Accounting accounting, Payments payments,
                         Company company) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.accounting = accounting;
        this.payments = payments;
        this.company = company;
    }

    private Layout map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Layout(rs.getLong("id"), rs.getString("target"), rs.getString("name"),
                json.readValue(rs.getString("columns"), new TypeReference<List<Column>>() {}), rs.getString("separator"),
                rs.getBoolean("decimal_comma"), rs.getString("date_format"), rs.getBoolean("header_row"), rs.getString("encoding"),
                rs.getString("extension"), rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class));
    }

    public List<Layout> list(String target) {
        String sql = "select * from export_layout" + (target == null ? "" : " where target = ?") + " order by target, name";
        var spec = jdbc.sql(sql);
        if (target != null) {
            spec = spec.param(target);
        }
        return spec.query((rs, i) -> map(rs)).list();
    }

    public Layout find(long id) {
        return jdbc.sql("select * from export_layout where id = ?").param(id).query((rs, i) -> map(rs)).optional()
                .orElseThrow(() -> new NotFoundException("Layout not found."));
    }

    @Transactional
    public Layout save(Long id, Layout l) {
        Map<String, String> fields = FIELDS.get(l.target());
        if (fields == null) {
            throw new BusinessRuleException("Unknown file kind %s.".formatted(l.target()));
        }
        if (l.columns() == null || l.columns().isEmpty()) {
            throw new BusinessRuleException("Add at least one column.");
        }
        for (Column c : l.columns()) {
            if (!fields.containsKey(c.field())) {
                throw new BusinessRuleException("Unknown field %s.".formatted(c.field()));
            }
        }
        String sep = switch (l.separator()) {
            case ";", ",", "|", "\t", "TAB" -> l.separator().equals("TAB") ? "\t" : l.separator();
            default -> throw new BusinessRuleException("The separator is ; , | or tab.");
        };
        try {
            DateTimeFormatter.ofPattern(l.dateFormat());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("The date format cannot be read: write it like dd/MM/yyyy.");
        }
        if (!List.of("UTF-8", "WINDOWS-1252").contains(l.encoding()) || !List.of("csv", "txt").contains(l.extension())) {
            throw new BusinessRuleException("The characters are UTF-8 or Windows-1252, the file .csv or .txt.");
        }
        String cols = json.writeValueAsString(l.columns());
        long saved;
        if (id == null) {
            saved = jdbc.sql("""
                    insert into export_layout (target, name, columns, separator, decimal_comma, date_format, header_row, encoding,
                                               extension, created_by)
                    values (?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(l.target(), l.name().trim(), cols, sep, l.decimalComma(), l.dateFormat(), l.headerRow(), l.encoding(),
                            l.extension(), CurrentUser.email()).query(Long.class).single();
        } else {
            find(id);
            jdbc.sql("""
                    update export_layout set target = ?, name = ?, columns = cast(? as jsonb), separator = ?, decimal_comma = ?,
                        date_format = ?, header_row = ?, encoding = ?, extension = ? where id = ?""")
                    .params(l.target(), l.name().trim(), cols, sep, l.decimalComma(), l.dateFormat(), l.headerRow(), l.encoding(),
                            l.extension(), id).update();
            saved = id;
        }
        audit.record("export_layout.saved", "export_layout", saved, Map.of("name", l.name().trim(), "target", l.target()));
        return find(saved);
    }

    @Transactional
    public void delete(long id) {
        find(id);
        jdbc.sql("delete from export_layout where id = ?").param(id).update();
        audit.record("export_layout.deleted", "export_layout", id, Map.of());
    }

    // ---- writing ----------------------------------------------------------------------------------------------

    public File journal(long layoutId, YearMonth month, boolean byCostCentre) {
        Layout l = find(layoutId);
        if (!l.target().equals("ACCOUNTING")) {
            throw new BusinessRuleException("This layout is not for the accounting entries.");
        }
        Accounting.Journal j = accounting.journal(month, byCostCentre);
        List<Map<String, Object>> rows = new ArrayList<>();
        int n = 0;
        String label = Texts.t("Payroll %s", month);
        for (Accounting.Entry e : j.entries()) {
            Map<String, Object> r = new LinkedHashMap<>();
            BigDecimal debit = e.debit() == null ? BigDecimal.ZERO : e.debit();
            BigDecimal credit = e.credit() == null ? BigDecimal.ZERO : e.credit();
            r.put("journal", j.journal());
            r.put("date", j.date());
            r.put("reference", j.reference());
            r.put("line_no", ++n);
            r.put("account", e.account());
            r.put("label", label + " · " + e.label());
            r.put("cost_centre", e.costCentre());
            r.put("debit", e.debit());
            r.put("credit", e.credit());
            r.put("amount", debit.subtract(credit));
            r.put("direction", debit.signum() != 0 ? "D" : "C");
            r.put("unsigned_amount", debit.signum() != 0 ? debit : credit);
            r.put("currency", j.currency());
            r.put("month", month.toString());
            rows.add(r);
        }
        accounting.exported(month);
        return write(l, rows, "payroll-entries-" + month);
    }

    public File payment(long layoutId, long batchId) {
        Layout l = find(layoutId);
        Payments.BatchView v = payments.batch(batchId);
        Payments.Batch b = v.batch();
        if (!l.target().equals(b.method())) {
            throw new BusinessRuleException("This layout is for another kind of payment file.");
        }
        String ordering = company.get().bankAccount();
        List<Map<String, Object>> rows = new ArrayList<>();
        int n = 0;
        for (Payments.Line line : v.lines()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("line_no", ++n);
            r.put("registration_no", line.registrationNo());
            r.put("beneficiary", line.beneficiary());
            r.put("provider", b.provider());
            r.put("account", line.account());
            r.put("amount", line.amount());
            r.put("currency", line.currency());
            r.put("narration", Texts.t("Salary %s", v.month()));
            r.put("month", v.month());
            r.put("date", b.paidOn() != null ? b.paidOn() : LocalDate.now());
            r.put("ordering_account", ordering);
            r.put("direction", line.directionName());
            r.put("unit", line.unitName());
            rows.add(r);
        }
        audit.record("payment.file_downloaded", "payment_batch", batchId, Map.of("lines", v.lines().size(), "layout", l.name()));
        return write(l, rows, "salaries-" + v.month() + "-" + b.provider().replaceAll("[^A-Za-z0-9]+", "-").toLowerCase() + "-" + b.id());
    }

    File write(Layout l, List<Map<String, Object>> rows, String baseName) {
        StringBuilder out = new StringBuilder();
        if (l.headerRow()) {
            out.append(String.join(l.separator(), l.columns().stream()
                    .map(c -> cell(c.header() == null || c.header().isBlank() ? c.field() : c.header(), l.separator())).toList()))
                    .append("\r\n");
        }
        DateTimeFormatter dates = DateTimeFormatter.ofPattern(l.dateFormat());
        for (Map<String, Object> r : rows) {
            List<String> cells = new ArrayList<>();
            for (Column c : l.columns()) {
                Object v = c.field().equals("text") ? c.value() : r.get(c.field());
                String s;
                if (v == null) {
                    s = "";
                } else if (v instanceof BigDecimal d) {
                    s = d.setScale(2, RoundingMode.HALF_UP).toPlainString();
                    if (l.decimalComma()) {
                        s = s.replace('.', ',');
                    }
                } else if (v instanceof LocalDate d) {
                    s = d.format(dates);
                } else {
                    s = v.toString();
                }
                cells.add(cell(s, l.separator()));
            }
            out.append(String.join(l.separator(), cells)).append("\r\n");
        }
        Charset charset = Charset.forName(l.encoding().equals("WINDOWS-1252") ? "windows-1252" : "UTF-8");
        String type = (l.extension().equals("txt") ? "text/plain" : "text/csv") + "; charset=" + charset.name();
        return new File(baseName + "." + l.extension(), out.toString().getBytes(charset), type);
    }

    /** Quoted when it holds the separator, a quote or a line break; a cell starting like a formula gets a quote mark. */
    private static String cell(String s, String sep) {
        String v = s;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("-?[0-9]+([.,][0-9]+)?")) {
            v = "'" + v;
        }
        if (v.contains(sep) || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
