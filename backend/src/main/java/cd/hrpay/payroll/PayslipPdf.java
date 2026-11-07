package cd.hrpay.payroll;

import cd.hrpay.i18n.Texts;

import cd.hrpay.payment.Company;
import cd.hrpay.payment.YearSummaries;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The payslip as a PDF document, in the layout the company chose:
 * <ul>
 *   <li>CLASSIC: earnings, then deductions, then employer charges, one table under the other;</li>
 *   <li>COMPACT: earnings and deductions side by side, fits a half page;</li>
 *   <li>LEDGER: one ruled table with a column for what is paid, what is withheld and what the employer pays.</li>
 * </ul>
 * All three carry the company's logo and legal information, the employee's details (matricule, grade, seniority,
 * marital status, children counted for family allowances) and the year-to-date totals.
 */
@Service
public class PayslipPdf {

    public record YearToDate(int year, int months, String currency, BigDecimal gross, BigDecimal cnss, BigDecimal ipr,
                             BigDecimal net, boolean otherCurrencies) {}

    private static final Font TITLE = new Font(Font.HELVETICA, 14, Font.BOLD);
    private static final Font BIG = new Font(Font.HELVETICA, 12, Font.BOLD);
    private static final Font BOLD = new Font(Font.HELVETICA, 8.5f, Font.BOLD);
    private static final Font NORMAL = new Font(Font.HELVETICA, 8.5f, Font.NORMAL);
    private static final Font SMALL = new Font(Font.HELVETICA, 7f, Font.NORMAL, new Color(90, 90, 90));
    private static final Font SMALL_BOLD = new Font(Font.HELVETICA, 7f, Font.BOLD, new Color(60, 60, 60));
    private static final Font NOTICE = new Font(Font.HELVETICA, 9, Font.BOLD, new Color(170, 60, 0));
    private static final Color SHADE = new Color(236, 240, 245);
    private static final Color RULE = new Color(150, 160, 170);
    private static String payment(String method) {
        return switch (method == null ? "" : method) {
            case "BANK" -> Texts.t("Bank transfer");
            case "MOBILE_MONEY" -> Texts.t("Mobile money");
            case "CASH" -> Texts.t("Cash");
            default -> method;
        };
    }

    private static String months(int n) {
        return n == 1 ? Texts.t("%s month", n) : Texts.t("%s months", n);
    }

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

    /** The payslip in the company's chosen layout. */
    public byte[] render(PayrollRuns.Payslip p) {
        return render(p, company.payslipSettings().template());
    }

    /** The payslip in a given layout (CLASSIC, COMPACT or LEDGER), for previews. */
    public byte[] render(PayrollRuns.Payslip p, String template) {
        return draw(p, template, yearToDate(p));
    }

    /** A made-up payslip in a given layout, with the company's real header, so HR can compare the layouts without showing anyone's pay. */
    public byte[] sample(String template) {
        return Texts.in(company.language(), () -> sampleIn(template));
    }

