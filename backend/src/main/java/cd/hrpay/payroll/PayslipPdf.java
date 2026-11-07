package cd.hrpay.payroll;

import cd.hrpay.payment.Company;
import cd.hrpay.payment.YearSummaries;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The payslip as a PDF document: employer and employee details, earnings, deductions, net pay, employer charges and
 * the totals of the year to date (closed months before this one, plus this one).
 */
@Service
public class PayslipPdf {

    public record YearToDate(int year, int months, String currency, BigDecimal gross, BigDecimal cnss, BigDecimal ipr,
                             BigDecimal net, boolean otherCurrencies) {}

    private static final Font TITLE = new Font(Font.HELVETICA, 14, Font.BOLD);
    private static final Font BOLD = new Font(Font.HELVETICA, 9, Font.BOLD);
    private static final Font NORMAL = new Font(Font.HELVETICA, 9, Font.NORMAL);
    private static final Font SMALL = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, new Color(90, 90, 90));
    private static final Font NOTICE = new Font(Font.HELVETICA, 9, Font.BOLD, new Color(170, 60, 0));
    private static final Color SHADE = new Color(236, 240, 245);
    private static final Map<String, String> PAYMENT = Map.of("BANK", "Bank transfer", "MOBILE_MONEY", "Mobile money", "CASH", "Cash");

    private final YearSummaries years;
    private final Company company;

    public PayslipPdf(YearSummaries years, Company company) {
        this.years = years;
        this.company = company;
    }

    /** Year-to-date totals in the payslip's currency: closed months of the year before this one, plus this one. */
    public YearToDate yearToDate(PayrollRuns.Payslip p) {
        String month = p.month().toString().substring(0, 7);
        List<YearSummaries.Month> before = years.employee(p.userId(), p.month().getYear()).months().stream()
                .filter(m -> m.month().compareTo(month) < 0).toList();
        List<YearSummaries.Month> same = before.stream().filter(m -> m.currency().equals(p.currency())).toList();
        BigDecimal cnss = amount(p, "CNSS_EMPLOYEE");
        BigDecimal ipr = amount(p, "IPR");
        return new YearToDate(p.month().getYear(), same.size() + 1, p.currency(),
                same.stream().map(YearSummaries.Month::gross).reduce(p.gross(), BigDecimal::add),
                same.stream().map(YearSummaries.Month::cnss).reduce(cnss, BigDecimal::add),
                same.stream().map(YearSummaries.Month::ipr).reduce(ipr, BigDecimal::add),
                same.stream().map(YearSummaries.Month::net).reduce(p.net(), BigDecimal::add),
                same.size() < before.size());
    }

    private static BigDecimal amount(PayrollRuns.Payslip p, String code) {
        return p.lines().stream().filter(l -> code.equals(l.elementCode())).map(PayrollRuns.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public byte[] render(PayrollRuns.Payslip p) {
        Map<String, Object> s = p.snapshot();
        Company.Details employer = company.get();
        YearToDate ytd = yearToDate(p);
        String cur = p.currency();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 36, 36);
        PdfWriter.getInstance(doc, bytes);
        doc.addTitle("Payslip " + p.month().toString().substring(0, 7) + " " + text(s.get("name")));
        doc.addCreator("HR Pay");
        doc.open();

        // Employer on the left, employee on the right.
        PdfPTable head = table(new float[] {1, 1});
        PdfPCell left = cell("", NORMAL, Element.ALIGN_LEFT, false);
        left.addElement(new Paragraph(text(employer.name()), BOLD));
        left.addElement(new Paragraph(text(employer.address()), NORMAL));
        left.addElement(new Paragraph(join("CNSS " + text(employer.cnssNumber()), "NIF " + text(employer.taxNumber()),
                "Id. Nat. " + text(employer.nationalId())), SMALL));
        PdfPCell right = cell("", NORMAL, Element.ALIGN_RIGHT, false);
        right.addElement(right(text(s.get("name")), BOLD));
        right.addElement(right(join(text(s.get("registrationNo")), text(s.get("grade"))), NORMAL));
        right.addElement(right(text(s.get("occupation")), NORMAL));
        if (s.get("unit") != null) {
            String dir = text(s.get("direction"));
            right.addElement(right(join(text(s.get("unit")), dir.equals(text(s.get("unit"))) ? "" : dir,
                    s.get("costCentre") == null ? "" : "CC " + text(s.get("costCentre"))), NORMAL));
        }
        head.addCell(left);
        head.addCell(right);
        doc.add(head);

        Paragraph title = new Paragraph("Payslip · " + p.month().format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)), TITLE);
        title.setSpacingBefore(10);
        doc.add(title);
        doc.add(new Paragraph(p.number() == null ? "Not numbered yet" : p.number(), SMALL));
        if (!"CLOSED".equals(p.runStatus())) {
            doc.add(new Paragraph("DRAFT: this payroll is " + p.runStatus().toLowerCase() + " and not yet closed.", NOTICE));
        }

        PdfPTable facts = table(new float[] {1, 1, 1});
        facts.setSpacingBefore(8);
        for (String f : List.of(
                "Contract: " + text(s.get("contractType")).replace('_', ' ').toLowerCase() + " since " + text(s.get("contractStart")),
                "CNSS no.: " + dash(s.get("cnssNumber")) + " · NIF: " + dash(s.get("taxNumber")),
                "Children in charge: " + text(s.get("childrenInCharge")) + " · dependants: " + text(s.get("dependantsInCharge")),
                "Hours paid: " + hours(p.paidMinutes()) + " of " + hours(p.scheduledMinutes()),
                "Days worked: " + p.daysWorked(),
                "Monthly basic: " + money(new BigDecimal(text(s.get("basicMonthly"))), cur))) {
            facts.addCell(cell(f, SMALL, Element.ALIGN_LEFT, false));
        }
        doc.add(facts);

        PdfPTable lines = table(new float[] {5, 2, 1.6f, 2.4f});
        lines.setSpacingBefore(10);
        lines.setHeaderRows(1);
        for (String h : List.of("Item", "Base", "Rate", "Amount")) {
            PdfPCell c = cell(h, BOLD, h.equals("Item") ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT, true);
            c.setBorder(Rectangle.BOTTOM);
            lines.addCell(c);
        }
        section(lines, "Earnings", p, "EARNING", p.gross(), "Gross pay");
        section(lines, "Deductions", p, "DEDUCTION", p.employeeDeductions(), "Total deductions");
        PdfPCell net = cell("Net pay", TITLE, Element.ALIGN_LEFT, true);
        net.setColspan(3);
        lines.addCell(net);
        lines.addCell(cell(money(p.net(), cur), TITLE, Element.ALIGN_RIGHT, true));
        doc.add(lines);

        String pay = PAYMENT.getOrDefault(text(s.get("paymentMethod")), text(s.get("paymentMethod")));
        if ("BANK".equals(s.get("paymentMethod")) && s.get("bankName") != null) {
            pay += ": " + text(s.get("bankName")) + " " + text(s.get("bankAccount"));
        } else if ("MOBILE_MONEY".equals(s.get("paymentMethod"))) {
            pay += ": " + text(s.get("mobileMoneyOperator")) + " " + text(s.get("mobileMoneyNumber"));
        }
        if (cur.equals("USD") && p.usdCdf() != null) {
            pay += " · net in CDF: " + money(p.net().multiply(p.usdCdf()).setScale(0, RoundingMode.HALF_UP), "CDF")
                    + " at the BCC rate of " + p.rateDate() + " ($1 = " + p.usdCdf().stripTrailingZeros().toPlainString() + " CDF)";
        }
        Paragraph paid = new Paragraph("Paid by " + pay, NORMAL);
        paid.setSpacingBefore(6);
        doc.add(paid);

        // Year to date.
        PdfPTable year = table(new float[] {3, 2, 2, 2, 2});
        year.setSpacingBefore(12);
        PdfPCell yh = cell("Year to date " + ytd.year() + " (" + ytd.months() + " month" + (ytd.months() == 1 ? "" : "s")
                + ", in " + ytd.currency() + ")", BOLD, Element.ALIGN_LEFT, true);
        yh.setColspan(5);
        year.addCell(yh);
        for (String h : List.of("", "Gross", "CNSS", "IPR", "Net")) {
            year.addCell(cell(h, BOLD, h.isEmpty() ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT, false));
        }
        year.addCell(cell("Total", NORMAL, Element.ALIGN_LEFT, false));
        for (BigDecimal v : List.of(ytd.gross(), ytd.cnss(), ytd.ipr(), ytd.net())) {
            year.addCell(cell(money(v, cur), NORMAL, Element.ALIGN_RIGHT, false));
        }
        doc.add(year);
        if (ytd.otherCurrencies()) {
            doc.add(new Paragraph("Months paid in another currency are not included above.", SMALL));
        }

        PdfPTable charges = table(new float[] {5, 2, 1.6f, 2.4f});
        charges.setSpacingBefore(12);
        PdfPCell ch = cell("Employer charges (not deducted from pay)", BOLD, Element.ALIGN_LEFT, true);
        ch.setColspan(4);
        charges.addCell(ch);
        for (PayrollRuns.Line l : p.lines()) {
            if (l.kind().equals("EMPLOYER_CHARGE") && l.amount().signum() > 0) {
                line(charges, l, cur);
            }
        }
        total(charges, "Total employer charges", p.employerCharges(), cur);
        doc.add(charges);

        Paragraph foot = new Paragraph("Taxable income for IPR: " + money(p.taxableBase(), cur) + " ("
                + money(p.taxableBaseCdf(), "CDF") + "). CNSS base: " + money(p.cnssBase(), cur) + ".", SMALL);
        foot.setSpacingBefore(8);
        doc.add(foot);
        doc.add(new Paragraph("Keep this payslip without time limit.", SMALL));
        doc.close();
        return bytes.toByteArray();
    }

    private void section(PdfPTable t, String title, PayrollRuns.Payslip p, String kind, BigDecimal total, String totalLabel) {
        PdfPCell h = cell(title, BOLD, Element.ALIGN_LEFT, true);
        h.setColspan(4);
        t.addCell(h);
        for (PayrollRuns.Line l : p.lines()) {
            if (l.kind().equals(kind)) {
                line(t, l, p.currency());
            }
        }
        total(t, totalLabel, total, p.currency());
    }

    private static void line(PdfPTable t, PayrollRuns.Line l, String cur) {
        t.addCell(cell(l.label(), NORMAL, Element.ALIGN_LEFT, false));
        t.addCell(cell(l.base() == null ? "" : number(l.base(), 2), NORMAL, Element.ALIGN_RIGHT, false));
        String rate = l.rate() == null ? ""
                : l.kind().equals("EARNING") && !"HOUSING".equals(l.elementCode()) ? number(l.rate(), 4) : number(l.rate(), 3) + "%";
        t.addCell(cell(rate, NORMAL, Element.ALIGN_RIGHT, false));
        t.addCell(cell(money(l.amount(), cur), NORMAL, Element.ALIGN_RIGHT, false));
    }

    private static void total(PdfPTable t, String label, BigDecimal amount, String cur) {
        PdfPCell c = cell(label, BOLD, Element.ALIGN_LEFT, false);
        c.setColspan(3);
        c.setBorder(Rectangle.TOP);
        t.addCell(c);
        PdfPCell a = cell(money(amount, cur), BOLD, Element.ALIGN_RIGHT, false);
        a.setBorder(Rectangle.TOP);
        t.addCell(a);
    }

    private static PdfPTable table(float[] widths) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        return t;
    }

    private static PdfPCell cell(String text, Font font, int align, boolean shaded) {
        PdfPCell c = new PdfPCell(new Phrase(new Chunk(text, font)));
        c.setBorder(Rectangle.NO_BORDER);
        c.setHorizontalAlignment(align);
        c.setPadding(3);
        if (shaded) {
            c.setBackgroundColor(SHADE);
        }
        return c;
    }

    private static Paragraph right(String text, Font font) {
        Paragraph p = new Paragraph(text, font);
        p.setAlignment(Element.ALIGN_RIGHT);
        return p;
    }

    private static String text(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String dash(Object v) {
        return v == null || v.toString().isBlank() ? "—" : v.toString();
    }

    private static String join(String... parts) {
        return String.join(" · ", java.util.Arrays.stream(parts).filter(x -> x != null && !x.isBlank()).toList());
    }

    private static String hours(int minutes) {
        return minutes / 60 + " h " + String.format("%02d", minutes % 60);
    }

    private static String number(BigDecimal v, int digits) {
        DecimalFormat f = new DecimalFormat("#,##0." + "#".repeat(digits), DecimalFormatSymbols.getInstance(Locale.FRANCE));
        return f.format(v).replace('\u202f', ' ').replace('\u00a0', ' ');
    }

    static String money(BigDecimal v, String currency) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.FRANCE));
        String n = f.format(v.setScale(2, RoundingMode.HALF_UP)).replace(' ', ' ').replace(' ', ' ');
        return currency.equals("USD") ? "$" + n : n + " " + currency;
    }
}
