package cd.hrpay.common;

import cd.hrpay.report.ReportController;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

/** CSV files that open directly in Excel: UTF-8 with a BOM, CRLF line ends, formula-safe cells. */
public final class Csv {

    private Csv() {}

    public static PrintWriter start(HttpServletResponse response, String filename) throws IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        PrintWriter out = response.getWriter();
        out.write('﻿');
        return out;
    }

    public static void row(PrintWriter out, Object... cells) {
        out.write(Arrays.stream(cells).map(Csv::text).map(ReportController::cell).collect(Collectors.joining(",")));
        out.write("\r\n");
    }

    private static String text(Object v) {
        if (v == null) {
            return null;
        }
        return v instanceof BigDecimal b ? b.toPlainString() : v.toString();
    }
}