    private byte[] sampleIn(String template) {
        java.time.LocalDate month = java.time.LocalDate.now().withDayOfMonth(1);
        Map<String, Object> s = new java.util.LinkedHashMap<>();
        s.put("name", Texts.t("Sample Employee"));
        s.put("registrationNo", "EMP-0000");
        s.put("contractType", "PERMANENT");
        s.put("contractStart", month.minusYears(6).minusMonths(4).toString());
        s.put("hireDate", month.minusYears(6).minusMonths(4).toString());
        s.put("seniority", "6 y 4 m");
        s.put("seniorityYears", 6);
        s.put("seniorityMonths", 4);
        s.put("grade", Texts.t("B2 · Officer"));
        s.put("occupation", Texts.t("Accountant"));
        s.put("unit", Texts.t("Accounting"));
        s.put("direction", Texts.t("Finance"));
        s.put("basicMonthly", new BigDecimal("900.00"));
        s.put("childrenInCharge", 3);
        s.put("dependantsInCharge", 4);
        s.put("maritalStatus", "MARRIED");
        s.put("maritalCode", "M");
        s.put("cnssNumber", "000000000");
        s.put("taxNumber", "A0000000X");
        s.put("paymentMethod", "BANK");
        s.put("bankName", Texts.t("Sample bank"));
        s.put("bankAccount", "0000 0000 0000");
        List<PayrollRuns.Line> lines = List.of(
                new PayrollRuns.Line("BASIC", Texts.t("Basic salary"), "EARNING", new BigDecimal("900.00"), null, new BigDecimal("900.00"), 10, null),
                new PayrollRuns.Line("HOUSING", Texts.t("Housing allowance"), "EARNING", new BigDecimal("900.00"), new BigDecimal("30"), new BigDecimal("270.00"), 20, null),
                new PayrollRuns.Line("TRANSPORT", Texts.t("Transport allowance"), "EARNING", null, null, new BigDecimal("60.00"), 30, null),
                new PayrollRuns.Line("FAMILY", Texts.t("Family allowances"), "EARNING", new BigDecimal("3"), null, new BigDecimal("45.00"), 40, null),
                new PayrollRuns.Line("CNSS_EMPLOYEE", Texts.t("CNSS (employee share)"), "DEDUCTION", new BigDecimal("900.00"), new BigDecimal("5"), new BigDecimal("45.00"), 100, null),
                new PayrollRuns.Line("IPR", Texts.t("IPR (income tax)"), "DEDUCTION", new BigDecimal("855.00"), null, new BigDecimal("98.50"), 110, null),
                new PayrollRuns.Line("CNSS_EMPLOYER", Texts.t("CNSS (employer share)"), "EMPLOYER_CHARGE", new BigDecimal("900.00"), new BigDecimal("13"), new BigDecimal("117.00"), 200, null),
                new PayrollRuns.Line("INPP", "INPP", "EMPLOYER_CHARGE", new BigDecimal("900.00"), new BigDecimal("3"), new BigDecimal("27.00"), 210, null));
        PayrollRuns.Payslip p = new PayrollRuns.Payslip(0, 0, month, "SAMPLE", 0, "SAMPLE-0001", "USD",
                new BigDecimal("1275.00"), new BigDecimal("900.00"), new BigDecimal("855.00"), new BigDecimal("1881000"),
                new BigDecimal("143.50"), new BigDecimal("1131.50"), new BigDecimal("144.00"), 10560, 10560, 22,
                new BigDecimal("2200"), month, false, s, List.of(), lines);
        YearToDate ytd = new YearToDate(month.getYear(), month.getMonthValue(), "USD",
                new BigDecimal("1275.00").multiply(BigDecimal.valueOf(month.getMonthValue())),
                new BigDecimal("45.00").multiply(BigDecimal.valueOf(month.getMonthValue())),
                new BigDecimal("98.50").multiply(BigDecimal.valueOf(month.getMonthValue())),
                new BigDecimal("1131.50").multiply(BigDecimal.valueOf(month.getMonthValue())), false);
        return draw(p, template, ytd);
    }

    private byte[] draw(PayrollRuns.Payslip p, String template, YearToDate ytd) {
        return Texts.in(company.language(), () -> drawIn(p, template, ytd));
    }

