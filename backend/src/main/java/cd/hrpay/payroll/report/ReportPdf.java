package cd.hrpay.payroll.report;

import cd.hrpay.i18n.Texts;
import cd.hrpay.payment.Company;
import cd.hrpay.payment.Letterhead;
import cd.hrpay.payroll.report.Report.Column;
import cd.hrpay.payroll.report.Report.Row;
import cd.hrpay.payroll.report.Report.Table;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfGState;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfTemplate;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * A payroll report as a PDF: the company's letterhead, landscape pages, each table with its header repeated on every
 * page, "page x of y", and PROVISIONAL across every page while the month is not closed. A table with a fixed number of
 * rows per page (the livre de paie) ends each page with its totals and the totals carried forward.
 */
@Service
public class ReportPdf {

    private static final Color SHADE = new Color(236, 240, 245);
    private static final Color TOTAL = new Color(222, 228, 236);
    private static final Color RULE = new Color(180, 188, 198);
    private static final Color MUTED = new Color(90, 98, 110);

    private final Letterhead letterhead;
    private final Company company;
    private final JdbcClient jdbc;

    public ReportPdf(Letterhead letterhead, Company company, JdbcClient jdbc) {
        this.letterhead = letterhead;
        this.company = company;
        this.jdbc = jdbc;
    }

    public byte[] render(Report report) {
        boolean large = report.tables().stream().anyMatch(Table::large);
        Rectangle size = large ? PageSize.A3.rotate() : PageSize.A4.rotate();
        Letterhead.Head head = letterhead.read();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(size, 28, 28, 26, Letterhead.Head.bottomMargin());
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        head.footer(writer);
        writer.setPageEvent(new Pages(report.provisional()));
        doc.addTitle(report.title());
        doc.open();
        head.draw(doc);

        Paragraph title = new Paragraph(report.title(), new Font(Font.HELVETICA, 14, Font.BOLD));
        title.setSpacingBefore(8);
        doc.add(title);
        if (report.subtitle() != null) {
            doc.add(new Paragraph(report.subtitle(), new Font(Font.HELVETICA, 8.5f, Font.NORMAL, MUTED)));
        }
        if ("livre".equals(report.code())) {
            doc.add(employer(report));
        }
        for (Table t : report.tables()) {
            if (t.rowsPerPage() > 0) {
                paged(doc, t);
            } else {
                Paragraph h = new Paragraph(t.title(), new Font(Font.HELVETICA, 10, Font.BOLD));
                h.setSpacingBefore(10);
                h.setSpacingAfter(4);
                doc.add(h);
                PdfPTable table = table(t, t.rows());
                doc.add(table);
            }
        }
        if (!report.notes().isEmpty()) {
            Paragraph notes = new Paragraph();
            notes.setSpacingBefore(10);
            for (String n : report.notes()) {
                notes.add(new Phrase(n + "\n", new Font(Font.HELVETICA, 7.5f, Font.NORMAL, MUTED)));
            }
            doc.add(notes);
        }
        doc.close();
        return out.toByteArray();
    }

    /** The head of the livre de paie model: the employer's social security number, name and branch of activity. */
    private PdfPTable employer(Report report) {
        Company.Details d = company.get();
        String activity = jdbc.sql("select activity from company where id = current_company_id()").query(String.class).optional().orElse(null);
        PdfPTable t = new PdfPTable(new float[] {1, 1, 1, 1});
        t.setWidthPercentage(100);
        t.setSpacingBefore(6);
        Font label = new Font(Font.HELVETICA, 7, Font.NORMAL, MUTED);
        Font value = new Font(Font.HELVETICA, 9, Font.BOLD);
        String[][] cells = {
                {Texts.t("Employer's social security no."), d.cnssNumber() == null ? "—" : d.cnssNumber()},
                {Texts.t("Employer"), d.name() == null ? "—" : d.name()},
                {Texts.t("Branch of activity"), activity == null || activity.isBlank() ? "—" : activity},
                {Texts.t("Pay sheet of"), report.tables().isEmpty() ? "" : report.tables().getFirst().title()}};
        for (String[] c : cells) {
            PdfPCell cell = new PdfPCell();
            cell.setBorderColor(RULE);
            cell.addElement(new Paragraph(c[0], label));
            cell.addElement(new Paragraph(c[1], value));
            cell.setPaddingBottom(5);
            t.addCell(cell);
        }
        return t;
    }

