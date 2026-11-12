package cd.hrpay.payroll;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MasterDataController {

    static final String CODE = "[A-Za-z0-9_-]{1,32}";

    public record GradeInput(@NotNull Long categoryId, @NotBlank @Pattern(regexp = CODE) String code,
                             @NotBlank @Size(max = 150) String name,
                             @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal baseSalary,
                             @NotNull @Pattern(regexp = "USD|CDF|XAF") String currency, boolean active,
                             @DecimalMin("1") @Digits(integer = 4, fraction = 2) BigDecimal smigCoefficient) {}

    public record OccupationInput(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 150) String name,
                                  boolean active) {}

    public record NoticeInput(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.DecimalMin("0") @jakarta.validation.constraints.DecimalMax("365") BigDecimal base,
                              @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp = "DAYS|MONTHS") String baseUnit,
                              @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.DecimalMin("0") @jakarta.validation.constraints.DecimalMax("60") BigDecimal daysPerYear,
                              @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp = "WORKING|CALENDAR") String dayKind,
                              @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.DecimalMin("0.05") @jakarta.validation.constraints.DecimalMax("1") BigDecimal resignationShare) {}

    public record RaiseInput(@NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal percent,
                             @Pattern(regexp = "ANNIVERSARY_MONTH|NEXT_MONTH") String timing) {}

    public record ContractInput(@NotNull @Pattern(regexp = "PERMANENT|FIXED_TERM|INTERN|CONSULTANT|DAILY|APPRENTICE") String contractType,
                                @NotNull LocalDate startDate, LocalDate endDate, @NotNull Long gradeId, Long occupationId,
                                boolean expatriate,
                                @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal negotiatedBasic,
                                @Size(max = 300) String note,
                                @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal agreedNet,
                                LocalDate hireDate) {}

    public record ProfileInput(LocalDate birthDate, @Pattern(regexp = "FEMALE|MALE") String gender,
                               @Size(max = 60) String nationality,
                               @NotNull @Pattern(regexp = "SINGLE|MARRIED|DIVORCED|WIDOWED") String maritalStatus,
                               @Size(max = 40) String cnssNumber, @Size(max = 40) String taxNumber,
                               @NotNull @Pattern(regexp = "BANK|MOBILE_MONEY|CASH") String paymentMethod,
                               @Size(max = 100) String bankName, @Size(max = 60) String bankAccount,
                               @Size(max = 40) String mobileMoneyOperator,
                               @Pattern(regexp = "\\+?[0-9 ]{8,20}") String mobileMoneyNumber,
                               @Size(max = 100) String commune,
                               LocalDate nursingBreakUntil,
                               @Pattern(regexp = "[A-Za-z]{2}") String nationalityCode,
                               @Pattern(regexp = "[A-Za-z]{2}") String birthCountryCode, Long birthPlaceId,
                               @Size(max = 100) String birthPlaceText, Long addressPlaceId,
                               @Size(max = 100) String addressQuarter, @Size(max = 200) String addressStreet,
                               Long bankId, Long mobileMoneyOperatorId,
                               @Pattern(regexp = "NATIONAL_ID|ELECTOR_CARD|PASSPORT|RESIDENCE_PERMIT|DRIVING_LICENCE|OTHER") String idDocumentType,
                               @Size(max = 40) String idDocumentNumber, LocalDate idDocumentIssuedOn,
                               @Size(max = 100) String idDocumentIssuedAt) {}

    public record DependantInput(@NotBlank @Size(max = 150) String fullName,
                                 @NotNull @Pattern(regexp = "SPOUSE|CHILD") String relationship, LocalDate birthDate,
                                 boolean inCharge, Boolean student, Boolean disabled, @Size(max = 300) String note) {}

    /** The file, with the places picked (the top one first) so the lists open where they were left. */
    public record EmployeeFileView(List<EmployeeFile.Contract> contracts, EmployeeFile.Profile profile,
                                   List<EmployeeFile.DependantRow> dependants,
                                   List<cd.hrpay.reference.References.Place> birthPlacePath,
                                   List<cd.hrpay.reference.References.Place> addressPlacePath) {}

    private final MasterData masterData;
    private final EmployeeFile files;
    private NegotiatedPay negotiated;
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    @org.springframework.beans.factory.annotation.Autowired
    void setClock(java.time.Clock clock) {
        this.clock = clock;
    }

    private cd.hrpay.reference.References refs;

    @org.springframework.beans.factory.annotation.Autowired
    void setRefs(cd.hrpay.reference.References refs) {
        this.refs = refs;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setNegotiated(NegotiatedPay negotiated) {
        this.negotiated = negotiated;
    }

    public MasterDataController(MasterData masterData, EmployeeFile files) {
        this.masterData = masterData;
        this.files = files;
    }

    @GetMapping("/grades")
    public List<MasterData.Grade> grades() {
        return masterData.grades();
    }

    @PostMapping("/grades")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('masterdata.manage')")
    public MasterData.Grade addGrade(@Valid @RequestBody GradeInput b) {
        return masterData.saveGrade(null, b.categoryId(), b.code().trim(), b.name().trim(), b.baseSalary(), b.currency(), b.active(),
                b.smigCoefficient());
    }

    @PutMapping("/grades/{id}")
    @PreAuthorize("hasAuthority('masterdata.manage')")
    public MasterData.Grade updateGrade(@PathVariable long id, @Valid @RequestBody GradeInput b) {
        return masterData.saveGrade(id, b.categoryId(), b.code().trim(), b.name().trim(), b.baseSalary(), b.currency(), b.active(),
                b.smigCoefficient());
    }

    @PutMapping("/grade-categories/{id}/raise")
    @PreAuthorize("hasAuthority('masterdata.manage')")
    public Map<String, BigDecimal> setRaise(@PathVariable long id, @Valid @RequestBody RaiseInput b) {
        masterData.setRaisePercent(id, b.percent());
        if (b.timing() != null) {
            masterData.setRaiseTiming(id, b.timing());
        }
        return Map.of("percent", masterData.raisePercent(id));
    }

    @PutMapping("/grade-categories/{id}/notice")
    @PreAuthorize("hasAuthority('masterdata.manage')")
    public void setNotice(@PathVariable long id, @Valid @RequestBody NoticeInput b) {
        masterData.setNotice(id, b.base(), b.baseUnit(), b.daysPerYear(), b.dayKind(), b.resignationShare());
    }

    @GetMapping("/occupations")
    public List<MasterData.Occupation> occupations() {
        return masterData.occupations();
    }

    @PostMapping("/occupations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('masterdata.manage')")
    public MasterData.Occupation addOccupation(@Valid @RequestBody OccupationInput b) {
        return masterData.saveOccupation(null, b.code().trim(), b.name().trim(), b.active());
    }

    @PutMapping("/occupations/{id}")
    @PreAuthorize("hasAuthority('masterdata.manage')")
    public MasterData.Occupation updateOccupation(@PathVariable long id, @Valid @RequestBody OccupationInput b) {
        return masterData.saveOccupation(id, b.code().trim(), b.name().trim(), b.active());
    }

    @GetMapping("/users/{userId}/file")
    @PreAuthorize("hasAuthority('salary.read')")
    public EmployeeFileView file(@PathVariable long userId) {
        EmployeeFile.Profile profile = files.profile(userId);
        return new EmployeeFileView(files.contracts(userId), profile,
                files.dependantRows(userId, java.time.LocalDate.now(clock)), refs.path(profile.birthPlaceId()),
                refs.path(profile.addressPlaceId()));
    }

    @PostMapping("/users/{userId}/contracts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('salary.write')")
    public EmployeeFile.Contract startContract(@PathVariable long userId, @Valid @RequestBody ContractInput b) {
        // The agreed salary (negotiatedBasic) or net: the basic stays the grade's, the rest is the complement.
        return negotiated.startContract(userId, b.contractType(), b.startDate(), b.endDate(), b.gradeId(), b.occupationId(),
                b.expatriate(), b.negotiatedBasic(), b.agreedNet(), b.note(), b.hireDate());
    }

    public record GradeChangeInput(@NotNull Long gradeId, @NotNull LocalDate from, Boolean keepNet,
                                   @Size(max = 300) String note) {}

    public record GradeCorrectionInput(@NotNull Long gradeId) {}

    @PostMapping("/users/{userId}/grade-change")
    @PreAuthorize("hasAuthority('salary.write')")
    public EmployeeFile.Contract changeGrade(@PathVariable long userId, @Valid @RequestBody GradeChangeInput b) {
        return negotiated.changeGrade(userId, b.gradeId(), b.from(), Boolean.TRUE.equals(b.keepNet()), b.note());
    }

    @PutMapping("/users/{userId}/contracts/{contractId}/grade")
    @PreAuthorize("hasAuthority('salary.write')")
    public EmployeeFile.Contract correctGrade(@PathVariable long userId, @PathVariable long contractId,
                                              @Valid @RequestBody GradeCorrectionInput b) {
        return files.correctGrade(userId, contractId, b.gradeId());
    }

    @PutMapping("/users/{userId}/profile")
    @PreAuthorize("hasAuthority('salary.write')")
    public EmployeeFile.Profile saveProfile(@PathVariable long userId, @Valid @RequestBody ProfileInput b) {
        return files.saveProfile(userId, new EmployeeFile.Profile(b.birthDate(), b.gender(), b.nationality(),
                b.maritalStatus(), b.cnssNumber(), b.taxNumber(), b.paymentMethod(), b.bankName(), b.bankAccount(),
                b.mobileMoneyOperator(), b.mobileMoneyNumber(), b.commune(), b.nursingBreakUntil(), b.nationalityCode(),
                b.birthCountryCode(), b.birthPlaceId(), b.birthPlaceText(), b.addressPlaceId(), b.addressQuarter(),
                b.addressStreet(), b.bankId(), b.mobileMoneyOperatorId(), b.idDocumentType(), b.idDocumentNumber(),
                b.idDocumentIssuedOn(), b.idDocumentIssuedAt()));
    }

    @PostMapping("/users/{userId}/dependants")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('salary.write')")
    public EmployeeFile.Dependant addDependant(@PathVariable long userId, @Valid @RequestBody DependantInput b) {
        return files.addDependant(userId, b.fullName(), b.relationship(), b.birthDate(), b.inCharge(),
                Boolean.TRUE.equals(b.student()), Boolean.TRUE.equals(b.disabled()), b.note());
    }

    @PutMapping("/users/{userId}/dependants/{id}")
    @PreAuthorize("hasAuthority('salary.write')")
    public List<EmployeeFile.DependantRow> updateDependant(@PathVariable long userId, @PathVariable long id,
                                                           @Valid @RequestBody DependantInput b) {
        files.updateDependant(userId, id, b.fullName(), b.relationship(), b.birthDate(), b.inCharge(),
                Boolean.TRUE.equals(b.student()), Boolean.TRUE.equals(b.disabled()), b.note());
        return files.dependantRows(userId, java.time.LocalDate.now(clock));
    }

    @DeleteMapping("/users/{userId}/dependants/{id}")
    @PreAuthorize("hasAuthority('salary.write')")
    public List<EmployeeFile.DependantRow> removeDependant(@PathVariable long userId, @PathVariable long id) {
        files.removeDependant(userId, id);
        return files.dependantRows(userId, java.time.LocalDate.now(clock));
    }
}
