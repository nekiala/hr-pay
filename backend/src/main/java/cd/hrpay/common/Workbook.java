package cd.hrpay.common;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * An Excel workbook (.xlsx) of several sheets. Amounts are real numbers with two decimals, so they add up and pivot in
 * Excel; the header row is bold and stays visible on scroll; total rows are bold.
 */
public final class Workbook {

    /** A cell: a value, and whether it belongs to a total row. */
    private record Cell(Object value, boolean bold, boolean money) {}

    private record Sheet(String name, List<List<Cell>> rows, int[] widths) {}

    private final List<Sheet> sheets = new ArrayList<>();
    private final Set<String> names = new HashSet<>();
    private Sheet current;

    /** Starts a sheet; its name is cut to Excel's 31 characters and made unique. */
    public Workbook sheet(String name) {
        String clean = name.replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
        if (clean.isEmpty()) {
            clean = "Sheet";
        }
        clean = clean.length() > 31 ? clean.substring(0, 31) : clean;
        String unique = clean;
        for (int i = 2; !names.add(unique.toLowerCase()); i++) {
            String suffix = " " + i;
            unique = (clean.length() + suffix.length() > 31 ? clean.substring(0, 31 - suffix.length()) : clean) + suffix;
        }
        current = new Sheet(unique, new ArrayList<>(), new int[256]);
        sheets.add(current);
        return this;
    }

    /** Adds a row; a BigDecimal is an amount (two decimals), any other number a plain number. */
    public Workbook row(boolean bold, List<?> cells) {
        if (current == null) {
            sheet("Sheet1");
        }
        List<Cell> row = new ArrayList<>();
        for (int c = 0; c < cells.size() && c < 256; c++) {
            Object v = cells.get(c);
            row.add(new Cell(v, bold, v instanceof BigDecimal));
            String text = v == null ? "" : v instanceof BigDecimal b ? b.toPlainString() : v.toString();
            current.widths()[c] = Math.max(current.widths()[c], Math.min(50, text.length() + (v instanceof BigDecimal ? 3 : 0)));
        }
        current.rows().add(row);
        return this;
    }

    public void write(OutputStream target) throws IOException {
        if (sheets.isEmpty()) {
            sheet("Sheet1");
        }
        try (ZipOutputStream zip = new ZipOutputStream(target)) {
            StringBuilder types = new StringBuilder("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                    """);
            StringBuilder book = new StringBuilder("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets>""");
            StringBuilder rels = new StringBuilder("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    """);
            for (int i = 0; i < sheets.size(); i++) {
                int n = i + 1;
                types.append("<Override PartName=\"/xl/worksheets/sheet").append(n)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
                book.append("<sheet name=\"").append(escape(sheets.get(i).name())).append("\" sheetId=\"").append(n)
                        .append("\" r:id=\"rId").append(n).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(n)
                        .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                        .append(n).append(".xml\"/>");
            }
            rels.append("<Relationship Id=\"rId").append(sheets.size() + 1)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
                    .append("</Relationships>");
            put(zip, "[Content_Types].xml", types.append("</Types>").toString());
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>""");
            put(zip, "xl/workbook.xml", book.append("</sheets></workbook>").toString());
            put(zip, "xl/_rels/workbook.xml.rels", rels.toString());
            // Styles: 0 normal, 1 bold, 2 amount, 3 bold amount.
            put(zip, "xl/styles.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0.00"/></numFmts>
                    <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
                    <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
                    <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
                    <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                    <cellXfs count="4"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="164" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyNumberFormat="1"/></cellXfs>
                    </styleSheet>""");
            for (int i = 0; i < sheets.size(); i++) {
                put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(sheets.get(i)));
            }
        }
    }

    private static String sheetXml(Sheet sheet) {
        StringBuilder data = new StringBuilder();
        int columns = 1;
        for (int r = 0; r < sheet.rows().size(); r++) {
            List<Cell> cells = sheet.rows().get(r);
            columns = Math.max(columns, cells.size());
            data.append("<row r=\"").append(r + 1).append("\">");
            for (int c = 0; c < cells.size(); c++) {
                Cell cell = cells.get(c);
                Object v = cell.value();
                if (v == null || v.toString().isEmpty()) {
                    continue;
                }
                String ref = column(c) + (r + 1);
                int style = (cell.bold() ? 1 : 0) + (cell.money() ? 2 : 0);
                String s = style == 0 ? "" : " s=\"" + style + "\"";
                if (v instanceof Number) {
                    String text = v instanceof BigDecimal b ? b.toPlainString() : v.toString();
                    data.append("<c r=\"").append(ref).append('"').append(s).append("><v>").append(text).append("</v></c>");
                } else {
                    data.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(s).append("><is><t xml:space=\"preserve\">")
                            .append(escape(v.toString())).append("</t></is></c>");
                }
            }
            data.append("</row>");
        }
        StringBuilder cols = new StringBuilder("<cols>");
        for (int c = 0; c < columns; c++) {
            cols.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"")
                    .append(Math.max(8, sheet.widths()[c] + 2)).append("\" customWidth=\"1\"/>");
        }
        cols.append("</cols>");
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
                """ + cols + "<sheetData>" + data + "</sheetData></worksheet>";
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String column(int index) {
        StringBuilder s = new StringBuilder();
        for (int i = index + 1; i > 0; i = (i - 1) / 26) {
            s.insert(0, (char) ('A' + (i - 1) % 26));
        }
        return s.toString();
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> {
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }
}
