package cd.hrpay.payroll.report;

import cd.hrpay.common.Workbook;
import cd.hrpay.payroll.report.Report.Row;
import cd.hrpay.payroll.report.Report.Table;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Reports as an Excel workbook: one sheet per table, headed by the report's title and filters. */
public final class ReportXlsx {

    private ReportXlsx() {
    }

    public static void write(HttpServletResponse response, String name, List<Report> list) throws IOException {
        Workbook book = new Workbook();
        for (Report r : list) {
            for (Table t : r.tables()) {
                book.sheet(r.tables().size() == 1 ? r.title() : t.title());
                book.row(true, t.columns().stream().map(Report.Column::label).toList());
                for (Row row : t.rows()) {
                    List<Object> cells = new ArrayList<>(row.cells());
                    if (row.depth() > 0 && !cells.isEmpty() && cells.getFirst() instanceof String s) {
                        cells.set(0, "  ".repeat(row.depth()) + s);
                    }
                    book.row(row.strong(), cells);
                }
                book.row(false, List.of());
                book.row(false, List.of(r.title() + " · " + r.subtitle()));
                for (String n : r.notes()) {
                    book.row(false, List.of(n));
                }
            }
        }
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + name + ".xlsx\"");
        book.write(response.getOutputStream());
    }
}
