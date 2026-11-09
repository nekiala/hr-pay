package cd.hrpay.lifecycle;

import cd.hrpay.i18n.Texts;
import cd.hrpay.payment.Company;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;

/**
 * A Word (.docx) copy of a contract or letter, laid out like its PDF: the company's heading and legal numbers in the
 * page header and footer, the title, the articles, the signatures, the notes and the annex. Written directly as
 * Office Open XML, so no extra library is needed. The copy is for HR to adjust by hand; the PDF stays the document
 * that is signed.
 */
@Component
public class DocxWriter {

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String TEAL = "0F766E";
    private static final String MUTED = "5B6472";

    private final Company company;

    public DocxWriter(Company company) {
        this.company = company;
    }

    public byte[] write(ContractSheet c) {
        Company.Details d = company.get();
        Company.PayslipSettings s = company.payslipSettings();
        Company.Logo logo = company.logo().orElse(null);
        String logoExt = logo == null ? null : logo.contentType().equals("image/png") ? "png" : "jpeg";
        int[] logoSize = logo == null ? null : size(logo.data());
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                put(zip, "[Content_Types].xml", contentTypes(logoExt));
                put(zip, "_rels/.rels", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                        </Relationships>""");
                put(zip, "word/_rels/document.xml.rels", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                        <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>
                        <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>
                        </Relationships>""");
                if (logo != null) {
                    put(zip, "word/_rels/header1.xml.rels", """
                            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                            <Relationship Id="rIdLogo" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/logo.%s"/>
                            </Relationships>""".formatted(logoExt));
                    zip.putNextEntry(new ZipEntry("word/media/logo." + logoExt));
                    zip.write(logo.data());
                    zip.closeEntry();
                }
                put(zip, "word/styles.xml", STYLES);
                put(zip, "word/header1.xml", header(d, s, logo != null, logoSize));
                put(zip, "word/footer1.xml", footer(d));
                put(zip, "word/document.xml", document(c));
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("The Word copy cannot be written", e);
        }
    }

    // ---- parts ------------------------------------------------------------------------------------------------------

    private static String contentTypes(String logoExt) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                %s
                <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
                <Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>
                <Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>
                </Types>""".formatted(logoExt == null ? ""
                : "<Default Extension=\"%s\" ContentType=\"image/%s\"/>".formatted(logoExt, logoExt));
    }

    private static final String STYLES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
            <w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:cs="Arial"/><w:sz w:val="19"/><w:szCs w:val="19"/><w:lang w:val="fr-FR"/></w:rPr></w:rPrDefault>
            <w:pPrDefault><w:pPr><w:spacing w:after="100" w:line="264" w:lineRule="auto"/><w:jc w:val="both"/></w:pPr></w:pPrDefault></w:docDefaults>
            <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
            <w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:pPr><w:jc w:val="center"/><w:spacing w:before="240" w:after="40"/></w:pPr><w:rPr><w:b/><w:sz w:val="28"/></w:rPr></w:style>
            <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="140" w:after="40"/><w:jc w:val="left"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:sz w:val="20"/></w:rPr></w:style>
            <w:style w:type="table" w:default="1" w:styleId="TableNormal"><w:name w:val="Normal Table"/><w:tblPr><w:tblCellMar><w:left w:w="80" w:type="dxa"/><w:right w:w="80" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style>
            </w:styles>""";

    private static String header(Company.Details d, Company.PayslipSettings s, boolean logo, int[] size) {
        StringBuilder x = new StringBuilder(open("w:hdr"));
        StringBuilder lines = new StringBuilder();
        lines.append(para(run(d.name(), true, 26, null), "right", 0));
        for (String l : new String[] {s.legalForm(), d.address(),
                join(" · ", s.phone() == null ? null : Texts.t("Tel. %s", s.phone()), s.email(), s.website())}) {
            if (l != null && !l.isBlank()) {
                lines.append(para(run(l, false, 16, MUTED), "right", 0));
            }
        }
        if (logo) {
            // Scaled to fit 130 x 60 points, as on the PDF.
            double scale = Math.min(130.0 / size[0], 60.0 / size[1]);
            long cx = Math.round(size[0] * scale * 12700);
            long cy = Math.round(size[1] * scale * 12700);
            String picture = """
                    <w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="%d" cy="%d"/><wp:docPr id="1" name="Logo"/>
                    <a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
                    <pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:nvPicPr><pic:cNvPr id="1" name="Logo"/><pic:cNvPicPr/></pic:nvPicPr>
                    <pic:blipFill><a:blip r:embed="rIdLogo"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
                    <pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="%d" cy="%d"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>
                    </a:graphicData></a:graphic></wp:inline></w:drawing></w:r>""".formatted(cx, cy, cx, cy);
            x.append("<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders><w:bottom w:val=\"single\" w:sz=\"10\" w:color=\"")
                    .append(TEAL).append("\"/></w:tblBorders></w:tblPr><w:tblGrid><w:gridCol w:w=\"2800\"/><w:gridCol w:w=\"6500\"/></w:tblGrid><w:tr>")
                    .append("<w:tc><w:tcPr><w:vAlign w:val=\"center\"/></w:tcPr><w:p>").append(picture).append("</w:p></w:tc>")
                    .append("<w:tc><w:tcPr><w:vAlign w:val=\"bottom\"/></w:tcPr>").append(lines).append("</w:tc></w:tr></w:tbl><w:p/>");
        } else {
            x.append(lines.toString().replace("<w:jc w:val=\"right\"/>", "<w:jc w:val=\"left\"/>"));
            x.append("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"10\" w:space=\"1\" w:color=\"").append(TEAL)
                    .append("\"/></w:pBdr><w:spacing w:after=\"0\"/></w:pPr></w:p>");
        }
        return x.append("</w:hdr>").toString();
    }

    private static String footer(Company.Details d) {
        String legal = join(" · ", d.rccm() == null ? null : "RCCM " + d.rccm(),
                d.nationalId() == null ? null : Texts.t("Nat. ID %s", d.nationalId()),
                d.taxNumber() == null ? null : Texts.t("Tax no. %s", d.taxNumber()),
                d.cnssNumber() == null ? null : Texts.t("CNSS no. %s", d.cnssNumber()));
        String who = join(" · ", d.name(), d.address());
        StringBuilder x = new StringBuilder(open("w:ftr"));
        x.append("<w:p><w:pPr><w:pBdr><w:top w:val=\"single\" w:sz=\"4\" w:space=\"1\" w:color=\"E3E6EA\"/></w:pBdr><w:jc w:val=\"center\"/><w:spacing w:after=\"0\"/></w:pPr>")
                .append(run(who == null ? "" : who, false, 15, MUTED)).append("</w:p>");
        if (legal != null) {
            x.append(para(run(legal, false, 15, MUTED), "center", 0));
        }
        String page = Texts.t("Page") + " ";
        x.append("<w:p><w:pPr><w:jc w:val=\"right\"/><w:spacing w:after=\"0\"/></w:pPr>").append(run(page, false, 15, MUTED))
                .append(field("PAGE")).append(run(" / ", false, 15, MUTED)).append(field("NUMPAGES")).append("</w:p>");
        return x.append("</w:ftr>").toString();
    }

    private static String field(String code) {
        return "<w:r><w:rPr><w:color w:val=\"" + MUTED + "\"/><w:sz w:val=\"15\"/></w:rPr><w:fldChar w:fldCharType=\"begin\"/></w:r>"
                + "<w:r><w:rPr><w:color w:val=\"" + MUTED + "\"/><w:sz w:val=\"15\"/></w:rPr><w:instrText xml:space=\"preserve\"> " + code
                + " </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:rPr><w:sz w:val=\"15\"/></w:rPr><w:t>1</w:t></w:r>"
                + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>";
    }

    private static String document(ContractSheet c) {
        StringBuilder x = new StringBuilder(open("w:document")).append("<w:body>");
        x.append("<w:p><w:pPr><w:pStyle w:val=\"Title\"/></w:pPr>").append(run(c.title().toUpperCase(Texts.locale()), true, 28, null))
                .append("</w:p>");
        String ref = c.number() == null ? c.reference()
                : c.reference() == null ? Texts.t("No. %s", c.number()) : c.reference() + " · " + Texts.t("No. %s", c.number());
        if (ref != null) {
            x.append(para(run(ref, false, 16, MUTED), "center", 200));
        }
        blocks(x, c.body(), 19, null);
        if (c.placeAndDate() != null) {
            x.append("<w:p><w:pPr><w:jc w:val=\"right\"/><w:spacing w:before=\"200\"/></w:pPr>").append(run(c.placeAndDate(), false, 19, null))
                    .append("</w:p>");
        }
        // Signatures, side by side.
        x.append("<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/></w:tblPr><w:tblGrid><w:gridCol w:w=\"4650\"/>")
                .append(c.employeeSigner() == null ? "" : "<w:gridCol w:w=\"4650\"/>").append("</w:tblGrid><w:tr><w:trPr><w:cantSplit/></w:trPr>");
        x.append(signCell(c.employerHeading(), List.of(new LifecyclePdf.Line(Texts.t("Name"), c.employerSigner()),
                new LifecyclePdf.Line(Texts.t("Capacity"), c.employerCapacity())), Texts.t("Signature and stamp:")));
        if (c.employeeSigner() != null) {
            x.append(signCell(c.employeeHeading(), List.of(new LifecyclePdf.Line(Texts.t("Name"), c.employeeSigner()),
                    new LifecyclePdf.Line(Texts.t("Mention"), c.employeeMention())), Texts.t("Signature:")));
        }
        x.append("</w:tr></w:tbl>");
        if (c.notes() != null && !c.notes().isBlank()) {
            x.append("<w:p/>");
            blocks(x, c.notes(), 15, "374151");
        }
        if (!c.annex().isEmpty()) {
            x.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
            x.append(para(run(Texts.t("Annex: identification of the parties"), true, 22, null), "center", 40));
            x.append(para(run(Texts.t("%s · Labour Code art. 212", c.title()), false, 16, MUTED), "center", 200));
            for (ContractSheet.Section s : c.annex()) {
                table(x, s.heading(), s.lines());
            }
            List<LifecyclePdf.Line> control = c.controlLines();
            if (!control.isEmpty() || c.approvalHeading() != null) {
                table(x, Texts.t("Document control and adoption"), control);
                if (c.approvalHeading() != null) {
                    x.append(para(run(c.approvalHeading(), true, 17, null), "left", 0));
                    x.append(para(run(join(", ", c.employerSigner(), c.employerCapacity()), false, 17, null), "left", 0));
                    x.append(para(run(Texts.t("Date: ____ / ____ / ________      Signature and stamp: ______________________"), false, 17, null),
                            "left", 0));
                }
            }
        }
        x.append("<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rId2\"/><w:footerReference w:type=\"default\" r:id=\"rId3\"/>")
                .append("<w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1700\" w:right=\"1130\" w:bottom=\"1300\" w:left=\"1130\" ")
                .append("w:header=\"500\" w:footer=\"400\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>");
        return x.toString();
    }

    private static void blocks(StringBuilder x, String text, int size, String color) {
        for (ContractSheet.Block b : ContractSheet.blocks(text)) {
            if (b.heading()) {
                x.append("<w:p><w:pPr><w:pStyle w:val=\"Heading2\"/></w:pPr>").append(run(b.text(), true, size + 1, color)).append("</w:p>");
            } else {
                StringBuilder runs = new StringBuilder();
                String[] lines = b.text().split("\n");
                for (int i = 0; i < lines.length; i++) {
                    if (i > 0) {
                        runs.append("<w:r><w:br/></w:r>");
                    }
                    runs.append(run(lines[i], false, size, color));
                }
                x.append("<w:p>").append(runs).append("</w:p>");
            }
        }
    }

    private static String signCell(String heading, List<LifecyclePdf.Line> lines, String signature) {
        StringBuilder x = new StringBuilder("<w:tc><w:tcPr><w:tcW w:w=\"2500\" w:type=\"pct\"/></w:tcPr>");
        x.append(para(run(heading, true, 19, null), "left", 60));
        for (LifecyclePdf.Line l : lines) {
            if (l.value() != null) {
                x.append("<w:p><w:pPr><w:jc w:val=\"left\"/><w:spacing w:after=\"40\"/></w:pPr>").append(run(l.label() + " : ", true, 17, "374151"))
                        .append(run(l.value(), false, 17, null)).append("</w:p>");
            }
        }
        x.append(para(run(signature, true, 17, "374151"), "left", 900));
        return x.append("</w:tc>").toString();
    }

    private static void table(StringBuilder x, String heading, List<LifecyclePdf.Line> lines) {
        x.append("<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders><w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"E3E6EA\"/>")
                .append("<w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"E3E6EA\"/></w:tblBorders></w:tblPr>")
                .append("<w:tblGrid><w:gridCol w:w=\"2900\"/><w:gridCol w:w=\"6400\"/></w:tblGrid>");
        x.append("<w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"2\"/><w:tcBorders><w:bottom w:val=\"single\" w:sz=\"8\" w:color=\"").append(TEAL)
                .append("\"/></w:tcBorders></w:tcPr>").append(para(run(heading, true, 19, null), "left", 40)).append("</w:tc></w:tr>");
        for (LifecyclePdf.Line l : lines) {
            x.append("<w:tr><w:tc><w:tcPr><w:tcW w:w=\"2900\" w:type=\"dxa\"/></w:tcPr>").append(para(run(l.label(), true, 17, "374151"), "left", 20))
                    .append("</w:tc><w:tc><w:tcPr><w:tcW w:w=\"6400\" w:type=\"dxa\"/></w:tcPr>")
                    .append(para(run(l.value() == null ? "—" : l.value(), false, 17, null), "left", 20)).append("</w:tc></w:tr>");
        }
        x.append("</w:tbl><w:p/>");
    }

    // ---- small helpers -----------------------------------------------------------------------------------------

    private static String open(String root) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><" + root + " xmlns:w=\"" + W + "\" xmlns:r=\"" + R
                + "\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\">";
    }

    private static String para(String runs, String align, int after) {
        return "<w:p><w:pPr><w:jc w:val=\"" + align + "\"/><w:spacing w:after=\"" + after + "\"/></w:pPr>" + runs + "</w:p>";
    }

    private static String run(String text, boolean bold, int halfPoints, String color) {
        return "<w:r><w:rPr>" + (bold ? "<w:b/>" : "") + (color == null ? "" : "<w:color w:val=\"" + color + "\"/>")
                + "<w:sz w:val=\"" + halfPoints + "\"/><w:szCs w:val=\"" + halfPoints + "\"/></w:rPr><w:t xml:space=\"preserve\">"
                + escape(text) + "</w:t></w:r>";
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> {
                    // Characters XML 1.0 does not allow are dropped.
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String join(String sep, String... parts) {
        String s = Stream.of(parts).filter(p -> p != null && !p.isBlank()).map(String::trim).collect(Collectors.joining(sep));
        return s.isEmpty() ? null : s;
    }

    private static int[] size(byte[] image) {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(image));
            return img == null ? new int[] {130, 60} : new int[] {Math.max(1, img.getWidth()), Math.max(1, img.getHeight())};
        } catch (IOException e) {
            return new int[] {130, 60};
        }
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.strip().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
