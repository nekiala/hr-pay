package cd.hrpay.attendance;

import cd.hrpay.audit.AuditService;
import cd.hrpay.payroll.report.ReportPdf;
import cd.hrpay.payroll.report.ReportXlsx;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Closing the attendance decade by decade, and the decade sheets. HR (attendance.decade.close). */
@RestController
@RequestMapping("/api/attendance/decades")
@PreAuthorize("hasAuthority('attendance.decade.close')")
public class AttendanceDecadeController {

    public record ReopenInput(@NotBlank @Size(max = 500) String reason) {}

    private final AttendanceDecades decades;
    private final ReportPdf pdf;
    private final AuditService audit;

    public AttendanceDecadeController(AttendanceDecades decades, ReportPdf pdf, AuditService audit) {
        this.decades = decades;
        this.pdf = pdf;
        this.audit = audit;
    }

    @GetMapping
    public List<AttendanceDecades.Decade> month(@RequestParam YearMonth month) {
        return decades.month(month);
    }

    @GetMapping("/{start}")
    public AttendanceDecades.Detail detail(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                                           @RequestParam(required = false) Long unitId) {
        return decades.detail(start, unitId);
    }

    @PostMapping("/{start}/close")
    public AttendanceDecades.Decade close(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start) {
        return decades.close(start);
    }

    @PostMapping("/{start}/reopen")
    public AttendanceDecades.Decade reopen(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                                           @Valid @RequestBody ReopenInput b) {
        return decades.reopen(start, b.reason());
    }

    /** The decade sheet (fiche de pointage décadaire), for one unit or all, in PDF or Excel. */
    @GetMapping("/{start}/sheet")
    public ResponseEntity<byte[]> sheet(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                                        @RequestParam(required = false) Long unitId,
                                        @RequestParam(defaultValue = "pdf") String format,
                                        HttpServletResponse response) throws IOException {
        var report = decades.sheet(start, unitId);
        audit.record("attendance.decade_sheet", "attendance_decade", null, unitId == null
                ? Map.of("from", start.toString(), "format", format)
                : Map.of("from", start.toString(), "format", format, "unitId", unitId));
        String name = "fiche-decade-" + start;
        if (format.equals("xlsx")) {
            ReportXlsx.write(response, name, List.of(report));
            return null;
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "attachment; filename=\"" + name + ".pdf\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(pdf.render(report));
    }
}