    /** Rows in pages of a fixed size, each page closed by its totals and what is carried to the next. */
    private void paged(Document doc, Table t) {
        List<Row> rows = t.rows();
        int n = Math.max(1, t.rowsPerPage());
        BigDecimal[] carried = zeros(t.columns().size());
        for (int start = 0; start < Math.max(1, rows.size()); start += n) {
            List<Row> chunk = rows.subList(Math.min(start, rows.size()), Math.min(rows.size(), start + n));
            BigDecimal[] page = zeros(t.columns().size());
            for (Row r : chunk) {
                add(page, t.columns(), r);
            }
            List<Row> all = new ArrayList<>(chunk);
            all.add(sumRow(t.columns(), page, Texts.t("Page total")));
            for (int i = 0; i < carried.length; i++) {
                carried[i] = carried[i].add(page[i]);
            }
            boolean last = start + n >= rows.size();
            all.add(sumRow(t.columns(), carried, last ? Texts.t("Grand total") : Texts.t("Carried forward")));
            PdfPTable table = table(t, all);
            table.setSpacingBefore(6);
            doc.add(table);
            if (!last) {
                doc.newPage();
            }
        }
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] z = new BigDecimal[n];
        java.util.Arrays.fill(z, BigDecimal.ZERO);
        return z;
    }

    private static void add(BigDecimal[] sums, List<Column> cols, Row r) {
        for (int i = 0; i < cols.size() && i < r.cells().size(); i++) {
            if (!cols.get(i).type().equals("TEXT") && r.cells().get(i) instanceof Number v) {
                sums[i] = sums[i].add(new BigDecimal(v.toString()));
            }
        }
    }

    private static Row sumRow(List<Column> cols, BigDecimal[] sums, String label) {
        List<Object> cells = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            // Rates and monthly salaries do not add up.
            String l = cols.get(i).label();
            boolean skip = cols.get(i).type().equals("TEXT") || l.startsWith("5 ") || l.startsWith("9 ") || l.startsWith("15 ")
                    || l.startsWith("18 ") || l.startsWith("29 ");
            cells.add(i == 1 ? label : skip ? "" : sums[i]);
        }
        return new Row(cells, "TOTAL", 0, null);
    }

    private PdfPTable table(Table t, List<Row> rows) {
        int n = t.columns().size();
        float size = n > 24 ? 5.6f : n > 14 ? 6.5f : n > 9 ? 7.5f : 8.5f;
        Font head = new Font(Font.HELVETICA, size, Font.BOLD);
        Font normal = new Font(Font.HELVETICA, size, Font.NORMAL);
        Font bold = new Font(Font.HELVETICA, size, Font.BOLD);
        float[] widths = new float[n];
        for (int i = 0; i < n; i++) {
            Column c = t.columns().get(i);
            String l = c.label();
            widths[i] = l.startsWith("1 ") ? 1f
                    : l.equals(Texts.t("Currency")) ? 0.7f
                    : c.type().equals("TEXT") ? (i == 0 || l.startsWith("2 ") || l.startsWith("33 ") ? 2.2f : 1.8f)
                    : c.type().equals("NUMBER") ? 0.6f : 1.5f;
        }
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (Column c : t.columns()) {
            PdfPCell cell = new PdfPCell(new Phrase(c.label(), head));
            cell.setBackgroundColor(SHADE);
            cell.setBorderColor(RULE);
            cell.setHorizontalAlignment(c.type().equals("TEXT") ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            cell.setVerticalAlignment(Element.ALIGN_BOTTOM);
            cell.setPadding(3);
            table.addCell(cell);
        }
        if (rows.isEmpty()) {
            PdfPCell cell = new PdfPCell(new Phrase(Texts.t("None."), normal));
            cell.setColspan(n);
            cell.setBorderColor(RULE);
            cell.setPadding(4);
            table.addCell(cell);
        }
        for (Row r : rows) {
            boolean strong = r.strong();
            for (int i = 0; i < n; i++) {
                Object v = i < r.cells().size() ? r.cells().get(i) : "";
                Column c = t.columns().get(i);
                PdfPCell cell = new PdfPCell(new Phrase(format(v, c), strong ? bold : normal));
                cell.setBorderColor(RULE);
                cell.setPadding(2.5f);
                cell.setHorizontalAlignment(c.type().equals("TEXT") ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
                if (i == 0 && r.depth() > 0) {
                    cell.setPaddingLeft(2.5f + 9 * r.depth());
                }
                if ("TOTAL".equals(r.style())) {
                    cell.setBackgroundColor(TOTAL);
                } else if ("SUBTOTAL".equals(r.style()) || "GROUP".equals(r.style())) {
                    cell.setBackgroundColor(SHADE);
                }
                table.addCell(cell);
            }
        }
        return table;
    }

    static String format(Object v, Column c) {
        if (v == null) {
            return "";
        }
        if (v instanceof Number num && !c.type().equals("TEXT")) {
            BigDecimal b = new BigDecimal(num.toString());
            // A count in an amount column (the number of employees) stays a whole number.
            boolean money = c.type().equals("MONEY") && !(v instanceof Integer || v instanceof Long);
            DecimalFormatSymbols sym = Texts.french() ? DecimalFormatSymbols.getInstance(Locale.FRANCE) : DecimalFormatSymbols.getInstance(Locale.UK);
            if (Texts.french()) {
                sym.setGroupingSeparator(' ');
            }
            DecimalFormat f = new DecimalFormat(money ? "#,##0.00" : "#,##0.##", sym);
            return f.format(money ? b.setScale(2, RoundingMode.HALF_UP) : b);
        }
        return v.toString();
    }

    /** "Page x of y" at the foot of each page, and PROVISIONAL across it while the month is open. */
    private static final class Pages extends PdfPageEventHelper {
        private final boolean provisional;
        private PdfTemplate total;
        private BaseFont font;

        Pages(boolean provisional) {
            this.provisional = provisional;
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            total = writer.getDirectContent().createTemplate(40, 12);
            try {
                font = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, false);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void onEndPage(PdfWriter writer, Document doc) {
            PdfContentByte cb = writer.getDirectContent();
            String text = Texts.t("Page %s of", writer.getPageNumber()) + " ";
            float x = doc.right() - 60;
            float y = doc.bottom() - 40;
            cb.beginText();
            cb.setFontAndSize(font, 7.5f);
            cb.setColorFill(MUTED);
            cb.setTextMatrix(x, y);
            cb.showText(text);
            cb.endText();
            cb.addTemplate(total, x + font.getWidthPoint(text, 7.5f), y);
            if (provisional) {
                PdfContentByte under = writer.getDirectContentUnder();
                under.saveState();
                PdfGState g = new PdfGState();
                g.setFillOpacity(0.12f);
                under.setGState(g);
                ColumnText.showTextAligned(under, Element.ALIGN_CENTER,
                        new Phrase(Texts.t("PROVISIONAL"), new Font(Font.HELVETICA, 90, Font.BOLD, new Color(170, 60, 0))),
                        (doc.left() + doc.right()) / 2, (doc.top() + doc.bottom()) / 2, 25);
                under.restoreState();
            }
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            total.beginText();
            total.setFontAndSize(font, 7.5f);
            total.setColorFill(MUTED);
            total.setTextMatrix(0, 0);
            total.showText(String.valueOf(writer.getPageNumber() - 1));
            total.endText();
        }
    }
}