    private byte[] drawIn(PayrollRuns.Payslip p, String template, YearToDate ytd) {
        Context c = new Context(p, company.get(), company.payslipSettings(), company.logo().orElse(null), ytd);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 30, 30);
        PdfWriter.getInstance(doc, bytes);
        doc.addTitle(Texts.t("Payslip %s %s", p.month().toString().substring(0, 7), text(p.snapshot().get("name"))));
        doc.addCreator("HR Pay");
        doc.open();
        switch (template == null ? "CLASSIC" : template) {
            case "COMPACT" -> compact(doc, c);
            case "LEDGER" -> ledger(doc, c);
            default -> classic(doc, c);
        }
        doc.close();
        return bytes.toByteArray();
    }

    private record Context(PayrollRuns.Payslip p, Company.Details employer, Company.PayslipSettings settings,
                           Company.Logo logo, YearToDate ytd) {
        Map<String, Object> s() {
            return p.snapshot();
        }

        String cur() {
            return p.currency();
        }
    }

    // ---- Layouts ---------------------------------------------------------------------------------------------

    private void classic(Document doc, Context c) {
        doc.add(header(c, true));
        doc.add(titleLine(c));
        draftNotice(doc, c);
        doc.add(employeeGrid(c, 3));

        PdfPTable lines = table(new float[] {5, 2, 1.6f, 2.4f});
        lines.setSpacingBefore(8);
        lines.setHeaderRows(1);
        for (String h : List.of(Texts.t("Item"), Texts.t("Base"), Texts.t("Rate"), Texts.t("Amount"))) {
            PdfPCell x = cell(h, BOLD, h.equals(Texts.t("Item")) ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT, true);
            x.setBorder(Rectangle.BOTTOM);
            lines.addCell(x);
        }
        section(lines, Texts.t("Earnings"), c, "EARNING", c.p().gross(), Texts.t("Gross pay"));
        section(lines, Texts.t("Deductions"), c, "DEDUCTION", c.p().employeeDeductions(), Texts.t("Total deductions"));
        netRow(lines, c, 3);
        doc.add(lines);
        doc.add(paidBy(c));
        doc.add(yearTable(c));
        if (c.ytd().otherCurrencies()) {
            doc.add(new Paragraph(Texts.t("Months paid in another currency are not included above."), SMALL));
        }

        PdfPTable charges = table(new float[] {5, 2, 1.6f, 2.4f});
        charges.setSpacingBefore(10);
        PdfPCell ch = cell(Texts.t("Employer charges (not deducted from pay)"), BOLD, Element.ALIGN_LEFT, true);
        ch.setColspan(4);
        charges.addCell(ch);
        for (PayrollRuns.Line l : c.p().lines()) {
            if (l.kind().equals("EMPLOYER_CHARGE") && l.amount().signum() > 0) {
                line(charges, l, c.cur());
            }
        }
        total(charges, Texts.t("Total employer charges"), c.p().employerCharges(), c.cur(), 3);
        doc.add(charges);
        footer(doc, c);
    }

    private void compact(Document doc, Context c) {
        doc.add(header(c, false));
        doc.add(titleLine(c));
        draftNotice(doc, c);
        doc.add(employeeGrid(c, 4));

        PdfPTable two = table(new float[] {1, 1});
        two.setSpacingBefore(8);
        two.addCell(column(Texts.t("Earnings"), c, "EARNING", c.p().gross(), Texts.t("Gross pay")));
        two.addCell(column(Texts.t("Deductions"), c, "DEDUCTION", c.p().employeeDeductions(), Texts.t("Total deductions")));
        doc.add(two);

        PdfPTable net = table(new float[] {3, 1});
        net.setSpacingBefore(4);
        netRow(net, c, 1);
        doc.add(net);
        doc.add(paidBy(c));

        YearToDate y = c.ytd();
        Paragraph ytd = new Paragraph(Texts.t("Year to date %s (%s): gross %s · CNSS %s · IPR %s · net %s", y.year(), months(y.months()),
                money(y.gross(), y.currency()), money(y.cnss(), y.currency()), money(y.ipr(), y.currency()),
                money(y.net(), y.currency())), SMALL_BOLD);
        ytd.setSpacingBefore(6);
        doc.add(ytd);
        Paragraph employer = new Paragraph(Texts.t("Employer charges this month: %s. Taxable income for IPR: %s.",
                money(c.p().employerCharges(), c.cur()), money(c.p().taxableBase(), c.cur())), SMALL);
        doc.add(employer);
        footer(doc, c);
    }

    private void ledger(Document doc, Context c) {
        doc.add(header(c, true));
        doc.add(titleLine(c));
        draftNotice(doc, c);
        doc.add(employeeGrid(c, 3));

        String cur = c.cur();
        PdfPTable t = table(new float[] {2.1f, 3.6f, 1.6f, 1.1f, 1.9f, 1.9f, 1.1f, 1.9f});
        t.setSpacingBefore(8);
        t.setHeaderRows(2);
        String[] top = {Texts.t("Code"), Texts.t("Item"), Texts.t("Base"), Texts.t("Employee"), "", "", Texts.t("Employer"), ""};
        int[] spans = {1, 1, 1, 3, 0, 0, 2, 0};
        for (int i = 0; i < top.length; i++) {
            if (spans[i] == 0) {
                continue;
            }
            PdfPCell h = ruled(top[i], BOLD, i < 2 ? Element.ALIGN_LEFT : Element.ALIGN_CENTER, true);
            h.setColspan(spans[i]);
            t.addCell(h);
        }
        for (String h : List.of("", "", "", Texts.t("Rate"), Texts.t("Paid"), Texts.t("Withheld"), Texts.t("Rate"), Texts.t("Amount"))) {
            t.addCell(ruled(h, SMALL_BOLD, Element.ALIGN_CENTER, true));
        }
        // One row per item: earnings and deductions, then each employer charge on the row of its base.
        List<PayrollRuns.Line> rows = new ArrayList<>();
        c.p().lines().stream().filter(l -> l.kind().equals("EARNING")).forEach(rows::add);
        c.p().lines().stream().filter(l -> l.kind().equals("DEDUCTION")).forEach(rows::add);
        c.p().lines().stream().filter(l -> l.kind().equals("EMPLOYER_CHARGE") && l.amount().signum() > 0).forEach(rows::add);
        for (PayrollRuns.Line l : rows) {
            boolean earning = l.kind().equals("EARNING");
            boolean employer = l.kind().equals("EMPLOYER_CHARGE");
            t.addCell(ruled(l.elementCode() == null ? "" : l.elementCode(), SMALL, Element.ALIGN_LEFT, false));
            t.addCell(ruled(l.label(), NORMAL, Element.ALIGN_LEFT, false));
            t.addCell(ruled(l.base() == null ? "" : number(l.base(), 2), NORMAL, Element.ALIGN_RIGHT, false));
            t.addCell(ruled(employer ? "" : rate(l), NORMAL, Element.ALIGN_RIGHT, false));
            t.addCell(ruled(earning ? money(l.amount(), cur) : "", NORMAL, Element.ALIGN_RIGHT, false));
            t.addCell(ruled(l.kind().equals("DEDUCTION") ? money(l.amount(), cur) : "", NORMAL, Element.ALIGN_RIGHT, false));
            t.addCell(ruled(employer ? rate(l) : "", NORMAL, Element.ALIGN_RIGHT, false));
            t.addCell(ruled(employer ? money(l.amount(), cur) : "", NORMAL, Element.ALIGN_RIGHT, false));
        }
        PdfPCell totals = ruled(Texts.t("Totals"), BOLD, Element.ALIGN_LEFT, true);
        totals.setColspan(4);
        t.addCell(totals);
        t.addCell(ruled(money(c.p().gross(), cur), BOLD, Element.ALIGN_RIGHT, true));
        t.addCell(ruled(money(c.p().employeeDeductions(), cur), BOLD, Element.ALIGN_RIGHT, true));
        t.addCell(ruled("", BOLD, Element.ALIGN_RIGHT, true));
        t.addCell(ruled(money(c.p().employerCharges(), cur), BOLD, Element.ALIGN_RIGHT, true));
        PdfPCell net = ruled("NET PAY", BIG, Element.ALIGN_LEFT, true);
        net.setColspan(5);
        t.addCell(net);
        PdfPCell netAmount = ruled(money(c.p().net(), cur), BIG, Element.ALIGN_RIGHT, true);
        netAmount.setColspan(3);
        t.addCell(netAmount);
        doc.add(t);
        doc.add(paidBy(c));
        doc.add(yearTable(c));
        Paragraph bases = new Paragraph(Texts.t("Taxable income for IPR: %s (%s). CNSS base: %s.", money(c.p().taxableBase(), cur),
                money(c.p().taxableBaseCdf(), "CDF"), money(c.p().cnssBase(), cur)), SMALL);
        bases.setSpacingBefore(4);
        doc.add(bases);
        footer(doc, c);
    }

    // ---- Shared blocks ---------------------------------------------------------------------------------------

    /** Logo and employer on the left; for the full layouts, the legal numbers on the right. */
    private PdfPTable header(Context c, boolean full) {
        Company.Details e = c.employer();
        Company.PayslipSettings s = c.settings();
        PdfPTable head = table(c.logo() == null ? new float[] {3, 2} : new float[] {1.6f, 2.5f, 2.0f});
        if (c.logo() != null) {
            PdfPCell logo = cell("", NORMAL, Element.ALIGN_LEFT, false);
            try {
                Image img = Image.getInstance(c.logo().data());
                img.scaleToFit(125, 55);
                logo.addElement(img);
            } catch (Exception ignored) {
                // A logo that cannot be drawn is left out rather than failing the payslip.
            }
            head.addCell(logo);
        }
        PdfPCell left = cell("", NORMAL, Element.ALIGN_LEFT, false);
        left.addElement(new Paragraph(text(e.name()), BIG));
        if (s.legalForm() != null) {
            left.addElement(new Paragraph(s.legalForm(), SMALL));
        }
        left.addElement(new Paragraph(text(e.address()), NORMAL));
        String contact = join(s.phone(), s.email(), s.website());
        if (!contact.isEmpty()) {
            left.addElement(new Paragraph(contact, SMALL));
        }
        head.addCell(left);
        PdfPCell right = cell("", NORMAL, Element.ALIGN_RIGHT, false);
        List<String> legal = new ArrayList<>();
        add(legal, "RCCM", e.rccm());
        add(legal, Texts.t("Id. Nat."), e.nationalId());
        add(legal, "NIF", e.taxNumber());
        add(legal, "CNSS", e.cnssNumber());
        if (full) {
            add(legal, "INPP", e.inppNumber());
            add(legal, "ONEM", e.onemNumber());
        }
        for (String l : legal) {
            right.addElement(right(l, SMALL));
        }
        head.addCell(right);
        return head;
    }

    private Paragraph titleLine(Context c) {
        PayrollRuns.Payslip p = c.p();
        Paragraph title = new Paragraph();
        title.add(new Chunk(Texts.t("Payslip · %s", Texts.month(p.month())), TITLE));
        title.add(new Chunk("    " + (p.number() == null ? Texts.t("not numbered yet") : Texts.t("No. %s", p.number()))
                + Texts.t(" · period %s to %s", Texts.day(p.month()), Texts.day(p.month().withDayOfMonth(p.month().lengthOfMonth()))), SMALL));
        title.setSpacingBefore(8);
        return title;
    }

    private static void draftNotice(Document doc, Context c) {
        if ("SAMPLE".equals(c.p().runStatus())) {
            doc.add(new Paragraph(Texts.t("SAMPLE: made-up figures to show the layout."), NOTICE));
        } else if (!"CLOSED".equals(c.p().runStatus())) {
            doc.add(new Paragraph(Texts.t("DRAFT: this payroll is %s and not yet closed.", Texts.t(c.p().runStatus().toLowerCase())), NOTICE));
        }
    }

    /** The employee's details as a ruled grid of label and value pairs. */
    private PdfPTable employeeGrid(Context c, int perRow) {
        Map<String, Object> s = c.s();
        List<String[]> facts = new ArrayList<>();
        facts.add(new String[] {Texts.t("Employee"), text(s.get("name"))});
        facts.add(new String[] {Texts.t("Matricule"), text(s.get("registrationNo"))});
        facts.add(new String[] {Texts.t("Grade"), text(s.get("grade"))});
        facts.add(new String[] {Texts.t("Occupation"), dash(s.get("occupation"))});
        if (s.get("unit") != null) {
            String dir = text(s.get("direction"));
            facts.add(new String[] {Texts.t("Unit"), join(text(s.get("unit")), dir.equals(text(s.get("unit"))) ? "" : dir)});
        }
        facts.add(new String[] {Texts.t("Hired"), dash(s.get("hireDate"))});
        facts.add(new String[] {Texts.t("Seniority"), s.get("seniorityYears") == null ? "—"
                : Texts.t("%s yr %s mo", s.get("seniorityYears"), s.get("seniorityMonths"))});
        facts.add(new String[] {Texts.t("Marital status"), dash(s.get("maritalCode"))});
        facts.add(new String[] {Texts.t("Children (family allowances)"), text(s.get("childrenInCharge"))});
        facts.add(new String[] {Texts.t("Contract"), Texts.t(text(s.get("contractType")).replace('_', ' ').toLowerCase())
                + (Boolean.TRUE.equals(s.get("expatriate")) ? Texts.t(", expatriate") : "")});
        facts.add(new String[] {Texts.t("CNSS no."), dash(s.get("cnssNumber"))});
        facts.add(new String[] {"NIF", dash(s.get("taxNumber"))});
        facts.add(new String[] {Texts.t("Hours paid"), Texts.t("%s of %s", hours(c.p().paidMinutes()), hours(c.p().scheduledMinutes()))});
        facts.add(new String[] {Texts.t("Days worked"), String.valueOf(c.p().daysWorked())});
        facts.add(new String[] {Texts.t("Monthly basic"), money(new BigDecimal(text(s.get("basicMonthly")).isEmpty() ? "0"
                : text(s.get("basicMonthly"))), c.cur())});
        if (c.cur().equals("USD") && c.p().usdCdf() != null) {
            facts.add(new String[] {Texts.t("BCC rate"), "$1 = " + c.p().usdCdf().stripTrailingZeros().toPlainString() + " CDF ("
                    + c.p().rateDate() + ")"});
        }
        PdfPTable grid = table(repeat(perRow));
        grid.setSpacingBefore(6);
        for (String[] f : facts) {
            PdfPCell x = new PdfPCell();
            x.setBorderColor(RULE);
            x.setBorderWidth(0.4f);
            x.setPadding(3);
            x.addElement(new Paragraph(f[0], SMALL));
            x.addElement(new Paragraph(f[1], BOLD));
            grid.addCell(x);
        }
        for (int i = facts.size() % perRow; i > 0 && i < perRow; i++) {
            PdfPCell empty = new PdfPCell();
            empty.setBorder(com.lowagie.text.Rectangle.TOP);
            empty.setBorderColor(RULE);
            empty.setBorderWidth(0.4f);
            grid.addCell(empty);
        }
        return grid;
    }

    private static float[] repeat(int n) {
        float[] w = new float[n];
        java.util.Arrays.fill(w, 1);
        return w;
    }

    private static void section(PdfPTable t, String title, Context c, String kind, BigDecimal total, String totalLabel) {
        PdfPCell h = cell(title, BOLD, Element.ALIGN_LEFT, true);
        h.setColspan(4);
        t.addCell(h);
        for (PayrollRuns.Line l : c.p().lines()) {
            if (l.kind().equals(kind)) {
                line(t, l, c.cur());
            }
        }
        total(t, totalLabel, total, c.cur(), 3);
    }

    /** One column of the compact layout: item and amount only. */
    private static PdfPCell column(String title, Context c, String kind, BigDecimal total, String totalLabel) {
        PdfPTable t = table(new float[] {3, 1.4f});
        PdfPCell h = cell(title, BOLD, Element.ALIGN_LEFT, true);
        h.setColspan(2);
        t.addCell(h);
        for (PayrollRuns.Line l : c.p().lines()) {
            if (l.kind().equals(kind)) {
                t.addCell(cell(l.label(), NORMAL, Element.ALIGN_LEFT, false));
                t.addCell(cell(money(l.amount(), c.cur()), NORMAL, Element.ALIGN_RIGHT, false));
            }
        }
        total(t, totalLabel, total, c.cur(), 1);
        PdfPCell holder = new PdfPCell(t);
        holder.setBorder(Rectangle.NO_BORDER);
        holder.setPaddingRight(4);
        return holder;
    }

    private static void netRow(PdfPTable t, Context c, int labelSpan) {
        PdfPCell net = cell(Texts.t("Net pay"), TITLE, Element.ALIGN_LEFT, true);
        net.setColspan(labelSpan);
        t.addCell(net);
        t.addCell(cell(money(c.p().net(), c.cur()), TITLE, Element.ALIGN_RIGHT, true));
    }

    private static Paragraph paidBy(Context c) {
        Map<String, Object> s = c.s();
        String pay = payment(text(s.get("paymentMethod")));
        if ("BANK".equals(s.get("paymentMethod")) && s.get("bankName") != null) {
            pay += ": " + text(s.get("bankName")) + " " + text(s.get("bankAccount"));
        } else if ("MOBILE_MONEY".equals(s.get("paymentMethod"))) {
            pay += ": " + text(s.get("mobileMoneyOperator")) + " " + text(s.get("mobileMoneyNumber"));
        }
        if (c.cur().equals("USD") && c.p().usdCdf() != null) {
            pay += Texts.t(" · net in CDF: %s", money(c.p().net().multiply(c.p().usdCdf()).setScale(0, RoundingMode.HALF_UP), "CDF"));
        }
        Paragraph paid = new Paragraph(Texts.t("Paid by %s", pay), NORMAL);
        paid.setSpacingBefore(5);
        return paid;
    }

    private static PdfPTable yearTable(Context c) {
        YearToDate y = c.ytd();
        PdfPTable year = table(new float[] {3, 2, 2, 2, 2});
        year.setSpacingBefore(10);
        PdfPCell yh = cell(Texts.t("Year to date %s (%s, in %s)", y.year(), months(y.months()), y.currency()), BOLD, Element.ALIGN_LEFT, true);
        yh.setColspan(5);
        year.addCell(yh);
        for (String h : List.of("", Texts.t("Gross"), "CNSS", "IPR", Texts.t("Net"))) {
            year.addCell(cell(h, BOLD, h.isEmpty() ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT, false));
        }
        year.addCell(cell(Texts.t("Total"), NORMAL, Element.ALIGN_LEFT, false));
        for (BigDecimal v : List.of(y.gross(), y.cnss(), y.ipr(), y.net())) {
            year.addCell(cell(money(v, y.currency()), NORMAL, Element.ALIGN_RIGHT, false));
        }
        return year;
    }

    private static void footer(Document doc, Context c) {
        Paragraph keep = new Paragraph((c.settings().footer() == null ? "" : c.settings().footer() + " ")
                + Texts.t("Keep this payslip without time limit."), SMALL);
        keep.setSpacingBefore(10);
        doc.add(keep);
    }

    private static void line(PdfPTable t, PayrollRuns.Line l, String cur) {
        t.addCell(cell(l.label(), NORMAL, Element.ALIGN_LEFT, false));
        t.addCell(cell(l.base() == null ? "" : number(l.base(), 2), NORMAL, Element.ALIGN_RIGHT, false));
        t.addCell(cell(rate(l), NORMAL, Element.ALIGN_RIGHT, false));
        t.addCell(cell(money(l.amount(), cur), NORMAL, Element.ALIGN_RIGHT, false));
    }

    private static String rate(PayrollRuns.Line l) {
        return l.rate() == null ? ""
                : l.kind().equals("EARNING") && !"HOUSING".equals(l.elementCode()) ? number(l.rate(), 4) : number(l.rate(), 3) + "%";
    }

    private static void total(PdfPTable t, String label, BigDecimal amount, String cur, int labelSpan) {
        PdfPCell x = cell(label, BOLD, Element.ALIGN_LEFT, false);
        x.setColspan(labelSpan);
        x.setBorder(Rectangle.TOP);
        t.addCell(x);
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

    private static PdfPCell ruled(String text, Font font, int align, boolean shaded) {
        PdfPCell c = cell(text, font, align, shaded);
        c.setBorder(Rectangle.BOX);
        c.setBorderColor(RULE);
        c.setBorderWidth(0.4f);
        return c;
    }

    private static Paragraph right(String text, Font font) {
        Paragraph p = new Paragraph(text, font);
        p.setAlignment(Element.ALIGN_RIGHT);
        return p;
    }

    private static void add(List<String> out, String label, String value) {
        if (value != null && !value.isBlank()) {
            out.add(label + " " + value);
        }
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
        return f.format(v).replace(' ', ' ').replace(' ', ' ');
    }

    static String money(BigDecimal v, String currency) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.FRANCE));
        String n = f.format(v.setScale(2, RoundingMode.HALF_UP)).replace(' ', ' ').replace(' ', ' ');
        return currency.equals("USD") ? "$" + n : n + " " + currency;
    }
}
