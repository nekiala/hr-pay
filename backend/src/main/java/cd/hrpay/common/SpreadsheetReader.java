package cd.hrpay.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads the first sheet of an Excel workbook (.xlsx), or a CSV file, into rows of text. Cells keep the text Excel
 * stores: a number as written (45292 for a date typed in Excel, 1500.5 for an amount), a text as typed. Empty rows
 * at the end are dropped. Files larger than the limits are refused rather than read.
 */
public final class SpreadsheetReader {

    private static final long MAX_UNZIPPED = 60L * 1024 * 1024;
    private static final int MAX_ROWS = 20_000;

    private SpreadsheetReader() {}

    public static List<List<String>> read(String fileName, byte[] content) {
        boolean zip = content.length > 4 && content[0] == 'P' && content[1] == 'K';
        if (zip) {
            return xlsx(content);
        }
        if (fileName != null && fileName.toLowerCase().endsWith(".xls")) {
            throw new BusinessRuleException("Save the file as an Excel workbook (.xlsx) or as CSV: the old .xls format cannot be read.");
        }
        return csv(new String(content, StandardCharsets.UTF_8));
    }

    // ---- CSV ---------------------------------------------------------------------------------------------------

    static List<List<String>> csv(String text) {
        text = text.replace("﻿", "");
        String firstLine = text.lines().findFirst().orElse("");
        char sep = count(firstLine, ';') > count(firstLine, ',') ? ';' : count(firstLine, '\t') > count(firstLine, ',') ? '\t' : ',';
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (ch == sep) {
                row.add(cell.toString().trim());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString().trim());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                if (rows.size() > MAX_ROWS) {
                    throw new BusinessRuleException("The file has more than %s rows: split it.".formatted(MAX_ROWS));
                }
            } else {
                cell.append(ch);
            }
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString().trim());
            rows.add(row);
        }
        return trim(rows);
    }

    private static int count(String s, char c) {
        return (int) s.chars().filter(x -> x == c).count();
    }

    // ---- xlsx --------------------------------------------------------------------------------------------------

    private static List<List<String>> xlsx(byte[] content) {
        Map<String, byte[]> parts = new HashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                boolean wanted = name.equals("xl/sharedStrings.xml") || name.equals("xl/workbook.xml")
                        || name.equals("xl/_rels/workbook.xml.rels") || name.startsWith("xl/worksheets/sheet");
                if (!wanted) {
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = zip.read(buffer)) > 0) {
                    total += n;
                    if (total > MAX_UNZIPPED) {
                        throw new BusinessRuleException("The workbook is too large to read: keep only the sheet to import.");
                    }
                    out.write(buffer, 0, n);
                }
                parts.put(name, out.toByteArray());
            }
        } catch (IOException e) {
            throw new BusinessRuleException("The file could not be read as an Excel workbook.");
        }
        String sheet = firstSheet(parts);
        if (sheet == null || !parts.containsKey(sheet)) {
            throw new BusinessRuleException("The file could not be read as an Excel workbook.");
        }
        try {
            List<String> shared = parts.containsKey("xl/sharedStrings.xml") ? sharedStrings(parts.get("xl/sharedStrings.xml")) : List.of();
            return trim(cells(parts.get(sheet), shared));
        } catch (XMLStreamException e) {
            throw new BusinessRuleException("The file could not be read as an Excel workbook.");
        }
    }

    private static XMLStreamReader reader(byte[] xml) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f.createXMLStreamReader(new ByteArrayInputStream(xml));
    }

    /** The first sheet in the workbook's order, which is not always sheet1.xml. */
    private static String firstSheet(Map<String, byte[]> parts) {
        try {
            String rid = null;
            if (parts.containsKey("xl/workbook.xml")) {
                XMLStreamReader r = reader(parts.get("xl/workbook.xml"));
                while (r.hasNext() && rid == null) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("sheet")) {
                        for (int i = 0; i < r.getAttributeCount(); i++) {
                            if (r.getAttributeLocalName(i).equals("id")) {
                                rid = r.getAttributeValue(i);
                            }
                        }
                    }
                }
            }
            if (rid != null && parts.containsKey("xl/_rels/workbook.xml.rels")) {
                XMLStreamReader r = reader(parts.get("xl/_rels/workbook.xml.rels"));
                while (r.hasNext()) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("Relationship")
                            && rid.equals(r.getAttributeValue(null, "Id"))) {
                        String target = r.getAttributeValue(null, "Target");
                        return target.startsWith("/") ? target.substring(1) : "xl/" + target;
                    }
                }
            }
        } catch (XMLStreamException e) {
            // fall back to the first sheet file
        }
        return parts.containsKey("xl/worksheets/sheet1.xml") ? "xl/worksheets/sheet1.xml"
                : parts.keySet().stream().filter(k -> k.startsWith("xl/worksheets/sheet")).sorted().findFirst().orElse(null);
    }

    private static List<String> sharedStrings(byte[] xml) throws XMLStreamException {
        List<String> out = new ArrayList<>();
        XMLStreamReader r = reader(xml);
        StringBuilder text = null;
        boolean inPhonetic = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "si" -> text = new StringBuilder();
                    case "rPh" -> inPhonetic = true;
                    case "t" -> {
                        if (text != null && !inPhonetic) {
                            text.append(r.getElementText());
                        }
                    }
                    default -> { }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (r.getLocalName().equals("si") && text != null) {
                    out.add(text.toString());
                    text = null;
                } else if (r.getLocalName().equals("rPh")) {
                    inPhonetic = false;
                }
            }
        }
        return out;
    }

    private static List<List<String>> cells(byte[] xml, List<String> shared) throws XMLStreamException {
        List<List<String>> rows = new ArrayList<>();
        XMLStreamReader r = reader(xml);
        List<String> row = null;
        int rowIndex = 0;
        String ref = null;
        String type = null;
        String value = null;
        StringBuilder inline = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "row" -> {
                        String at = r.getAttributeValue(null, "r");
                        int index = at == null ? rowIndex + 1 : Integer.parseInt(at);
                        while (rows.size() < index - 1) {
                            rows.add(new ArrayList<>());
                        }
                        if (index > MAX_ROWS + 1) {
                            throw new BusinessRuleException("The file has more than %s rows: split it.".formatted(MAX_ROWS));
                        }
                        rowIndex = index;
                        row = new ArrayList<>();
                    }
                    case "c" -> {
                        ref = r.getAttributeValue(null, "r");
                        type = r.getAttributeValue(null, "t");
                        value = null;
                        inline = null;
                    }
                    case "v" -> value = r.getElementText();
                    case "is" -> inline = new StringBuilder();
                    case "t" -> {
                        if (inline != null) {
                            inline.append(r.getElementText());
                        }
                    }
                    default -> { }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (r.getLocalName().equals("c") && row != null) {
                    int col = ref == null ? row.size() : column(ref);
                    while (row.size() < col) {
                        row.add("");
                    }
                    String text;
                    if ("s".equals(type)) {
                        text = value == null ? "" : shared.get(Integer.parseInt(value.trim()));
                    } else if ("inlineStr".equals(type)) {
                        text = inline == null ? "" : inline.toString();
                    } else if ("b".equals(type)) {
                        text = "1".equals(value) ? "TRUE" : "FALSE";
                    } else {
                        text = value == null ? "" : plain(value);
                    }
                    row.add(text.trim());
                } else if (r.getLocalName().equals("row") && row != null) {
                    rows.add(row);
                    row = null;
                }
            }
        }
        return rows;
    }

    /** 1500.0 is written 1500; a value like 1.5E3 as 1500. */
    private static String plain(String v) {
        try {
            return new BigDecimal(v.trim()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return v;
        }
    }

    static int column(String ref) {
        int col = 0;
        for (int i = 0; i < ref.length() && Character.isLetter(ref.charAt(i)); i++) {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
        }
        return col - 1;
    }

    private static List<List<String>> trim(List<List<String>> rows) {
        int last = rows.size();
        while (last > 0 && rows.get(last - 1).stream().allMatch(String::isBlank)) {
            last--;
        }
        return new ArrayList<>(rows.subList(0, last));
    }

    /** For callers that hold a stream. */
    public static List<List<String>> read(String fileName, InputStream in) throws IOException {
        return read(fileName, in.readAllBytes());
    }
}
