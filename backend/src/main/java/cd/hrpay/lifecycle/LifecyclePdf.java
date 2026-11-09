package cd.hrpay.lifecycle;

import cd.hrpay.i18n.Texts;
import cd.hrpay.payment.Letterhead;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfCopy;
import org.openpdf.text.pdf.PdfImportedPage;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.PdfWriter;
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

    private final Letterhead letterhead;

    public LifecyclePdf(Letterhead letterhead) {
        this.letterhead = letterhead;
    }

    /**
     * A letter or certificate. The body is plain text: a blank line starts a paragraph, a line starting with "## " is
     * a heading. The signatures go side by side at the end, the employer's first.
     */
    public byte[] letter(String title, String number, String body, String placeAndDate, List<Signature> signatures) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Letterhead.Head head = letterhead.read();
        Document doc = new Document(PageSize.A4, 60, 60, 40, Letterhead.Head.bottomMargin());
        head.footer(PdfWriter.getInstance(doc, bytes));
        doc.addTitle(title);
        doc.addCreator("HR Pay");
        doc.open();
        head.draw(doc);
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
        body(doc, body, NORMAL, 15);
        if (placeAndDate != null) {
            Paragraph d = new Paragraph(placeAndDate, NORMAL);
            d.setAlignment(Element.ALIGN_RIGHT);
            d.setSpacingBefore(14);
            doc.add(d);
        }
        if (!signatures.isEmpty()) {
            org.openpdf.text.pdf.PdfPTable table = new org.openpdf.text.pdf.PdfPTable(signatures.size());
            table.setWidthPercentage(100);
            table.setSpacingBefore(26);
            for (Signature s : signatures) {
                org.openpdf.text.pdf.PdfPCell cell = new org.openpdf.text.pdf.PdfPCell();
                cell.setBorder(org.openpdf.text.Rectangle.NO_BORDER);
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

    private static void body(Document doc, String body, Font font, float leading) {
        for (ContractSheet.Block b : ContractSheet.blocks(body)) {
            Paragraph p;
            if (b.heading()) {
                p = new Paragraph(b.text(), HEADING);
                p.setSpacingBefore(6);
                p.setSpacingAfter(3);
                p.setKeepTogether(true);
            } else {
                p = new Paragraph(b.text(), font);
                p.setLeading(leading);
                p.setSpacingAfter(leading * 0.55f);
                p.setAlignment(Element.ALIGN_JUSTIFIED);
            }
            doc.add(p);
        }
    }

    private static final Font CONTRACT_TEXT = new Font(Font.HELVETICA, 9.5f, Font.NORMAL);
    private static final Font NOTE = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, new java.awt.Color(0x37, 0x41, 0x51));
    private static final Font LABEL = new Font(Font.HELVETICA, 8.5f, Font.BOLD, new java.awt.Color(0x37, 0x41, 0x51));
    private static final Font VALUE = new Font(Font.HELVETICA, 8.5f, Font.NORMAL);

    /**
     * A contract: the heading, the title and the model's reference on the first page, then straight into the
     * articles (no summary repeating them); the place and date, the employer's and the employee's signature blocks,
     * the notes in small print, and an annex with the parties' full identity and the model's document control.
     * Every page is numbered "page x / y".
     */
    public byte[] contract(ContractSheet c) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Letterhead.Head head = letterhead.read();
        Document doc = new Document(PageSize.A4, 56, 56, 40, Letterhead.Head.bottomMargin() + 10);
        PdfWriter writer = PdfWriter.getInstance(doc, bytes);
        head.footer(writer);
        writer.setPageEvent(new PageNumbers());
        doc.addTitle(c.title());
        doc.addCreator("HR Pay");
        doc.open();
        head.draw(doc);
        Paragraph t = new Paragraph(c.title().toUpperCase(Texts.locale()), TITLE);
        t.setAlignment(Element.ALIGN_CENTER);
        t.setSpacingBefore(20);
        t.setSpacingAfter(3);
        doc.add(t);
        String ref = c.number() == null ? c.reference()
                : c.reference() == null ? Texts.t("No. %s", c.number()) : c.reference() + " · " + Texts.t("No. %s", c.number());
        if (ref != null) {
            Paragraph n = new Paragraph(ref, SMALL);
            n.setAlignment(Element.ALIGN_CENTER);
            doc.add(n);
        }
        Paragraph gap = new Paragraph(" ");
        gap.setSpacingAfter(4);
        doc.add(gap);
        body(doc, c.body(), CONTRACT_TEXT, 13);
        if (c.placeAndDate() != null) {
            Paragraph d = new Paragraph(c.placeAndDate(), CONTRACT_TEXT);
            d.setAlignment(Element.ALIGN_RIGHT);
            d.setSpacingBefore(10);
            doc.add(d);
        }
        PdfPTable sign = new PdfPTable(c.employeeSigner() == null ? 1 : 2);
        sign.setWidthPercentage(100);
        sign.setSpacingBefore(14);
        sign.setKeepTogether(true);
        sign.addCell(signCell(c.employerHeading(), List.of(
                new Line(Texts.t("Name"), c.employerSigner()),
                new Line(Texts.t("Capacity"), c.employerCapacity())), Texts.t("Signature and stamp:")));
        if (c.employeeSigner() != null) {
            sign.addCell(signCell(c.employeeHeading(), List.of(
                    new Line(Texts.t("Name"), c.employeeSigner()),
                    new Line(Texts.t("Mention"), c.employeeMention())), Texts.t("Signature:")));
        }
        doc.add(sign);
        if (c.notes() != null && !c.notes().isBlank()) {
            Paragraph rule = new Paragraph(" ");
            rule.setSpacingBefore(8);
            doc.add(rule);
            for (ContractSheet.Block b : ContractSheet.blocks(c.notes())) {
                Paragraph p = new Paragraph(b.text(), b.heading() ? LABEL : NOTE);
                p.setLeading(10);
                p.setSpacingAfter(4);
                p.setAlignment(Element.ALIGN_JUSTIFIED);
                doc.add(p);
            }
        }
        if (!c.annex().isEmpty()) {
            doc.newPage();
            Paragraph a = new Paragraph(Texts.t("Annex: identification of the parties"), HEADING);
            a.setAlignment(Element.ALIGN_CENTER);
            a.setSpacingAfter(4);
            doc.add(a);
            Paragraph sub = new Paragraph(Texts.t("%s · Labour Code art. 212", c.title()), SMALL);
            sub.setAlignment(Element.ALIGN_CENTER);
            sub.setSpacingAfter(10);
            doc.add(sub);
            for (ContractSheet.Section s : c.annex()) {
                doc.add(section(s.heading(), s.lines()));
            }
            List<Line> control = c.controlLines();
            if (!control.isEmpty() || c.approvalHeading() != null) {
                PdfPTable box = section(Texts.t("Document control and adoption"), control);
                if (c.approvalHeading() != null) {
                    PdfPCell cell = new PdfPCell();
                    cell.setColspan(2);
                    cell.setBorder(Rectangle.NO_BORDER);
                    cell.setPaddingTop(8);
                    cell.addElement(new Paragraph(c.approvalHeading(), LABEL));
                    cell.addElement(new Paragraph(join(", ", c.employerSigner(), c.employerCapacity()), VALUE));
                    cell.addElement(new Paragraph(Texts.t("Date: ____ / ____ / ________      Signature and stamp: ______________________"), VALUE));
                    box.addCell(cell);
                }
                doc.add(box);
            }
        }
        doc.close();
        return bytes.toByteArray();
    }

    private static PdfPCell signCell(String heading, List<Line> lines, String signature) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingRight(12);
        cell.addElement(new Paragraph(heading, BOLD));
        for (Line l : lines) {
            if (l.value() != null) {
                Paragraph p = new Paragraph();
                p.add(new Phrase(l.label() + " : ", LABEL));
                p.add(new Phrase(l.value(), VALUE));
                p.setSpacingBefore(2);
                cell.addElement(p);
            }
        }
        Paragraph s = new Paragraph(signature, LABEL);
        s.setSpacingBefore(4);
        cell.addElement(s);
        cell.setMinimumHeight(105);
        return cell;
    }

    private static PdfPTable section(String heading, List<Line> lines) {
        PdfPTable table = new PdfPTable(new float[] {1.3f, 3f});
        table.setWidthPercentage(100);
        table.setSpacingAfter(10);
        table.setKeepTogether(true);
        PdfPCell h = new PdfPCell(new Phrase(heading, BOLD));
        h.setColspan(2);
        h.setBorder(Rectangle.BOTTOM);
        h.setBorderColor(new java.awt.Color(0x0f, 0x76, 0x6e));
        h.setPaddingBottom(4);
        table.addCell(h);
        for (Line l : lines) {
            PdfPCell k = new PdfPCell(new Phrase(l.label(), LABEL));
            k.setBorder(Rectangle.BOTTOM);
            k.setBorderColor(new java.awt.Color(0xe3, 0xe6, 0xea));
            k.setPadding(3);
            table.addCell(k);
            PdfPCell v = new PdfPCell(new Phrase(l.value() == null ? "—" : l.value(), VALUE));
            v.setBorder(Rectangle.BOTTOM);
            v.setBorderColor(new java.awt.Color(0xe3, 0xe6, 0xea));
            v.setPadding(3);
            table.addCell(v);
        }
        return table;
    }

    private static String join(String sep, String... parts) {
        return java.util.Arrays.stream(parts).filter(p -> p != null && !p.isBlank()).collect(java.util.stream.Collectors.joining(sep));
    }

    /** "page x / y" at the top right of every page; y is written when the document closes. */
    private static final class PageNumbers extends org.openpdf.text.pdf.PdfPageEventHelper {
        private org.openpdf.text.pdf.PdfTemplate total;

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            total = writer.getDirectContent().createTemplate(30, 12);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            org.openpdf.text.pdf.PdfContentByte cb = writer.getDirectContent();
            String text = Texts.t("Page %d / ", writer.getPageNumber());
            float width = org.openpdf.text.pdf.ColumnText.getWidth(new Phrase(text, SMALL));
            float x = document.right() - width - 14;
            float y = document.getPageSize().getHeight() - 22;
            org.openpdf.text.pdf.ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, new Phrase(text, SMALL), x, y, 0);
            cb.addTemplate(total, x + width, y);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            org.openpdf.text.pdf.ColumnText.showTextAligned(total, Element.ALIGN_LEFT,
                    new Phrase(Integer.toString(writer.getPageNumber() - 1), SMALL), 0, 2, 0);
        }
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
}
