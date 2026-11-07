package cd.hrpay.payment;

import cd.hrpay.i18n.Texts;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Image;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

/**
 * The company's letterhead on the documents it issues (certificates, letters, contracts, the certificate of
 * employment): the logo beside the name, legal form, address and contacts, a rule under them, and the legal numbers
 * at the foot of every page. Everything comes from the Company page, so a document issued after a change carries it.
 */
@Component
public class Letterhead {

    private static final Color INK = new Color(0x11, 0x18, 0x27);
    private static final Color MUTED = new Color(0x5b, 0x64, 0x72);
    private static final Color RULE = new Color(0x0f, 0x76, 0x6e);
    private static final Font NAME = new Font(Font.HELVETICA, 14, Font.BOLD, INK);
    private static final Font LINE = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, MUTED);
    private static final Font FOOT = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, MUTED);

    private final Company company;

    public Letterhead(Company company) {
        this.company = company;
    }

    /** Reads the company once; call {@link Head#footer} before the document opens and {@link Head#draw} right after. */
    public Head read() {
        return new Head(company.get(), company.payslipSettings(), company.logo().orElse(null));
    }

    public static final class Head {
        private final Company.Details d;
        private final Company.PayslipSettings s;
        private final Company.Logo logo;

        Head(Company.Details d, Company.PayslipSettings s, Company.Logo logo) {
            this.d = d;
            this.s = s;
            this.logo = logo;
        }

        /** The legal numbers at the foot of each page, with a thin rule above. */
        public void footer(PdfWriter writer) {
            String legal = join(" · ", d.rccm() == null ? null : "RCCM " + d.rccm(),
                    d.nationalId() == null ? null : Texts.t("Nat. ID %s", d.nationalId()),
                    d.taxNumber() == null ? null : Texts.t("Tax no. %s", d.taxNumber()),
                    d.cnssNumber() == null ? null : Texts.t("CNSS no. %s", d.cnssNumber()));
            String who = join(" · ", d.name(), d.address());
            if (legal == null && who == null) {
                return;
            }
            writer.setPageEvent(new PdfPageEventHelper() {
                @Override
                public void onEndPage(PdfWriter w, Document doc) {
                    PdfContentByte cb = w.getDirectContent();
                    float y = doc.bottom() - 14;
                    cb.saveState();
                    cb.setColorStroke(new Color(0xe3, 0xe6, 0xea));
                    cb.setLineWidth(0.5f);
                    cb.moveTo(doc.left(), y + 10);
                    cb.lineTo(doc.right(), y + 10);
                    cb.stroke();
                    cb.restoreState();
                    float mid = (doc.left() + doc.right()) / 2;
                    if (who != null) {
                        ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, new Phrase(who, FOOT), mid, y, 0);
                        y -= 10;
                    }
                    if (legal != null) {
                        ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, new Phrase(legal, FOOT), mid, y, 0);
                    }
                }
            });
        }

        /** The logo on the left, the company's name and details on the right, and a rule under both. */
        public void draw(Document doc) {
            Image image = null;
            if (logo != null) {
                try {
                    image = Image.getInstance(logo.data());
                    image.scaleToFit(130, 60);
                } catch (Exception e) {
                    image = null; // a broken logo never stops the document
                }
            }
            PdfPTable table = new PdfPTable(image == null ? new float[] {1} : new float[] {1.3f, 3f});
            table.setWidthPercentage(100);
            if (image != null) {
                PdfPCell cell = new PdfPCell(image, false);
                cell.setBorder(Rectangle.BOTTOM); // the rule runs under the logo too
                cell.setBorderColor(RULE);
                cell.setBorderWidth(1.2f);
                cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                cell.setPaddingBottom(8);
                table.addCell(cell);
            }
            PdfPCell text = new PdfPCell();
            text.setBorder(Rectangle.BOTTOM);
            text.setBorderColor(RULE);
            text.setBorderWidth(1.2f);
            text.setPaddingBottom(8);
            text.setHorizontalAlignment(Element.ALIGN_RIGHT);
            text.setVerticalAlignment(Element.ALIGN_BOTTOM);
            List<Paragraph> lines = new ArrayList<>();
            lines.add(new Paragraph(d.name() == null ? "" : d.name(), NAME));
            for (String l : new String[] {
                    s.legalForm(),
                    d.address(),
                    join(" · ", s.phone() == null ? null : Texts.t("Tel. %s", s.phone()), s.email(), s.website())}) {
                if (l != null && !l.isBlank()) {
                    lines.add(new Paragraph(l, LINE));
                }
            }
            for (Paragraph p : lines) {
                p.setAlignment(image == null ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
                p.setLeading(p.getFont().getSize() + 3);
                text.addElement(p);
            }
            table.addCell(text);
            doc.add(table);
        }

        /** Room at the foot of the page for the legal numbers. */
        public static float bottomMargin() {
            return 62;
        }
    }

    private static String join(String sep, String... parts) {
        String s = Stream.of(parts).filter(p -> p != null && !p.isBlank()).map(String::trim).collect(Collectors.joining(sep));
        return s.isEmpty() ? null : s;
    }
}
