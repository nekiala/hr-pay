package cd.hrpay.position;

import cd.hrpay.i18n.Texts;
import cd.hrpay.payment.Company;
import cd.hrpay.payment.Letterhead;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The fiche de poste on the company letterhead: blank, or for a person with the version their contract was signed with. */
@Component
public class JobSheetPdf {

    private static final Color MUTED = new Color(0x5b, 0x64, 0x72);
    private static final Color SHADE = new Color(0xf3, 0xf4, 0xf6);
    private static final Font TITLE = new Font(Font.HELVETICA, 16, Font.BOLD);
    private static final Font HEAD = new Font(Font.HELVETICA, 11, Font.BOLD);
    private static final Font NORMAL = new Font(Font.HELVETICA, 10, Font.NORMAL);
    private static final Font BOLD = new Font(Font.HELVETICA, 10, Font.BOLD);
    private static final Font SMALL = new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED);

    private final JobCatalogue jobs;
    private final Letterhead letterhead;
    private final Company company;
    private final JdbcClient jdbc;

    public JobSheetPdf(JobCatalogue jobs, Letterhead letterhead, Company company, JdbcClient jdbc) {
        this.jobs = jobs;
        this.letterhead = letterhead;
        this.company = company;
        this.jdbc = jdbc;
    }

    /** The current description of a job, or for a person (userId not null) the one of their contract. */
    public byte[] render(long occupationId, Long userId, Integer version) {
        return Texts.in(company.language(), () -> build(occupationId, userId, version));
    }

    private byte[] build(long occupationId, Long userId, Integer version) {
        JobCatalogue.Job job = jobs.job(occupationId);
        JobCatalogue.Version v = jobs.version(occupationId, version).orElse(null);
        String[] person = userId == null ? null : jdbc.sql("""
                select person_name(u.first_name, u.middle_name, u.last_name), u.registration_no,
                       (select ou.name from org_assignment a join org_unit ou on ou.id = a.unit_id
                        where a.user_id = u.id and a.valid_to is null)
                from app_user u where u.id = ?""").param(userId)
                .query((rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}).optional().orElse(null);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Letterhead.Head head = letterhead.read();
        Document doc = new Document(PageSize.A4, 48, 48, 36, Letterhead.Head.bottomMargin());
        PdfWriter writer = PdfWriter.getInstance(doc, bytes);
        head.footer(writer);
        doc.addTitle(Texts.t("Job description") + " " + job.name());
        doc.addCreator("HR Pay");
        doc.open();
        head.draw(doc);

        Paragraph title = new Paragraph(Texts.t("JOB DESCRIPTION"), TITLE);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingBefore(14);
        doc.add(title);
        Paragraph name = new Paragraph(v != null ? v.name() : job.name(), HEAD);
        name.setAlignment(Element.ALIGN_CENTER);
        name.setSpacingAfter(12);
        doc.add(name);

        PdfPTable id = new PdfPTable(new float[] {1.3f, 3f});
        id.setWidthPercentage(100);
        if (person != null) {
            row(id, Texts.t("Name"), person[0]);
            row(id, Texts.t("Registration no."), person[1]);
            row(id, Texts.t("Unit"), person[2]);
        }
        row(id, Texts.t("Job code"), job.code());
        row(id, Texts.t("Job family"), job.familyName());
        row(id, Texts.t("Reports to"), job.reportsTo());
        String min = v != null ? v.minGrade() : job.minGrade();
        String max = v != null ? v.maxGrade() : job.maxGrade();
        row(id, Texts.t("Grades"), min == null && max == null ? null
                : min != null && max != null && !min.equals(max) ? Texts.t("%s to %s", min, max) : min != null ? min : max);
        row(id, Texts.t("Category"), job.category());
        if (v != null ? v.unskilled() : job.unskilled()) {
            row(id, Texts.t("Qualification"), Texts.t("Unskilled job: trial of one month at most (Labour Code art. 43)"));
        }
        doc.add(id);

        section(doc, Texts.t("Purpose of the job"), v != null ? v.purpose() : job.purpose(), false);
        section(doc, Texts.t("Main duties"), v != null ? v.duties() : job.duties(), true);
        section(doc, Texts.t("Education"), v != null ? v.education() : job.education(), false);
        section(doc, Texts.t("Experience"), v != null ? v.experience() : job.experience(), false);
        section(doc, Texts.t("Skills"), v != null ? v.skills() : job.skills(), true);
        section(doc, Texts.t("Working conditions"), v != null ? v.conditions() : job.conditions(), false);

        if (person != null) {
            PdfPTable sign = new PdfPTable(2);
            sign.setWidthPercentage(100);
            sign.setSpacingBefore(28);
            for (String who : new String[] {Texts.t("The employee"), Texts.t("For the employer")}) {
                PdfPCell c = new PdfPCell(new Phrase(who + "\n\n\n\n", BOLD));
                c.setBorder(Rectangle.NO_BORDER);
                sign.addCell(c);
            }
            doc.add(sign);
        }
        Paragraph foot = new Paragraph(v == null ? Texts.t("Description not saved yet.")
                : Texts.t("Version %d of %s", v.version(), Texts.day(v.savedAt().toLocalDate())), SMALL);
        foot.setSpacingBefore(18);
        doc.add(foot);
        doc.close();
        return bytes.toByteArray();
    }

    private static void row(PdfPTable t, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        PdfPCell l = new PdfPCell(new Phrase(label, BOLD));
        l.setBackgroundColor(SHADE);
        l.setPadding(5);
        l.setBorderColor(new Color(0xe3, 0xe6, 0xea));
        PdfPCell r = new PdfPCell(new Phrase(value, NORMAL));
        r.setPadding(5);
        r.setBorderColor(new Color(0xe3, 0xe6, 0xea));
        t.addCell(l);
        t.addCell(r);
    }

    /** A heading and its text; with lines, each line of the text is a bullet. */
    private static void section(Document doc, String heading, String text, boolean lines) {
        if (text == null || text.isBlank()) {
            return;
        }
        Paragraph h = new Paragraph(heading, HEAD);
        h.setSpacingBefore(12);
        h.setSpacingAfter(4);
        doc.add(h);
        if (!lines) {
            doc.add(new Paragraph(text.strip(), NORMAL));
            return;
        }
        Arrays.stream(text.split("\\R")).map(String::strip).map(s -> s.replaceFirst("^[-•*]\\s*", ""))
                .filter(s -> !s.isEmpty()).forEach(s -> {
                    Paragraph p = new Paragraph("•  " + s, NORMAL);
                    p.setIndentationLeft(10);
                    p.setFirstLineIndent(-8);
                    p.setSpacingAfter(2);
                    doc.add(p);
                });
    }
}
