package cd.hrpay.mission;

import cd.hrpay.common.AppProperties;
import cd.hrpay.payment.Company;
import cd.hrpay.payment.Letterhead;
import cd.hrpay.security.DataCrypto;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfTemplate;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The ordre de mission: on the company letterhead, numbered, with the traveller, the destination, the dates and who
 * pays, the usual request to the authorities, who approved it, boxes for the departure and arrival stamps, and a QR code
 * that opens the public check page. In French; an international mission adds the same page in English.
 */
@Component
public class OrderPdf {

    private static final Color MUTED = new Color(0x5b, 0x64, 0x72);
    private static final Font TITLE = new Font(Font.HELVETICA, 17, Font.BOLD);
    private static final Font NORMAL = new Font(Font.HELVETICA, 10, Font.NORMAL);
    private static final Font BOLD = new Font(Font.HELVETICA, 10, Font.BOLD);
    private static final Font SMALL = new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED);

    private static final Map<String, String> FR = Map.ofEntries(
            Map.entry("title", "ORDRE DE MISSION"), Map.entry("no", "N° %s"), Map.entry("amendment", "Avenant n° %s : %s"),
            Map.entry("intro", "La direction de %s ordonne à :"), Map.entry("name", "Nom"), Map.entry("reg", "Matricule"),
            Map.entry("job", "Fonction"), Map.entry("grade", "Grade"), Map.entry("unit", "Service"),
            Map.entry("nationality", "Nationalité"), Map.entry("id", "Pièce d'identité"),
            Map.entry("goes", "de se rendre en mission :"), Map.entry("dest", "Destination"),
            Map.entry("from", "Départ"), Map.entry("to", "Retour"), Map.entry("length", "Durée"),
            Map.entry("days", "%s jour(s)"), Map.entry("purpose", "Objet de la mission"), Map.entry("transport", "Moyen de transport"),
            Map.entry("costs", "Frais de mission"), Map.entry("authorities",
                    "Les autorités civiles et militaires sont priées de faciliter au porteur du présent ordre l'accomplissement de sa mission."),
            Map.entry("done", "Fait à %s, le %s"), Map.entry("doneNoPlace", "Fait le %s"),
            Map.entry("approved", "Approuvé par %s le %s"), Map.entry("firstApproved", "Visé par %s le %s"),
            Map.entry("employer", "Pour l'employeur"), Map.entry("departure", "Visa de départ"), Map.entry("arrival", "Visa d'arrivée"),
            Map.entry("stamp", "Cachet, date et signature"),
            Map.entry("check", "Vérifiez cet ordre de mission en scannant le code ou sur %s"),
            Map.entry("PLANE", "Avion"), Map.entry("ROAD", "Route"), Map.entry("BOAT", "Bateau"), Map.entry("TRAIN", "Train"),
            Map.entry("COMPANY_CAR", "Véhicule de l'entreprise"), Map.entry("OTHER", "Autre"),
            Map.entry("COMPANY", "À la charge de %s"), Map.entry("HOST", "À la charge de l'organisation d'accueil"),
            Map.entry("SPONSOR", "À la charge du sponsor"), Map.entry("SHARED", "Partagés entre %s et l'organisation d'accueil"),
            Map.entry("NATIONAL_ID", "Carte d'identité"), Map.entry("ELECTOR_CARD", "Carte d'électeur"),
            Map.entry("PASSPORT", "Passeport"), Map.entry("RESIDENCE_PERMIT", "Carte de résident"),
            Map.entry("DRIVING_LICENCE", "Permis de conduire"), Map.entry("OTHER_ID", "Pièce"));

    private static final Map<String, String> EN = Map.ofEntries(
            Map.entry("title", "MISSION ORDER"), Map.entry("no", "No. %s"), Map.entry("amendment", "Amendment no. %s: %s"),
            Map.entry("intro", "The management of %s instructs:"), Map.entry("name", "Name"), Map.entry("reg", "Staff number"),
            Map.entry("job", "Position"), Map.entry("grade", "Grade"), Map.entry("unit", "Department"),
            Map.entry("nationality", "Nationality"), Map.entry("id", "Identity document"),
            Map.entry("goes", "to travel on a work mission:"), Map.entry("dest", "Destination"),
            Map.entry("from", "Departure"), Map.entry("to", "Return"), Map.entry("length", "Duration"),
            Map.entry("days", "%s day(s)"), Map.entry("purpose", "Purpose"), Map.entry("transport", "Means of transport"),
            Map.entry("costs", "Mission costs"), Map.entry("authorities",
                    "All civil and military authorities are kindly requested to assist the bearer of this order in carrying out the mission."),
            Map.entry("done", "Issued in %s on %s"), Map.entry("doneNoPlace", "Issued on %s"),
            Map.entry("approved", "Approved by %s on %s"), Map.entry("firstApproved", "Endorsed by %s on %s"),
            Map.entry("employer", "For the employer"), Map.entry("departure", "Departure stamp"), Map.entry("arrival", "Arrival stamp"),
            Map.entry("stamp", "Stamp, date and signature"),
            Map.entry("check", "Check this mission order by scanning the code or at %s"),
            Map.entry("PLANE", "Air"), Map.entry("ROAD", "Road"), Map.entry("BOAT", "Boat"), Map.entry("TRAIN", "Train"),
            Map.entry("COMPANY_CAR", "Company vehicle"), Map.entry("OTHER", "Other"),
            Map.entry("COMPANY", "Paid by %s"), Map.entry("HOST", "Paid by the host organisation"),
            Map.entry("SPONSOR", "Paid by the sponsor"), Map.entry("SHARED", "Shared between %s and the host organisation"),
            Map.entry("NATIONAL_ID", "Identity card"), Map.entry("ELECTOR_CARD", "Voter card"),
            Map.entry("PASSPORT", "Passport"), Map.entry("RESIDENCE_PERMIT", "Residence permit"),
            Map.entry("DRIVING_LICENCE", "Driving licence"), Map.entry("OTHER_ID", "Document"));

    private final TravelMissions missions;
    private final Letterhead letterhead;
    private final Company company;
    private final DataCrypto crypto;
    private final AppProperties properties;
    private final JdbcClient jdbc;

    public OrderPdf(TravelMissions missions, Letterhead letterhead, Company company, DataCrypto crypto,
                    AppProperties properties, JdbcClient jdbc) {
        this.missions = missions;
        this.letterhead = letterhead;
        this.company = company;
        this.crypto = crypto;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    /** The public page the QR code opens. */
    public String checkUrl(java.util.UUID token) {
        return properties.publicUrl().replaceAll("/+$", "") + "/mission-check/" + token;
    }

    public byte[] render(long missionId) {
        TravelMissions.Row r = missions.order(missionId);
        TravelMissions.OrderPerson person = missions.person(r.userId(), r.startDate(), crypto);
        String url = checkUrl(missions.tokenOf(missionId));
        Company.Details details = company.get();
        Company.Representative rep = company.representative();
        String extra = jdbc.sql("select coalesce(nationality_fr, name_fr) || '|' || coalesce(nationality_en, name_en) from country where code = ?")
                .param(person.nationality() == null ? "" : person.nationality()).query(String.class).optional().orElse(null);
        String[] nationality = extra == null ? new String[] {person.nationality(), person.nationality()} : extra.split("\\|", 2);
        String[] country = jdbc.sql("select name_fr || '|' || name_en from country where code = ?").param(r.countryCode())
                .query(String.class).single().split("\\|", 2);
        String approver = nameOf(r.decidedBy());
        String endorser = r.firstApprovedBy() != null && !r.firstApprovedBy().equalsIgnoreCase(r.decidedBy()) ? nameOf(r.firstApprovedBy()) : null;

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Letterhead.Head head = letterhead.read();
        Document doc = new Document(PageSize.A4, 48, 48, 36, Letterhead.Head.bottomMargin());
        PdfWriter writer = PdfWriter.getInstance(doc, bytes);
        head.footer(writer);
        doc.addTitle("Ordre de mission " + r.number());
        doc.addCreator("HR Pay");
        doc.open();
        page(doc, writer, head, FR, Locale.FRENCH, r, person, nationality[0], country[0], details, rep, approver, endorser, url);
        if ("INTERNATIONAL".equals(r.scope())) {
            doc.newPage();
            page(doc, writer, head, EN, Locale.ENGLISH, r, person, nationality.length > 1 ? nationality[1] : nationality[0],
                    country.length > 1 ? country[1] : country[0], details, rep, approver, endorser, url);
        }
        doc.close();
        return bytes.toByteArray();
    }

    private String nameOf(String email) {
        if (email == null) {
            return null;
        }
        return jdbc.sql("select person_name(first_name, middle_name, last_name) from app_user where lower(email) = lower(?)")
                .param(email).query(String.class).optional().orElse(email);
    }

    private static String day(LocalDate d, Locale locale) {
        return d.format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale));
    }

    private static PdfPCell cell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
        c.setPadding(5);
        c.setBorderColor(new Color(0xd1, 0xd5, 0xdb));
        return c;
    }

    private static void row(PdfPTable t, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        t.addCell(cell(label, NORMAL));
        t.addCell(cell(value, BOLD));
    }

    private void page(Document doc, PdfWriter writer, Letterhead.Head head, Map<String, String> L, Locale locale,
                      TravelMissions.Row r, TravelMissions.OrderPerson p, String nationality, String country,
                      Company.Details details, Company.Representative rep, String approver, String endorser, String url) {
        head.draw(doc);
        String companyName = details.name() == null ? "" : details.name();
        Paragraph title = new Paragraph(L.get("title"), TITLE);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingBefore(16);
        doc.add(title);
        Paragraph num = new Paragraph(L.get("no").formatted(r.number()), BOLD);
        num.setAlignment(Element.ALIGN_CENTER);
        doc.add(num);
        if (r.amendment() > 0) {
            Paragraph am = new Paragraph(L.get("amendment").formatted(r.amendment(), r.amendmentReason() == null ? "" : r.amendmentReason()), SMALL);
            am.setAlignment(Element.ALIGN_CENTER);
            doc.add(am);
        }

        Paragraph intro = new Paragraph(L.get("intro").formatted(companyName), NORMAL);
        intro.setSpacingBefore(14);
        intro.setSpacingAfter(6);
        doc.add(intro);
        PdfPTable who = new PdfPTable(new float[] {1.3f, 3f});
        who.setWidthPercentage(100);
        row(who, L.get("name"), r.employeeName());
        row(who, L.get("reg"), r.registrationNo());
        row(who, L.get("job"), p.occupation());
        row(who, L.get("grade"), p.grade());
        row(who, L.get("unit"), p.unit());
        row(who, L.get("nationality"), nationality);
        if (p.idNumber() != null && !p.idNumber().isBlank()) {
            String type = p.idType() == null ? null : L.getOrDefault(p.idType(), L.get("OTHER_ID"));
            row(who, L.get("id"), (type == null ? "" : type + " ") + p.idNumber());
        }
        doc.add(who);

        Paragraph goes = new Paragraph(L.get("goes"), NORMAL);
        goes.setSpacingBefore(10);
        goes.setSpacingAfter(6);
        doc.add(goes);
        PdfPTable what = new PdfPTable(new float[] {1.3f, 3f});
        what.setWidthPercentage(100);
        int days = (int) java.time.temporal.ChronoUnit.DAYS.between(r.startDate(), r.endDate()) + 1;
        row(what, L.get("dest"), r.city() + ", " + country);
        row(what, L.get("from"), day(r.startDate(), locale));
        row(what, L.get("to"), day(r.endDate(), locale));
        row(what, L.get("length"), L.get("days").formatted(days));
        row(what, L.get("purpose"), r.purpose());
        row(what, L.get("transport"), L.getOrDefault(r.transport(), r.transport()));
        String costs = r.costsBy() == null ? null : L.get(r.costsBy()).contains("%s") ? L.get(r.costsBy()).formatted(companyName) : L.get(r.costsBy());
        row(what, L.get("costs"), costs == null ? null : costs + (r.costsNote() == null ? "" : ". " + r.costsNote()));
        doc.add(what);

        Paragraph authorities = new Paragraph(L.get("authorities"), NORMAL);
        authorities.setSpacingBefore(12);
        doc.add(authorities);

        LocalDate issued = r.decidedAt() == null ? LocalDate.now() : r.decidedAt().atZoneSameInstant(ZoneId.of("Africa/Kinshasa")).toLocalDate();
        String place = rep.signingPlace();
        Paragraph done = new Paragraph(place == null || place.isBlank() ? L.get("doneNoPlace").formatted(day(issued, locale))
                : L.get("done").formatted(place, day(issued, locale)), NORMAL);
        done.setSpacingBefore(12);
        doc.add(done);

        // Who approved, beside the QR code.
        PdfPTable sign = new PdfPTable(new float[] {3f, 1.2f});
        sign.setWidthPercentage(100);
        sign.setSpacingBefore(10);
        PdfPCell left = new PdfPCell();
        left.setBorder(Rectangle.NO_BORDER);
        left.addElement(new Paragraph(L.get("employer"), BOLD));
        if (rep.name() != null && !rep.name().isBlank()) {
            left.addElement(new Paragraph(rep.name() + (rep.title() == null ? "" : ", " + rep.title()), NORMAL));
        }
        if (endorser != null && r.firstApprovedAt() != null) {
            left.addElement(new Paragraph(L.get("firstApproved").formatted(endorser,
                    day(r.firstApprovedAt().atZoneSameInstant(ZoneId.of("Africa/Kinshasa")).toLocalDate(), locale)), SMALL));
        }
        if (approver != null) {
            left.addElement(new Paragraph(L.get("approved").formatted(approver, day(issued, locale)), SMALL));
        }
        sign.addCell(left);
        PdfPCell qr = new PdfPCell(qr(writer, url, 96), false);
        qr.setBorder(Rectangle.NO_BORDER);
        qr.setHorizontalAlignment(Element.ALIGN_RIGHT);
        sign.addCell(qr);
        doc.add(sign);
        Paragraph check = new Paragraph(L.get("check").formatted(url), SMALL);
        check.setAlignment(Element.ALIGN_RIGHT);
        doc.add(check);

        // Boxes for the stamps at departure and arrival.
        PdfPTable stamps = new PdfPTable(2);
        stamps.setWidthPercentage(100);
        stamps.setSpacingBefore(14);
        for (String k : new String[] {"departure", "arrival"}) {
            PdfPCell c = new PdfPCell();
            c.setFixedHeight(86);
            c.setPadding(6);
            c.setBorderColor(new Color(0x9c, 0xa3, 0xaf));
            c.addElement(new Paragraph(L.get(k), BOLD));
            c.addElement(new Paragraph(L.get("stamp"), SMALL));
            stamps.addCell(c);
        }
        doc.add(stamps);
    }

    /** A QR code drawn as vector squares (no image library needed). */
    static Image qr(PdfWriter writer, String text, float size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 1));
            PdfContentByte cb = writer.getDirectContent();
            PdfTemplate tpl = cb.createTemplate(size, size);
            float unit = size / m.getWidth();
            tpl.setColorFill(Color.BLACK);
            for (int y = 0; y < m.getHeight(); y++) {
                for (int x = 0; x < m.getWidth(); x++) {
                    if (m.get(x, y)) {
                        tpl.rectangle(x * unit, size - (y + 1) * unit, unit, unit);
                    }
                }
            }
            tpl.fill();
            return Image.getInstance(tpl);
        } catch (Exception e) {
            throw new IllegalStateException("Could not draw the QR code", e);
        }
    }
}
