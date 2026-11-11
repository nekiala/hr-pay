package cd.hrpay.report;

import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.payroll.report.Report;
import cd.hrpay.payroll.report.ReportPdf;
import cd.hrpay.payroll.report.ReportXlsx;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The HR rankings on screen, in Excel and in PDF; each view and download is written in the audit log. */
@RestController
@RequestMapping("/api/rankings")
@PreAuthorize("hasAnyAuthority('report.hr', 'attendance.read.all', 'attendance.read.team', 'mission.manage', "
        + "'discipline.manage', 'discipline.propose')")
public class RankingController {

    private final HrRankings rankings;
    private final ReportPdf pdf;

    public RankingController(HrRankings rankings, ReportPdf pdf) {
        this.rankings = rankings;
        this.pdf = pdf;
    }

    @GetMapping
    public List<HrRankings.KindInfo> kinds() {
        return rankings.kinds();
    }

    private static HrRankings.Kind kind(String name) {
        try {
            return HrRankings.Kind.valueOf(name.toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Unknown ranking %s.".formatted(name));
        }
    }

    @GetMapping("/{name}")
    public ResponseEntity<?> ranking(@PathVariable String name,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(required = false) Long unitId,
                                     @RequestParam(defaultValue = "20") int top,
                                     @RequestParam(defaultValue = "false") boolean byUnit,
                                     @RequestParam(defaultValue = "json") String format,
                                     HttpServletResponse response) throws IOException {
        HrRankings.Kind kind = kind(name);
        HrRankings.Ranking r = rankings.ranking(kind, from, to, unitId, format.equals("json") ? top : 0, byUnit);
        rankings.viewed(kind, r.from(), r.to(), unitId, format);
        if (format.equals("json")) {
            return ResponseEntity.ok(r);
        }
        String file = "ranking-" + kind.name().toLowerCase().replace('_', '-') + "-" + r.from() + "-" + r.to();
        Report report = report(r);
        if (format.equals("xlsx")) {
            ReportXlsx.write(response, file, List.of(report));
            return null;
        }
        if (!format.equals("pdf")) {
            throw new NotFoundException("Unknown format %s.".formatted(format));
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "attachment; filename=\"" + file + ".pdf\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(pdf.render(report));
    }

    @GetMapping("/{name}/people/{userId}")
    public List<HrRankings.Line> lines(@PathVariable String name, @PathVariable long userId,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return rankings.lines(kind(name), userId, from, to);
    }

    /** The same ranking as a printable report: rank, who, the score, every column and the previous period. */
    static Report report(HrRankings.Ranking r) {
        boolean units = r.kind().equals("TURNOVER") || r.kind().equals("UNITS");
        List<Report.Column> cols = new ArrayList<>();
        cols.add(Report.Column.number("#"));
        if (units) {
            cols.add(Report.Column.text(r.byUnit() ? Texts.t("Unit") : Texts.t("Direction")));
            if (r.byUnit()) {
                cols.add(Report.Column.text(Texts.t("Direction")));
            }
        } else {
            cols.add(Report.Column.text(Texts.t("Registration no")));
            cols.add(Report.Column.text(Texts.t("Name")));
            cols.add(Report.Column.text(Texts.t("Direction")));
            cols.add(Report.Column.text(Texts.t("Unit")));
        }
        cols.add(Report.Column.number(r.scoreLabel()));
        for (HrRankings.Column c : r.columns()) {
            cols.add(c.type().equals("MONEY") ? Report.Column.money(c.label())
                    : c.type().equals("TEXT") || c.type().equals("DATE") ? Report.Column.text(c.label()) : Report.Column.number(c.label()));
        }
        boolean previous = r.previousFrom() != null && !r.kind().equals("FOLLOW_UP");
        if (previous) {
            cols.add(Report.Column.number(Texts.t("Previous period")));
        }
        List<Report.Row> rows = new ArrayList<>();
        int rank = 0;
        for (HrRankings.Row row : r.rows()) {
            List<Object> cells = new ArrayList<>();
            cells.add(BigDecimal.valueOf(++rank));
            if (units) {
                cells.add(row.name());
                if (r.byUnit()) {
                    cells.add(row.directionName());
                }
            } else {
                cells.add(row.registrationNo());
                cells.add(row.name());
                cells.add(row.directionName());
                cells.add(row.unitName());
            }
            cells.add(row.score());
            for (HrRankings.Column c : r.columns()) {
                Object v = row.values().get(c.key());
                cells.add(v instanceof LocalDate d ? d.toString() : v instanceof Number n && !c.type().equals("TEXT")
                        ? new BigDecimal(n.toString()) : v);
            }
            if (previous) {
                cells.add(row.previous());
            }
            rows.add(Report.Row.of(cells));
        }
        String subtitle = r.previousFrom() == null ? Texts.t("Today, %s", Texts.day(r.from()))
                : Texts.t("From %s to %s", Texts.day(r.from()), Texts.day(r.to()))
                + (previous ? " · " + Texts.t("previous period from %s to %s", Texts.day(r.previousFrom()), Texts.day(r.previousTo())) : "");
        return new Report("ranking", r.title(), subtitle, false, List.of(new Report.Table(r.title(), cols, rows)), List.of(r.hint()));
    }
}
