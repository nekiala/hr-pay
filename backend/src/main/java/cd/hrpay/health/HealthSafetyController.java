package cd.hrpay.health;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.lifecycle.Files;
import cd.hrpay.payroll.report.Report;
import cd.hrpay.payroll.report.ReportPdf;
import cd.hrpay.payroll.report.ReportXlsx;
import cd.hrpay.security.CurrentUser;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The health and safety page (medical visits, work accidents, the committee), what a unit head and an employee see of
 * it, and the accident report anyone can make.
 */
@RestController
@RequestMapping("/api/health")
@PreAuthorize("hasAuthority('health.safety')")
public class HealthSafetyController {

    private final MedicalVisits visits;
    private final WorkAccidents accidents;
    private final SafetyCommittee committee;
    private final HealthSafety health;
    private final ReportPdf pdf;
    private final AuditService audit;
    private final JdbcClient jdbc;

    public HealthSafetyController(MedicalVisits visits, WorkAccidents accidents, SafetyCommittee committee, HealthSafety health,
                                  ReportPdf pdf, AuditService audit, JdbcClient jdbc) {
        this.visits = visits;
        this.accidents = accidents;
        this.committee = committee;
        this.health = health;
        this.pdf = pdf;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    private static byte[] content(String base64) {
        return base64 == null || base64.isBlank() ? null : Files.decode(base64);
    }

    // ---- medical visits ----------------------------------------------------------------------------------------

    public record VisitInput(@NotNull Long userId, @NotBlank @Pattern(regexp = "HIRING|PROVISIONAL|YEARLY|RETURN|SPECIAL") String kind,
                             LocalDate dueOn, LocalDate visitedOn, @Size(max = 150) String doctor,
                             @Pattern(regexp = "FIT|FIT_RESTRICTED|UNFIT_TEMPORARY|UNFIT") String result,
                             @Size(max = 500) String restrictions, LocalDate validUntil, @Size(max = 500) String note,
                             @Size(max = 200) String fileName, @Size(max = 14_000_000) String content) {}

    public record ResultInput(@NotNull LocalDate visitedOn, @Size(max = 150) String doctor,
                              @NotBlank @Pattern(regexp = "FIT|FIT_RESTRICTED|UNFIT_TEMPORARY|UNFIT") String result,
                              @Size(max = 500) String restrictions, LocalDate validUntil, @Size(max = 500) String note,
                              @Size(max = 200) String fileName, @Size(max = 14_000_000) String content) {}

    public record ServiceInput(@NotBlank @Pattern(regexp = "OWN|SHARED|OUTSIDE") String organisation) {}

    @GetMapping("/visits")
    public MedicalVisits.Page visitPage(@RequestParam(defaultValue = "60") int days) {
        return visits.page(days);
    }

    @GetMapping("/people/{userId}/visits")
    public List<MedicalVisits.Visit> personVisits(@PathVariable long userId) {
        return visits.of(userId);
    }

    @PostMapping("/visits")
    @ResponseStatus(HttpStatus.CREATED)
    public HealthSafety.Saved<MedicalVisits.Visit> addVisit(@Valid @RequestBody VisitInput b) {
        return visits.add(b.userId(), b.kind(), b.dueOn(), b.visitedOn(), b.doctor(), b.result(), b.restrictions(),
                b.validUntil(), b.note(), b.fileName(), content(b.content()));
    }

    @PostMapping("/visits/{id}/result")
    public HealthSafety.Saved<MedicalVisits.Visit> result(@PathVariable long id, @Valid @RequestBody ResultInput b) {
        return visits.record(id, b.visitedOn(), b.doctor(), b.result(), b.restrictions(), b.validUntil(), b.note(),
                b.fileName(), content(b.content()));
    }

    @DeleteMapping("/visits/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteVisit(@PathVariable long id) {
        visits.delete(id);
    }

    /** The certificate: HR, or the person it is about. */
    @GetMapping("/visits/{id}/file")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> visitFile(@PathVariable long id) {
        MedicalVisits.Visit v = visits.visit(id);
        if (!CurrentUser.has(HealthSupport.PERMISSION) && !Long.valueOf(v.userId()).equals(CurrentUser.id())) {
            throw new AccessDeniedException("Not your visit.");
        }
        HealthSupport.File f = visits.file(id);
        return Files.inline(f.name(), f.contentType(), f.data());
    }

    @PutMapping("/service")
    public MedicalVisits.Service service(@Valid @RequestBody ServiceInput b) {
        return visits.saveService(b.organisation());
    }

    /** The visits due, to send to the doctor or clinic. */
    @GetMapping("/visits/due-list")
    public ResponseEntity<?> dueList(@RequestParam(defaultValue = "60") int days, @RequestParam(defaultValue = "pdf") String format,
                                     HttpServletResponse response) throws IOException {
        LocalDate until = LocalDate.now().plusDays(Math.max(0, days));
        List<Report.Row> rows = new ArrayList<>();
        for (MedicalVisits.Due d : visits.due()) {
            if (!d.dueOn().isAfter(until)) {
                rows.add(Report.Row.of(List.of(d.registrationNo() == null ? "" : d.registrationNo(), d.name(),
                        d.unit() == null ? "" : d.unit(), d.site() == null ? "" : d.site(), kindName(d.kind()), Texts.day(d.dueOn()),
                        d.restrictions() == null ? "" : d.restrictions())));
            }
        }
        Report r = new Report("health.visits_due", Texts.t("Medical visits due"), Texts.t("Up to %s", Texts.day(until)), false,
                List.of(new Report.Table(Texts.t("Workers to see"), List.of(Report.Column.text(Texts.t("Registration no.")),
                        Report.Column.text(Texts.t("Name")), Report.Column.text(Texts.t("Unit")), Report.Column.text(Texts.t("Site")),
                        Report.Column.text(Texts.t("Visit")), Report.Column.text(Texts.t("Due")),
                        Report.Column.text(Texts.t("Restrictions in force"))), rows)),
                List.of(Texts.t("The visits are paid by the employer (Labour Code art. 38). The doctor gives the employer only the result: fit, fit with restrictions, unfit for now, or unfit.")));
        return send(r, format, "visites-medicales-" + LocalDate.now(), Map.of("kind", "VISITS_DUE"), response);
    }

    static String kindName(String kind) {
        return switch (kind) {
            case "HIRING" -> Texts.t("Hiring visit");
            case "PROVISIONAL" -> Texts.t("Nurse's provisional visit");
            case "RETURN" -> Texts.t("Return to work");
            case "SPECIAL" -> Texts.t("Special watch");
            default -> Texts.t("Yearly visit");
        };
    }

    /** A unit head's team: fit or not and the restrictions. */
    @GetMapping("/team")
    @PreAuthorize("isAuthenticated()")
    public List<MedicalVisits.Fitness> team() {
        return visits.team(CurrentUser.id());
    }

    public record Mine(List<MedicalVisits.Visit> visits, List<WorkAccidents.Accident> accidents) {}

    /** The employee's own visits and accidents. */
    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    public Mine mine() {
        long me = CurrentUser.id();
        return new Mine(visits.of(me), accidents.of(me));
    }

    // ---- work accidents ----------------------------------------------------------------------------------------

    public record ReportInput(@NotNull Long userId, @NotBlank @Pattern(regexp = "WORK|COMMUTING|DISEASE") String kind,
                              @NotNull OffsetDateTime occurredAt, @Size(max = 200) String place,
                              @NotBlank @Size(max = 4000) String description, @Size(max = 500) String witnesses,
                              @Size(max = 500) String injury, @Size(max = 500) String firstAid) {}

    public record CompleteInput(@NotBlank @Pattern(regexp = "WORK|COMMUTING|DISEASE") String kind, @NotNull OffsetDateTime occurredAt,
                                @Size(max = 200) String place, @NotBlank @Size(max = 4000) String description,
                                @Size(max = 500) String witnesses, @Size(max = 500) String injury, @Size(max = 500) String firstAid,
                                LocalDate offFrom, LocalDate offTo, @Size(max = 1000) String note) {}

    public record DeclareInput(LocalDate declaredOn, @Size(max = 100) String reference, LocalDate inspectionCopyOn,
                               LocalDate victimCopyOn) {}

    public record AccidentPage(int year, List<WorkAccidents.Accident> accidents, List<WorkAccidents.Accident> undeclared,
                               WorkAccidents.Figures figures) {}

    @GetMapping("/accidents")
    public AccidentPage accidentPage(@RequestParam(required = false) Integer year) {
        int y = year == null ? LocalDate.now().getYear() : year;
        return new AccidentPage(y, accidents.list(y), accidents.undeclared(), accidents.figures(y));
    }

    public record Reportable(long userId, String name, String registrationNo) {}

    /** Who the current user can report an accident for: themself, their team, everyone for HR. */
    @GetMapping("/accidents/reportable")
    @PreAuthorize("isAuthenticated()")
    public List<Reportable> reportable() {
        long me = CurrentUser.id();
        String select = "select id as user_id, person_name(first_name, middle_name, last_name) as name, registration_no from app_user";
        if (CurrentUser.has(HealthSupport.PERMISSION)) {
            return jdbc.sql(select + " where status = 'ACTIVE' and not not_employee order by name").query(Reportable.class).list();
        }
        java.util.Set<Long> ids = new java.util.HashSet<>(visits.teamIds(me));
        ids.add(me);
        return jdbc.sql(select + " where id in (:ids) order by (id = :me) desc, name").param("ids", ids).param("me", me)
                .query(Reportable.class).list();
    }

    @PostMapping("/accidents/report")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkAccidents.Accident report(@Valid @RequestBody ReportInput b) {
        return accidents.report(b.userId(), b.kind(), b.occurredAt(), b.place(), b.description(), b.witnesses(), b.injury(),
                b.firstAid());
    }

    @PutMapping("/accidents/{id}")
    public HealthSafety.Saved<WorkAccidents.Accident> complete(@PathVariable long id, @Valid @RequestBody CompleteInput b) {
        return accidents.complete(id, b.kind(), b.occurredAt(), b.place(), b.description(), b.witnesses(), b.injury(),
                b.firstAid(), b.offFrom(), b.offTo(), b.note());
    }

    @PutMapping("/accidents/{id}/declaration")
    public WorkAccidents.Accident declare(@PathVariable long id, @Valid @RequestBody DeclareInput b) {
        return accidents.declare(id, b.declaredOn(), b.reference(), b.inspectionCopyOn(), b.victimCopyOn());
    }

    @PostMapping("/accidents/{id}/letter")
    public HealthSafety.Saved<WorkAccidents.Accident> letter(@PathVariable long id) {
        return accidents.draftLetter(id);
    }

    @DeleteMapping("/accidents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccident(@PathVariable long id) {
        accidents.delete(id);
    }

    // ---- the committee -----------------------------------------------------------------------------------------

    public record MemberInput(Long userId, @Size(max = 150) String name,
                              @NotBlank @Pattern(regexp = "PRESIDENT|SECRETARY|DOCTOR|TRAINING|WORKER") String role,
                              @NotNull LocalDate fromOn, LocalDate termEnd) {}

    public record EndInput(@NotNull LocalDate endedOn) {}

    public record EventInput(@NotBlank @Pattern(regexp = "MEETING|INSPECTION|REPORT|MEDICAL_REPORT") String kind,
                             @NotNull LocalDate heldOn, Integer year, @Size(max = 200) String title, @Size(max = 4000) String note,
                             @Size(max = 200) String fileName, @Size(max = 14_000_000) String content) {}

    public record ActionInput(@NotBlank @Size(max = 500) String risk, @NotBlank @Size(max = 500) String action, Long ownerId,
                              LocalDate dueOn, Long eventId, Long accidentId) {}

    public record DoneInput(LocalDate doneOn) {}

    @GetMapping("/committee")
    public SafetyCommittee.Overview committee() {
        return committee.overview();
    }

    @PostMapping("/committee/members")
    @ResponseStatus(HttpStatus.CREATED)
    public SafetyCommittee.Member addMember(@Valid @RequestBody MemberInput b) {
        return committee.addMember(b.userId(), b.name(), b.role(), b.fromOn(), b.termEnd());
    }

    @PostMapping("/committee/members/{id}/end")
    public SafetyCommittee.Member endMember(@PathVariable long id, @Valid @RequestBody EndInput b) {
        return committee.endMember(id, b.endedOn());
    }

    @DeleteMapping("/committee/members/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMember(@PathVariable long id) {
        committee.deleteMember(id);
    }

    @PostMapping("/committee/events")
    @ResponseStatus(HttpStatus.CREATED)
    public SafetyCommittee.Event addEvent(@Valid @RequestBody EventInput b) {
        return committee.addEvent(b.kind(), b.heldOn(), b.year(), b.title(), b.note(), b.fileName(), content(b.content()));
    }

    @DeleteMapping("/committee/events/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEvent(@PathVariable long id) {
        committee.deleteEvent(id);
    }

    @GetMapping("/committee/events/{id}/file")
    public ResponseEntity<byte[]> eventFile(@PathVariable long id) {
        HealthSupport.File f = committee.eventFile(id);
        return Files.inline(f.name(), f.contentType(), f.data());
    }

    @PostMapping("/committee/actions")
    @ResponseStatus(HttpStatus.CREATED)
    public SafetyCommittee.Action addAction(@Valid @RequestBody ActionInput b) {
        return committee.addAction(b.risk(), b.action(), b.ownerId(), b.dueOn(), b.eventId(), b.accidentId());
    }

    @PutMapping("/committee/actions/{id}/done")
    public SafetyCommittee.Action doneAction(@PathVariable long id, @RequestBody DoneInput b) {
        return committee.doneAction(id, b.doneOn());
    }

    @DeleteMapping("/committee/actions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAction(@PathVariable long id) {
        committee.deleteAction(id);
    }

    @GetMapping("/committee/report/{year}")
    public ResponseEntity<?> yearlyReport(@PathVariable int year, @RequestParam(defaultValue = "pdf") String format,
                                          HttpServletResponse response) throws IOException {
        if (year < 2000 || year > LocalDate.now().getYear()) {
            throw new BusinessRuleException("Choose a year.");
        }
        return send(health.yearlyReport(year), format, "rapport-cshe-" + year, Map.of("kind", "CSHE_REPORT", "year", year), response);
    }

    // ---- deadlines ---------------------------------------------------------------------------------------------

    /** The open deadlines, shown on the declarations calendar too. */
    @GetMapping("/deadlines")
    @PreAuthorize("hasAuthority('health.safety') or hasAuthority('declaration.manage')")
    public List<HealthSafety.Deadline> deadlines() {
        return health.deadlines();
    }

    private ResponseEntity<?> send(Report r, String format, String name, Map<String, Object> what, HttpServletResponse response)
            throws IOException {
        if (!List.of("json", "pdf", "xlsx").contains(format)) {
            throw new BusinessRuleException("Choose the format: json, pdf or xlsx.");
        }
        if (format.equals("json")) {
            return ResponseEntity.ok(r);
        }
        Map<String, Object> details = new java.util.LinkedHashMap<>(what);
        details.put("format", format);
        audit.record("health.exported", "health", null, details);
        if (format.equals("xlsx")) {
            ReportXlsx.write(response, name, List.of(r));
            return null;
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "attachment; filename=\"" + name + ".pdf\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(pdf.render(r));
    }
}
