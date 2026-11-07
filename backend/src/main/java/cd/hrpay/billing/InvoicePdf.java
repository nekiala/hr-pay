package cd.hrpay.billing;

import cd.hrpay.i18n.Texts;

import cd.hrpay.payment.Company;
import cd.hrpay.tenancy.TenantContext;
import cd.hrpay.tenancy.Tenants;
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
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** The invoice as a PDF, issued by the provider's company with its logo and legal details. */
@Service
public class InvoicePdf {

    private static final Font TITLE = new Font(Font.HELVETICA, 16, Font.BOLD);
    private static final Font BOLD = new Font(Font.HELVETICA, 9, Font.BOLD);
    private static final Font NORMAL = new Font(Font.HELVETICA, 9, Font.NORMAL);
    private static final Font SMALL = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, new Color(90, 90, 90));
    private static final Font STAMP = new Font(Font.HELVETICA, 12, Font.BOLD, new Color(15, 118, 110));
    private static final Font VOID = new Font(Font.HELVETICA, 12, Font.BOLD, new Color(170, 40, 40));
    private static final Color SHADE = new Color(240, 243, 245);
    private static final Color RULE = new Color(200, 205, 210);

    private final Company company;
    private final Tenants tenants;
    private final Billing billing;

    public InvoicePdf(Company company, Tenants tenants, Billing billing) {
        this.company = company;
        this.tenants = tenants;
        this.billing = billing;
    }

    private record Issuer(Company.Details details, Company.PayslipSettings contact, Company.Logo logo) {}

    /** The invoice in its customer's language. */
    public byte[] render(Billing.Invoice i) {
        String language = tenants.callAs(i.companyId(), company::language);
        return Texts.in(language, () -> renderIn(i));
    }

    private byte[] renderIn(Billing.Invoice i) {
        Issuer issuer = tenants.callAs(TenantContext.providerCompany(),
                () -> new Issuer(company.get(), company.payslipSettings(), company.logo().orElse(null)));
        Billing.Settings settings = billing.settings();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 42, 42, 36, 36);
        PdfWriter.getInstance(doc, bytes);
        doc.addTitle(Texts.t("Invoice %s", i.number()));
        doc.addCreator("HR Pay");
        doc.open();

        PdfPTable head = table(new float[] {3, 2});
        PdfPCell from = new PdfPCell();
        from.setBorder(Rectangle.NO_BORDER);
        if (issuer.logo() != null) {
            try {
                Image logo = Image.getInstance(issuer.logo().data());
                logo.scaleToFit(130, 50);
                from.addElement(logo);
            } catch (Exception ignored) {
                // a broken logo never stops the invoice
            }
        }
        from.addElement(new Paragraph(issuer.details().name(), BOLD));
        for (String l : issuerLines(issuer)) {
            from.addElement(new Paragraph(l, SMALL));
        }
        head.addCell(from);
        PdfPCell title = new PdfPCell();
        title.setBorder(Rectangle.NO_BORDER);
        Paragraph t = new Paragraph(Texts.t("INVOICE"), TITLE);
        t.setAlignment(Element.ALIGN_RIGHT);
        title.addElement(t);
        title.addElement(right(i.number(), BOLD));
        title.addElement(right(Texts.t("Issued %s", Billing.day(i.issuedOn())), NORMAL));
        title.addElement(right(Texts.t("Due %s", Billing.day(i.dueOn())), NORMAL));
        if ("PAID".equals(i.status())) {
            title.addElement(right(Texts.t("PAID"), STAMP));
        } else if ("VOID".equals(i.status())) {
            title.addElement(right(Texts.t("CANCELLED"), VOID));
        }
        head.addCell(title);
        doc.add(head);

        Paragraph to = new Paragraph(Texts.t("Billed to"), SMALL);
        to.setSpacingBefore(16);
        doc.add(to);
        doc.add(new Paragraph(i.customerName(), BOLD));
        if (i.customerAddress() != null) {
            doc.add(new Paragraph(i.customerAddress(), NORMAL));
        }
        if (i.customerTaxNumber() != null) {
            doc.add(new Paragraph(Texts.t("Tax number %s", i.customerTaxNumber()), NORMAL));
        }

        PdfPTable lines = table(new float[] {6, 2});
        lines.setSpacingBefore(18);
        lines.addCell(ruled(Texts.t("Description"), BOLD, Element.ALIGN_LEFT, true));
        lines.addCell(ruled(Texts.t("Amount (%s)", i.currency()), BOLD, Element.ALIGN_RIGHT, true));
        String what = Texts.t("HR Pay subscription, %s, %s: %s to %s", i.planName(),
                "YEARLY".equals(i.cycle()) ? Texts.t("yearly") : Texts.t("monthly"), Billing.day(i.periodFrom()), Billing.day(i.periodTo()));
        if (i.usdCdf() != null) {
            what += "\n" + Texts.t("USD %s at the BCC rate of %s CDF per USD",Billing.amount(i.priceUsd()),
                    i.usdCdf().setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        lines.addCell(ruled(what, NORMAL, Element.ALIGN_LEFT, false));
        lines.addCell(ruled(Billing.amount(i.subtotal()), NORMAL, Element.ALIGN_RIGHT, false));
        total(lines, Texts.t("Subtotal"), i.subtotal(), NORMAL);
        total(lines, "%s %s%%".formatted(i.taxLabel(), i.taxRate().stripTrailingZeros().toPlainString()), i.tax(), NORMAL);
        total(lines, Texts.t("Total %s", i.currency()), i.total(), BOLD);
        if (i.paid().signum() > 0 && "OPEN".equals(i.status())) {
            total(lines, Texts.t("Paid"), i.paid(), NORMAL);
            total(lines, Texts.t("Left to pay"), i.balance(), BOLD);
        }
        doc.add(lines);

        if (!"VOID".equals(i.status())) {
            Paragraph how = new Paragraph(Texts.t("How to pay"), BOLD);
            how.setSpacingBefore(18);
            doc.add(how);
            doc.add(new Paragraph(Texts.t("Give the reference %s with the payment.", i.number()), NORMAL));
            if (settings.bankDetails() != null) {
                doc.add(new Paragraph(Texts.t("Bank transfer"), SMALL));
                doc.add(new Paragraph(settings.bankDetails(), NORMAL));
            }
            if (settings.mobileMoney() != null) {
                doc.add(new Paragraph(Texts.t("Mobile money"), SMALL));
                doc.add(new Paragraph(settings.mobileMoney(), NORMAL));
            }
        } else if (i.voidReason() != null) {
            Paragraph why = new Paragraph(Texts.t("Cancelled: %s", i.voidReason()), NORMAL);
            why.setSpacingBefore(18);
            doc.add(why);
        }
        if (settings.invoiceNote() != null) {
            Paragraph note = new Paragraph(settings.invoiceNote(), SMALL);
            note.setSpacingBefore(18);
            doc.add(note);
        }
        doc.close();
        return bytes.toByteArray();
    }

    private static List<String> issuerLines(Issuer issuer) {
        List<String> out = new ArrayList<>();
        Company.Details d = issuer.details();
        Company.PayslipSettings c = issuer.contact();
        add(out, null, d.address());
        add(out, null, c.legalForm());
        add(out, "RCCM", d.rccm());
        add(out, Texts.t("Id. Nat."), d.nationalId());
        add(out, Texts.t("Tax number"), d.taxNumber());
        add(out, null, String.join(" · ", java.util.stream.Stream.of(c.phone(), c.email(), c.website())
                .filter(s -> s != null && !s.isBlank()).toList()));
        return out;
    }

    private static void add(List<String> out, String label, String value) {
        if (value != null && !value.isBlank()) {
            out.add(label == null ? value : label + " " + value);
        }
    }

    private static void total(PdfPTable t, String label, BigDecimal amount, Font font) {
        PdfPCell l = cell(label, font, Element.ALIGN_RIGHT);
        t.addCell(l);
        t.addCell(cell(Billing.amount(amount), font, Element.ALIGN_RIGHT));
    }

    private static PdfPTable table(float[] widths) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        return t;
    }

    private static PdfPCell cell(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(new Chunk(text, font)));
        c.setBorder(Rectangle.NO_BORDER);
        c.setHorizontalAlignment(align);
        c.setPadding(4);
        return c;
    }

    private static PdfPCell ruled(String text, Font font, int align, boolean shaded) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setHorizontalAlignment(align);
        c.setPadding(5);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(RULE);
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
}
