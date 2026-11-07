package cd.hrpay.lifecycle;

import cd.hrpay.i18n.Texts;
import cd.hrpay.payment.Company;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfCopy;
import com.lowagie.text.pdf.PdfImportedPage;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfWriter;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.springframework.stereotype.Component;

/** The PDFs of letters, contracts and certificates: the company's heading, the text, and the signatures. */
@Component
public class LifecyclePdf {

    private static final Font TITLE = new Font(Font.HELVETICA, 15, Font.BOLD);
    private static final Font HEADING = new Font(Font.HELVETICA, 11, Font.BOLD);
    private static final Font BOLD = new Font(Font.HELVETICA, 10, Font.BOLD);
    private static final Font NORMAL = new Font(Font.HELVETICA, 10.5f, Font.NORMAL);
    private static final Font SMALL = new Font(Font.HELVETICA, 8, Font.NORMAL);
    private static final Font MONO = new Font(Font.COURIER, 8, Font.NORMAL);

    public record Signature(String name, String role) {}

    /** A line of a signature page: a label and its value. */
    public record Line(String label, String value) {}

    private final Company company;

    public LifecyclePdf(Company company) {
        this.company = company;
    }

    /**
     * A letter or certificate. The body is plain text: a blank line starts a paragraph, a line starting with "## " is
     * a heading. The signatures go side by side at the end, the employer's first.
     */
    public byte[] letter(String title, String number, String body, String placeAndDate, List<Signature> signatures) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 60, 60, 50, 50);
        PdfWriter.getInstance(doc, bytes);
        doc.addTitle(title);
        doc.addCreator("HR Pay");
        doc.open();
        heading(doc);
        Paragraph t = new Paragraph(title, TITLE);
        t.setAlignment(Element.ALIGN_CENTER);
        t.setSpacingBefore(28);
        t.setSpacingAfter(4);
        doc.add(t);
        if (number != null) {
            Paragraph n = new Paragraph(Texts.t("No. %s", number), SMALL);
            n.setAlignment(Element.ALIGN_CENTER);
            doc.add(n);
        }
        Paragraph gap = new Paragraph(" ");
        gap.setSpacingAfter(12);
        doc.add(gap);
        for (String block : body.replace("\r\n", "\n").split("\n\\s*\n")) {
            String text = block.strip();
            if (text.isEmpty()) {
                continue;
            }
            Paragraph p;
            if (text.startsWith("## ")) {
                p = new Paragraph(text.substring(3).strip(), HEADING);
                p.setSpacingBefore(6);
                p.setSpacingAfter(4);
            } else {
                p = new Paragraph(text, NORMAL);
                p.setLeading(15);
                p.setSpacingAfter(9);
                p.setAlignment(Element.ALIGN_JUSTIFIED);
            }
            doc.add(p);
        }
        if (placeAndDate != null) {
            Paragraph d = new Paragraph(placeAndDate, NORMAL);
            d.setAlignment(Element.ALIGN_RIGHT);
            d.setSpacingBefore(14);
            doc.add(d);
        }
        if (!signatures.isEmpty()) {
            com.lowagie.text.pdf.PdfPTable table = new com.lowagie.text.pdf.PdfPTable(signatures.size());
            table.setWidthPercentage(100);
            table.setSpacingBefore(26);
            for (Signature s : signatures) {
                com.lowagie.text.pdf.PdfPCell cell = new com.lowagie.text.pdf.PdfPCell();
                cell.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
                cell.addElement(new Paragraph(s.role(), BOLD));
                cell.addElement(new Paragraph(s.name() == null ? " " : s.name(), NORMAL));
                cell.setMinimumHeight(70);
                table.addCell(cell);
            }
            doc.add(table);
        }
        doc.close();
        return bytes.toByteArray();
    }

    /** The page added to a signed document: who signed, when, how, and the fingerprint of what they read. */
    public byte[] signaturePage(String title, List<Line> lines, String note) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 60, 60, 50, 50);
        PdfWriter.getInstance(doc, bytes);
        doc.open();
        Paragraph t = new Paragraph(title, TITLE);
        t.setSpacingAfter(16);
        doc.add(t);
        for (Line l : lines) {
            Paragraph label = new Paragraph(l.label(), BOLD);
            label.setSpacingBefore(6);
            doc.add(label);
            boolean fingerprint = l.value() != null && l.value().matches("[0-9a-f]{64}");
            doc.add(new Paragraph(l.value() == null ? "—" : l.value(), fingerprint ? MONO : NORMAL));
        }
        if (note != null) {
            Paragraph n = new Paragraph(note, SMALL);
            n.setSpacingBefore(20);
            n.setLeading(11);
            doc.add(n);
        }
        doc.close();
        return bytes.toByteArray();
    }

    /** The original document's pages, unchanged, followed by the given pages. */
    public static byte[] append(byte[] original, byte[] more) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document doc = new Document();
            PdfCopy copy = new PdfCopy(doc, out);
            doc.open();
            for (byte[] part : new byte[][] {original, more}) {
                PdfReader reader = new PdfReader(part);
                for (int i = 1; i <= reader.getNumberOfPages(); i++) {
                    PdfImportedPage page = copy.getImportedPage(reader, i);
                    copy.addPage(page);
                }
                reader.close();
            }
            doc.close();
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("The signed document cannot be put together", e);
        }
    }

    private void heading(Document doc) {
        Company.Details employer = company.get();
        company.logo().ifPresent(logo -> {
            try {
                Image image = Image.getInstance(logo.data());
                image.scaleToFit(140, 55);
                doc.add(image);
            } catch (Exception ignored) {
                // a broken logo never stops the document
            }
        });
        doc.add(new Paragraph(employer.name() == null ? "" : employer.name(), BOLD));
        for (String line : new String[] {employer.address(), employer.rccm() == null ? null : "RCCM " + employer.rccm(),
                employer.nationalId() == null ? null : Texts.t("Nat. ID %s", employer.nationalId()),
                employer.taxNumber() == null ? null : Texts.t("Tax no. %s", employer.taxNumber())}) {
            if (line != null && !line.isBlank()) {
                doc.add(new Paragraph(line, SMALL));
            }
        }
    }
}
