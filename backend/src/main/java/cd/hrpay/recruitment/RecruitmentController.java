package cd.hrpay.recruitment;

import cd.hrpay.lifecycle.Files;
import cd.hrpay.lifecycle.LifecyclePdf;
import cd.hrpay.i18n.Texts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Recruitment (/recruitment page). HR does everything; unit heads ask to hire and follow their units' vacancies. */
@RestController
@PreAuthorize("isAuthenticated()")
public class RecruitmentController {

    public record RequestInput(@NotNull Long unitId, @NotNull Long occupationId, @NotNull @Min(1) @Max(100) Integer posts,
                               @NotNull @Pattern(regexp = "NEW_POST|REPLACEMENT|TEMPORARY") String reason, Long replacesUserId,
                               @NotNull @Pattern(regexp = "PERMANENT|FIXED_TERM|INTERN|CONSULTANT|DAILY|APPRENTICE") String contractType,
                               LocalDate wantedOn, Long gradeId, @Size(max = 1000) String note) {}

    public record DecisionInput(@NotNull Boolean approve, @Size(max = 1000) String comment) {}

    public record VacancyInput(@NotNull Long unitId, @NotNull Long occupationId, @NotNull @Min(1) @Max(100) Integer posts,
                               @NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 20000) String adText,
                               @NotNull @Pattern(regexp = "PERMANENT|FIXED_TERM|INTERN|CONSULTANT|DAILY|APPRENTICE") String contractType,
                               Long gradeId, @Size(max = 200) String place, LocalDate closesOn,
                               @NotNull @Pattern(regexp = "INTERNAL|PUBLIC|INTERNAL_FIRST") String visibility,
                               @Min(0) @Max(90) Integer internalDays) {}

    public record StatusInput(@NotNull @Pattern(regexp = "OPEN|ON_HOLD|CLOSED|CANCELLED") String status) {}

    public record DateInput(LocalDate on) {}

    public record CandidateInput(@NotBlank @Size(max = 100) String firstName, @Size(max = 100) String middleName,
                                 @NotBlank @Size(max = 100) String lastName, @Email @Size(max = 254) String email,
                                 @Pattern(regexp = "\\+?[0-9\\s()-]{8,20}") String phone, LocalDate birthDate,
                                 @Pattern(regexp = "FEMALE|MALE") String gender, @Pattern(regexp = "[A-Za-z]{2}") String nationalityCode,
                                 @Size(max = 100) String city, @Size(max = 200) String currentJob, @Size(max = 200) String cvName,
                                 @Size(max = 100) String cvType, String cv, Boolean keepForFuture, @Size(max = 1000) String note,
                                 @Pattern(regexp = "CAREERS|INTERNAL|REFERRAL|AGENCY|OTHER") String source) {
        Recruitment.CandidateInput data() {
            byte[] file = cv == null || cv.isBlank() ? null : Files.decode(cv);
            return new Recruitment.CandidateInput(firstName, middleName, lastName, email, phone, birthDate, gender,
                    nationalityCode, city, currentJob, cvName, cvType, file, Boolean.TRUE.equals(keepForFuture), note);
        }
    }

    public record MoveInput(@NotNull String stage, @Size(max = 1000) String note, @Size(max = 400) String rejectReason,
                            Boolean tell) {}

    public record NoteInput(@NotBlank @Size(max = 1000) String note) {}

    public record InterviewInput(@NotNull OffsetDateTime startsAt, @Size(max = 200) String place, @Size(max = 1000) String note,
                                 @NotNull List<Long> panel) {}

    public record ScoreInput(@NotNull List<Recruitment.SkillScore> scores, @NotNull @Min(1) @Max(5) Integer overall,
                             @NotNull @Pattern(regexp = "YES|MAYBE|NO") String recommendation, @Size(max = 2000) String comment) {}

    public record OfferInput(@NotNull Long gradeId, @DecimalMin("0.01") BigDecimal net, @NotNull LocalDate start, LocalDate end,
                             @Min(0) @Max(6) Integer trialMonths, @Size(max = 20000) String body) {}

    public record AnswerInput(@NotNull @Pattern(regexp = "ACCEPTED|DECLINED") String answer, LocalDate on) {}

    public record HireInput(@Email @Size(max = 254) String email, @Pattern(regexp = "\\+?[0-9\\s()-]{8,20}") String phone,
                            @Size(max = 32) String registrationNo, Long siteId, LocalDate minorWaiverOn) {}

    private final Recruitment recruitment;
    private final LifecyclePdf pdf;
    private final cd.hrpay.payment.Company company;

    public RecruitmentController(Recruitment recruitment, LifecyclePdf pdf, cd.hrpay.payment.Company company) {
        this.recruitment = recruitment;
        this.pdf = pdf;
        this.company = company;
    }

    /** What the current user can do here: HR, a unit head, or a panel member. */
    @GetMapping("/api/recruitment/me")
    public Map<String, Object> me() {
        return Map.of("hr", Recruitment.isHr(), "units", recruitment.unitsIRun(), "interviews", recruitment.myInterviews().size());
    }

    @GetMapping("/api/recruitment/overview")
    public Recruitment.Overview overview() {
        return recruitment.overview();
    }

    // ---- requests

    @GetMapping("/api/recruitment/requests")
    public List<Recruitment.Request> requests() {
        return recruitment.requests();
    }

    @PostMapping("/api/recruitment/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public Recruitment.Request ask(@Valid @RequestBody RequestInput in) {
        return recruitment.ask(in.unitId(), in.occupationId(), in.posts(), in.reason(), in.replacesUserId(), in.contractType(),
                in.wantedOn(), in.gradeId(), in.note());
    }

    @PostMapping("/api/recruitment/requests/{id}/decision")
    @PreAuthorize("hasAuthority('recruitment.manage')")
    public Recruitment.Request decide(@PathVariable long id, @Valid @RequestBody DecisionInput in) {
        return recruitment.decide(id, in.approve(), in.comment());
    }

    @PostMapping("/api/recruitment/requests/{id}/cancel")
    public Recruitment.Request cancelRequest(@PathVariable long id) {
        return recruitment.cancelRequest(id);
    }

    // ---- vacancies

    @GetMapping("/api/recruitment/vacancies")
    public List<Recruitment.VacancyRow> vacancies(@RequestParam(defaultValue = "false") boolean closed) {
        return recruitment.vacancies(closed);
    }

    @GetMapping("/api/recruitment/vacancies/{id}")
    public Recruitment.Vacancy vacancy(@PathVariable long id) {
        return recruitment.vacancy(id);
    }

    @PostMapping("/api/recruitment/vacancies")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('recruitment.manage')")
    public Recruitment.Vacancy addVacancy(@Valid @RequestBody VacancyInput in) {
        return recruitment.saveVacancy(null, input(in));
    }

    @PutMapping("/api/recruitment/vacancies/{id}")
    @PreAuthorize("hasAuthority('recruitment.manage')")
    public Recruitment.Vacancy saveVacancy(@PathVariable long id, @Valid @RequestBody VacancyInput in) {
        return recruitment.saveVacancy(id, input(in));
    }

    private static Recruitment.VacancyInput input(VacancyInput in) {
        return new Recruitment.VacancyInput(in.unitId(), in.occupationId(), in.posts(), in.title(), in.adText(), in.contractType(),
                in.gradeId(), in.place(), in.closesOn(), in.visibility(), in.internalDays() == null ? 0 : in.internalDays());
    }

    @PostMapping("/api/recruitment/vacancies/{id}/status")
    @PreAuthorize("hasAuthority('recruitment.manage')")
    public Recruitment.Vacancy status(@PathVariable long id, @Valid @RequestBody StatusInput in) {
        return recruitment.setStatus(id, in.status());
    }

    @PutMapping("/api/recruitment/vacancies/{id}/onem")
    @PreAuthorize("hasAuthority('recruitment.manage')")
    public Recruitment.Vacancy onem(@PathVariable long id, @RequestBody DateInput in) {
        return recruitment.declaredToOnem(id, in.on());
    }

    /** The ad check on a text not saved yet, as HR types it. */
    @PostMapping("/api/recruitment/ad-check")
    @PreAuthorize("hasAuthority('recruitment.manage')")
    public List<AdCheck.Flag> adCheck(@RequestBody Map<String, String> in) {
        return AdCheck.check(in.get("title"), in.get("adText"));
    }

    @PostMapping("/api/recruitment/vacancies/{id}/candidates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('recruitment.manage')")
    public Recruitment.Application addCandidate(@PathVariable long id, @Valid @RequestBody CandidateInput in) {
        return recruitment.addCandidate(id, in.data(), in.source());
    }

    // ---- applications

    @GetMapping("/api/recruitment/applications/{id}")
    public Recruitment.Application application(@PathVariable long id) {
        return recruitment.application(id);
    }

    @PutMapping("/api/recruitment/applications/{id}/candidate")
    @PreAuthorize("hasAuthority('recruitment.manage')")
    public Recruitment.Application updateCandidate(@PathVariable long id, @Valid @RequestBody CandidateInput in) {
        return recruitment.updateCandidate(id, in.data());
    }

    @GetMapping("/api/recruitment/candidates/{id}/cv")
    public ResponseEntity<byte[]> cv(@PathVariable long id) {
        byte[] data = recruitment.cv(id);
        return Files.attachment(recruitment.cvName(id), recruitment.cvType(id), data);
    }

    @PostMapping("/api/recruitment/applications/{id}/stage")
    @PreAuthorize("hasAuthority('recruitment.manage')")
    public Recruitment.Application move(@PathVariable long id, @Valid @RequestBody MoveInput in) {
        return recruitment.move(id, in.stage(), in.note(), in.rejectReason(), !Boolean.FALSE.equals(in.tell()));
    }

    @PostMapping("/api/recruitment/applications/{id}/notes")
    @PreAuthorize("hasAuthority('recruitment.manage')")
    public Recruitment.Application note(@PathVariable long id, @Valid @RequestBody NoteInput in) {
        return recruitment.note(id, in.note());
    }

    @PostMapping("/api/recruitment/applications/{id}/interviews")
    @PreAuthorize("hasAuthority('recruitment.manage')")
    public Recruitment.Application interview(@PathVariable long id, @Valid @RequestBody InterviewInput in) {
        return recruitment.planInterview(id, in.startsAt(), in.place(), in.note(), in.panel());
    }

    @DeleteMapping("/api/recruitment/interviews/{id}")
    @PreAuthorize("hasAuthority('recruitment.manage')")
    public Recruitment.Application cancelInterview(@PathVariable long id) {
        return recruitment.cancelInterview(id);
    }

    @PutMapping("/api/recruitment/interviews/{id}/scorecard")
    public Recruitment.Application score(@PathVariable long id, @Valid @RequestBody ScoreInput in) {
        return recruitment.score(id, in.scores(), in.overall(), in.recommendation(), in.comment());
    }

    @GetMapping("/api/recruitment/interviews/mine")
    public List<Recruitment.MyInterview> myInterviews() {
        return recruitment.myInterviews();
    }

    @PutMapping("/api/recruitment/applications/{id}/offer")
    @PreAuthorize("hasAuthority('recruitment.manage')")
    public Recruitment.Application offer(@PathVariable long id, @Valid @RequestBody OfferInput in) {
        return recruitment.saveOffer(id, new Recruitment.OfferInput(in.gradeId(), in.net(), in.start(), in.end(), in.trialMonths(), in.body()));
    }

    @GetMapping("/api/recruitment/applications/{id}/offer.pdf")
    @PreAuthorize("hasAuthority('recruitment.manage')")
    public ResponseEntity<byte[]> offerPdf(@PathVariable long id) {
        String body = recruitment.offerBody(id);
        cd.hrpay.payment.Company.Representative rep = company.representative();
        String title = Texts.in(company.language(), () -> Texts.t("Job offer"));
        String place = (rep.signingPlace() == null ? "" : rep.signingPlace() + ", ")
                + Texts.in(company.language(), () -> Texts.day(recruitment.today()));
        byte[] file = pdf.letter(title, null, body, place,
                List.of(new LifecyclePdf.Signature(rep.name() == null ? "" : rep.name(), rep.title() == null ? "" : rep.title())));
        return Files.inline("offer-" + id + ".pdf", "application/pdf", file);
    }

    @PostMapping("/api/recruitment/applications/{id}/offer/sent")
    @PreAuthorize("hasAuthority('recruitment.manage')")
    public Recruitment.Application offerSent(@PathVariable long id, @RequestBody DateInput in) {
        return recruitment.offerSent(id, in.on());
    }

    @PostMapping("/api/recruitment/applications/{id}/offer/answer")
    @PreAuthorize("hasAuthority('recruitment.manage')")
    public Recruitment.Application offerAnswer(@PathVariable long id, @Valid @RequestBody AnswerInput in) {
        return recruitment.offerAnswer(id, in.answer(), in.on());
    }

    @PostMapping("/api/recruitment/applications/{id}/hire")
    @PreAuthorize("hasAuthority('recruitment.manage') and hasAuthority('user.create') and hasAuthority('salary.write')")
    public Map<String, Long> hire(@PathVariable long id, @Valid @RequestBody HireInput in) {
        return Map.of("userId", recruitment.hire(id, new Recruitment.HireInput(in.email(), in.phone(), in.registrationNo(),
                in.siteId(), in.minorWaiverOn())));
    }
}
