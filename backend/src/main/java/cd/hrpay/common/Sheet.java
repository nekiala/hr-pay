package cd.hrpay.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A table written as CSV or as an Excel workbook (.xlsx), from the same rows. The first row is the header. In the
 * workbook, numbers become number cells (so they add up in Excel), the header is bold and stays visible on scroll.
 */
public abstract class Sheet {

    public abstract void row(Object... cells);

    public abstract void finish() throws IOException;

    /** The format from the request's path: "....xlsx" gives a workbook, anything else CSV. */
    public static Sheet open(HttpServletRequest request, HttpServletResponse response, String baseName) throws IOException {
        return open(response, baseName, request.getRequestURI().endsWith(".xlsx") ? "xlsx" : "csv");
    }

    public static Sheet open(HttpServletResponse response, String baseName, String format) throws IOException {
        return "xlsx".equals(format) ? new XlsxSheet(response, baseName + ".xlsx") : new CsvSheet(response, baseName + ".csv");
    }

    private static final class CsvSheet extends Sheet {
        private final PrintWriter out;

        CsvSheet(HttpServletResponse response, String filename) throws IOException {
            out = Csv.start(response, filename);
        }

        @Override
        public void row(Object... cells) {
            Csv.row(out, cells);
        }

        @Override
        public void finish() {
            out.flush();
        }
    }

    // A plain number: no leading zero (so codes like 0812 stay text) and no exponent.
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]{0,14})(\\.[0-9]+)?");

    static final class XlsxSheet extends Sheet {
        private final HttpServletResponse response;
        private final List<Object[]> rows = new ArrayList<>();

        XlsxSheet(HttpServletResponse response, String filename) {
            this.response = response;
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        }

        @Override
        public void row(Object... cells) {
            rows.add(cells);
        }

        @Override
        public void finish() throws IOException {
            write(rows, response.getOutputStream());
        }

        static void write(List<Object[]> rows, OutputStream target) throws IOException {
            int columns = rows.stream().mapToInt(r -> r.length).max().orElse(1);
            int[] widths = new int[columns];
            StringBuilder data = new StringBuilder();
            for (int r = 0; r < rows.size(); r++) {
                Object[] cells = rows.get(r);
                data.append("<row r=\"").append(r + 1).append("\">");
                for (int c = 0; c < cells.length; c++) {
                    Object v = cells[c];
                    if (v == null || v.toString().isEmpty()) {
                        continue;
                    }
                    String ref = column(c) + (r + 1);
                    String text = v instanceof BigDecimal b ? b.toPlainString() : v.toString();
                    widths[c] = Math.max(widths[c], Math.min(60, text.length()));
                    String style = r == 0 ? " s=\"1\"" : "";
                    if (r > 0 && (v instanceof Number || NUMBER.matcher(text).matches())) {
                        data.append("<c r=\"").append(ref).append('"').append(style).append("><v>").append(text).append("</v></c>");
                    } else {
                        data.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(style).append("><is><t xml:space=\"preserve\">")
                                .append(escape(text)).append("</t></is></c>");
                    }
                }
                data.append("</row>");
            }
            StringBuilder cols = new StringBuilder("<cols>");
            for (int c = 0; c < columns; c++) {
                cols.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"")
                        .append(Math.max(8, widths[c] + 2)).append("\" customWidth=\"1\"/>");
            }
            cols.append("</cols>");

            try (ZipOutputStream zip = new ZipOutputStream(target)) {
                put(zip, "[Content_Types].xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                        <Default Extension="xml" ContentType="application/xml"/>
                        <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                        <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                        <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                        </Types>""");
                put(zip, "_rels/.rels", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                        </Relationships>""");
                put(zip, "xl/workbook.xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                        <sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets>
                        </workbook>""");
                put(zip, "xl/_rels/workbook.xml.rels", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                        <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                        </Relationships>""");
                put(zip, "xl/styles.xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                        <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
                        <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
                        <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
                        <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                        <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>
                        </styleSheet>""");
                put(zip, "xl/worksheets/sheet1.xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                        <sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
                        """ + cols + "<sheetData>" + data + "</sheetData></worksheet>");
            }
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
                        // Control characters are not allowed in XML text.
                        if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') {
                            out.append(ch);
                        }
                    }
                }
            }
            return out.toString();
        }
    }
}
