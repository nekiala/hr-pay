package cd.hrpay.payroll;

import cd.hrpay.i18n.Texts;
import cd.hrpay.payment.Company;
import cd.hrpay.payment.Letterhead;
import java.io.ByteArrayOutputStream;
import java.time.YearMonth;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

/**
 * The receipt of a quinzaine (bon d'acompte), signed by the employee when paid: the written statement of each payment
 * (DRC Labour Code art. 103, Congo art. 90).
 */
@Component
public class QuinzaineReceipt {

    private static final Font TITLE = new Font(Font.HELVETICA, 15, Font.BOLD);
    private static final Font NORMAL = new Font(Font.HELVETICA, 10.5f, Font.NORMAL);
    private static final Font BOLD = new Font(Font.HELVETICA, 10.5f, Font.BOLD);
    private static final Font SMALL = new Font(Font.HELVETICA, 8.5f, Font.NORMAL);

    private final Company company;
    private final Letterhead letterhead;

    public QuinzaineReceipt(Company company, Letterhead letterhead) {
        this.company = company;
        this.letterhead = letterhead;
    }

    public byte[] render(Quinzaines.Run run, Quinzaines.Line line) {
        return Texts.in(company.language(), () -> draw(run, line));
    }

    private static PdfPCell cell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setPadding(6);
        return c;
    }

    private byte[] draw(Quinzaines.Run run, Quinzaines.Line l) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Letterhead.Head head = letterhead.read();
        Document doc = new Document(PageSize.A5.rotate(), 40, 40, 30, Letterhead.Head.bottomMargin());
        head.footer(PdfWriter.getInstance(doc, bytes));
        doc.addTitle(Texts.t("Quinzaine receipt"));
        doc.addCreator("HR Pay");
        doc.open();
        head.draw(doc);
        Paragraph title = new Paragraph(Texts.t("QUINZAINE RECEIPT (ADVANCE ON PAY)"), TITLE);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingBefore(14);
        doc.add(title);
        Paragraph num = new Paragraph(l.number() == null ? Texts.t("Draft") : Texts.t("No. %s", l.number()), SMALL);
        num.setAlignment(Element.ALIGN_CENTER);
        num.setSpacingAfter(14);
        doc.add(num);

        PdfPTable t = new PdfPTable(new float[] {2, 3});
        t.setWidthPercentage(100);
        t.addCell(cell(Texts.t("Employee"), NORMAL));
        t.addCell(cell(l.employeeName() + (l.registrationNo() == null ? "" : " · " + l.registrationNo()), BOLD));
        t.addCell(cell(Texts.t("Month"), NORMAL));
        t.addCell(cell(Texts.month(YearMonth.from(run.month())), NORMAL));
        t.addCell(cell(Texts.t("Pay date"), NORMAL));
        t.addCell(cell(Texts.day(run.payDate()), NORMAL));
        t.addCell(cell(Texts.t("Grade category and rate"), NORMAL));
        t.addCell(cell(l.category() + " · " + l.percent().stripTrailingZeros().toPlainString() + " %", NORMAL));
        t.addCell(cell(Texts.t("Amount paid"), NORMAL));
        t.addCell(cell(l.amount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " " + l.currency(), BOLD));
        doc.add(t);

        Paragraph note = new Paragraph(Texts.t("This amount is an advance on the pay of %s. The payslip of the month takes it back in full from the net pay.",
                Texts.month(YearMonth.from(run.month()))), NORMAL);
        note.setSpacingBefore(12);
        doc.add(note);
        PdfPTable sign = new PdfPTable(2);
        sign.setWidthPercentage(100);
        sign.setSpacingBefore(26);
        PdfPCell a = new PdfPCell(new Phrase(Texts.t("For the employer"), NORMAL));
        PdfPCell b = new PdfPCell(new Phrase(Texts.t("Received by the employee (signature)"), NORMAL));
        for (PdfPCell c : new PdfPCell[] {a, b}) {
            c.setBorder(0);
            c.setFixedHeight(50);
            sign.addCell(c);
        }
        doc.add(sign);
        doc.close();
        return bytes.toByteArray();
    }
}
