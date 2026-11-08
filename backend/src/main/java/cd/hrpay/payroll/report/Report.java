package cd.hrpay.payroll.report;

import java.util.List;
import java.util.Map;

/**
 * A payroll report as data: a title and one or more tables. The same report is shown on screen (JSON), printed (PDF)
 * and exported (Excel), so the three never disagree.
 */
public record Report(String code, String title, String subtitle, boolean provisional, List<Table> tables, List<String> notes) {

    /** TEXT, MONEY (two decimals), NUMBER (a count or days). */
    public record Column(String label, String type) {

        public static Column text(String label) {
            return new Column(label, "TEXT");
        }

        public static Column money(String label) {
            return new Column(label, "MONEY");
        }

        public static Column number(String label) {
            return new Column(label, "NUMBER");
        }
    }

    /**
     * One row. Style: null for a normal row, SUBTOTAL, TOTAL, GROUP (a heading row) or COUNT (whole numbers in amount
     * columns). Depth indents the first cell
     * (a unit's place in the tree). Drill names what a click on the row lists, when it lists something.
     */
    public record Row(List<Object> cells, String style, int depth, Map<String, String> drill) {

        /** A total, subtotal or heading: printed in bold. */
        public boolean strong() {
            return style != null && !style.equals("COUNT");
        }

        public static Row of(List<Object> cells) {
            return new Row(cells, null, 0, null);
        }
    }

    /**
     * A table. With rowsPerPage above zero the PDF prints that many rows per page, followed by the page's totals and
     * the totals carried forward (the livre de paie); large puts it on A3 paper.
     */
    public record Table(String title, List<Column> columns, List<Row> rows, int rowsPerPage, boolean large) {

        public Table(String title, List<Column> columns, List<Row> rows) {
            this(title, columns, rows, 0, false);
        }
    }
}
