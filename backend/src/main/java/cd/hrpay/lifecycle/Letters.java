package cd.hrpay.lifecycle;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.Notifications;
import cd.hrpay.notify.OtpService;
import cd.hrpay.payment.Company;
import cd.hrpay.payroll.EmployeeFile;
import cd.hrpay.salary.SalaryService;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contracts and letters made from the company's templates, and signed by the employee in HR Pay. The employee reads
 * the PDF, asks for a one-time code (email, and WhatsApp where it is set up), types their name and the code; HR Pay
 * then adds a signature page that names them, the time, the address they signed from and the SHA-256 fingerprint
 * of the exact PDF they read, and files the signed copy in their documents. A sent document never changes: to
 * correct it, cancel it and send a new one.
 */
@Service
public class Letters {

    public static final String PURPOSE = "LETTER_SIGN";

    /** The fields a template can use, written {{field}}, with what each holds. */
    public static final Map<String, String> FIELDS = fields();

    private static Map<String, String> fields() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("employee.name", "Full name");
        m.put("employee.first_name", "First name");
        m.put("employee.last_name", "Last name");
        m.put("employee.postnom", "Postnom");
        m.put("employee.registration_no", "Registration no.");
        m.put("employee.birth_date", "Date of birth");
        m.put("employee.nationality", "Nationality");
        m.put("employee.birth_place", "Place of birth");
        m.put("employee.address", "Address");
        m.put("employee.email", "Email");
        m.put("employee.phone", "Phone");
        m.put("employee.hire_date", "Hire date");
        m.put("employee.gender", "Sex");
        m.put("employee.civility", "Mr or Ms");
        m.put("employee.born", "Born (né or née)");
        m.put("employee.marital_status", "Marital status");
        m.put("employee.cnss_no", "CNSS number of the employee");
        m.put("employee.id_document", "Identity document");
        m.put("employee.spouse", "Spouse");
        m.put("employee.children", "Children in charge");
        m.put("contract.type", "Kind of contract");
        m.put("contract.start", "Contract start");
        m.put("contract.end", "Contract end");
        m.put("contract.duration", "Contract duration, in words");
        m.put("contract.reason", "Why the contract is fixed-term");
        m.put("contract.trial", "Trial period");
        m.put("contract.notice", "Notice period");
        m.put("contract.work_place", "Place of work");
        m.put("contract.supervisor", "Supervisor");
        m.put("contract.hours", "Working hours");
        m.put("contract.medical", "Medical fitness for work");
        m.put("contract.signed_place", "Place where the contract is signed");
        m.put("job.title", "Occupation");
        m.put("job.purpose", "Purpose of the job (job description)");
        m.put("job.duties", "Main duties (job description)");
        m.put("job.requirements", "Education and experience required (job description)");
        m.put("job.grade", "Grade");
        m.put("job.category", "Grade category");
        m.put("job.grade_code", "Grade code");
        m.put("job.echelon", "Step (échelon)");
        m.put("salary.gross", "Gross monthly salary, with its currency");
        m.put("salary.basic", "Basic salary, with its currency");
        m.put("salary.complement", "Salary complement, with its currency");
        m.put("site.name", "Site");
        m.put("unit.name", "Unit in the structure");
        m.put("unit.path", "Units, from the top (Direction / Department / Service)");
        m.put("company.name", "Company name");
        m.put("company.address", "Company address");
        m.put("company.rccm", "Company RCCM");
        m.put("company.national_id", "Company national identification");
        m.put("company.tax_no", "Company tax number");
        m.put("company.cnss_no", "Company CNSS number");
        m.put("company.representative", "Who signs for the company");
        m.put("company.representative_title", "Title of who signs for the company");
        m.put("signatory.name", "Name of the person who sends the document");
        m.put("today", "Today's date");
        // A discipline case's own fields, filled in by the discipline register when it writes the letter.
        m.put("discipline.number", "Discipline: case number");
        m.put("discipline.facts", "Discipline: the facts");
        m.put("discipline.facts_date", "Discipline: date of the facts");
        m.put("discipline.hearing_date", "Discipline: date and time of the hearing");
        m.put("discipline.hearing_place", "Discipline: place of the hearing");
        m.put("discipline.heard_on", "Discipline: day of the hearing");
        m.put("discipline.sanction", "Discipline: the sanction");
        m.put("discipline.decided_on", "Discipline: day of the decision");
        m.put("discipline.suspension_days", "Discipline: days of mise à pied");
        m.put("discipline.suspension_from", "Discipline: first day of mise à pied");
        m.put("discipline.suspension_to", "Discipline: last day of mise à pied");
        m.put("discipline.return_date", "Discipline: day work resumes");
        // A promotion's or transfer's own fields, filled in by the career moves when they write the document.
        m.put("career.effective_on", "Move: date it takes effect");
        m.put("career.change", "Move: what changes (grade, occupation, unit, site)");
        m.put("career.new_basic", "Move: basic salary of the new grade");
        m.put("career.pay_note", "Move: what happens to the pay");
        m.put("career.trial", "Move: trial period of the promotion");
        m.put("career.housing", "Move: housing when the place of work changes (art. 138)");
        // A work accident's own fields, filled in by the accident register when it drafts the declaration.
        m.put("accident.kind", "Accident: kind (at work, on the way, disease)");
        m.put("accident.date", "Accident: date");
        m.put("accident.time", "Accident: time");
        m.put("accident.place", "Accident: place");
        m.put("accident.description", "Accident: what happened");
        m.put("accident.witnesses", "Accident: witnesses");
        m.put("accident.injury", "Accident: injury");
        m.put("accident.first_aid", "Accident: first aid given");
        m.put("accident.days_off", "Accident: days off work");
        return m;
    }

    private static final Pattern FIELD = Pattern.compile("\\{\\{\\s*([a-z_.]+)\\s*}}");

    public record Template(long id, String code, String name, String kind, String body, boolean requiresSignature,
                           Long documentTypeId, boolean active, String updatedBy, OffsetDateTime updatedAt, String docCode,
                           String docVersion, String docClassification, String docOwner, String docApprover,
                           LocalDate docAdoptedOn, LocalDate docEffectiveOn) {}

    /** The document control of a model, printed in a contract's annex. */
    public record Control(String code, String version, String classification, String owner, String approver,
                          LocalDate adoptedOn, LocalDate effectiveOn) {

        static final Control NONE = new Control(null, null, null, null, null, null, null);
    }

    public record Letter(long id, long userId, String employeeName, Long templateId, String kind, String number, String title,
                         String body, boolean requiresSignature, String status, String sha256, String sentBy, String sentByName,
                         OffsetDateTime sentAt, String signedSha256, OffsetDateTime signedAt, String signedName, String signedIp,
                         String declinedReason, Long documentId, String createdBy, OffsetDateTime createdAt, Long contractId,
                         LocalDate signableFrom) {}

    public record Draft(String title, String body, List<String> missing, List<ContractTerms.Issue> issues) {

        Draft(String title, String body, List<String> missing) {
            this(title, body, missing, List.of());
        }
    }

    public record CodeSent(UUID challengeId, List<String> channels, OffsetDateTime expiresAt) {}

    private static final String SELECT = """
            select l.id, l.user_id, person_name(u.first_name, u.middle_name, u.last_name) as employee_name, l.template_id, l.kind, l.number, l.title,
                   l.body, l.requires_signature, l.status, l.sha256, l.sent_by, l.sent_by_name, l.sent_at, l.signed_sha256,
                   l.signed_at, l.signed_name, l.signed_ip, l.declined_reason, l.document_id, l.created_by, l.created_at,
                   l.contract_id, null::date as signable_from
            from employee_letter l join app_user u on u.id = l.user_id""";

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final EmployeeFile files;
    private final SalaryService salaries;
    private final Company company;
    private final Documents documents;
    private final LifecyclePdf pdf;
    private final OtpService otp;
    private final Notifications notifications;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private ContractTerms terms;
    private cd.hrpay.schedule.WorkCalendar calendar;
    private DocxWriter docx;

    @org.springframework.beans.factory.annotation.Autowired
    void setContractParts(ContractTerms terms, cd.hrpay.schedule.WorkCalendar calendar, DocxWriter docx) {
        this.terms = terms;
        this.calendar = calendar;
        this.docx = docx;
    }

    public Letters(JdbcClient jdbc, UserRepository users, EmployeeFile files, SalaryService salaries, Company company,
                   Documents documents, LifecyclePdf pdf, OtpService otp, Notifications notifications, AuditService audit,
                   JsonMapper json, Clock clock, org.springframework.transaction.PlatformTransactionManager txManager) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.jdbc = jdbc;
        this.users = users;
        this.files = files;
        this.salaries = salaries;
        this.company = company;
        this.documents = documents;
        this.pdf = pdf;
        this.otp = otp;
        this.notifications = notifications;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // ---- templates ----------------------------------------------------------------------------------------------

    public List<Template> templates() {
        return jdbc.sql("select * from letter_template order by kind, name").query(Template.class).list();
    }

    public Template template(long id) {
        return jdbc.sql("select * from letter_template where id = ?").param(id).query(Template.class).optional()
                .orElseThrow(() -> new NotFoundException("Template not found."));
    }

    @Transactional
    public Template saveTemplate(Long id, String code, String name, String kind, String body, boolean requiresSignature,
                                 Long documentTypeId, boolean active) {
        return saveTemplate(id, code, name, kind, body, requiresSignature, documentTypeId, active, null);
    }

    @Transactional
    public Template saveTemplate(Long id, String code, String name, String kind, String body, boolean requiresSignature,
                                 Long documentTypeId, boolean active, Control control) {
        if (!Set.of("CONTRACT", "LETTER").contains(kind)) {
            throw new BusinessRuleException("A template is a contract or a letter.");
        }
        Set<String> unknown = unknownFields(body);
        if (!unknown.isEmpty()) {
            throw new BusinessRuleException("Unknown fields: %s.".formatted(String.join(", ", unknown)));
        }
        String c = code.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        jdbc.sql("select id from letter_template where code = ? and id <> ?").params(c, id == null ? -1 : id).query(Long.class)
                .optional().ifPresent(x -> {
                    throw new ConflictException("A template with the code %s exists already.".formatted(c));
                });
        long saved;
        if (id == null) {
            saved = jdbc.sql("""
                    insert into letter_template (code, name, kind, body, requires_signature, document_type_id, active, updated_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(c, name.trim(), kind, body, requiresSignature, documentTypeId, active, CurrentUser.email())
                    .query(Long.class).single();
        } else {
            template(id);
            jdbc.sql("""
                    update letter_template set code = ?, name = ?, kind = ?, body = ?, requires_signature = ?, document_type_id = ?,
                        active = ?, updated_by = ?, updated_at = now() where id = ?""")
                    .params(c, name.trim(), kind, body, requiresSignature, documentTypeId, active, CurrentUser.email(), id).update();
            saved = id;
        }
        if (control != null) {
            jdbc.sql("""
                    update letter_template set doc_code = ?, doc_version = ?, doc_classification = ?, doc_owner = ?, doc_approver = ?,
                        doc_adopted_on = ?, doc_effective_on = ? where id = ?""")
                    .params(blank(control.code()), blank(control.version()), blank(control.classification()), blank(control.owner()),
                            blank(control.approver()), control.adoptedOn(), control.effectiveOn(), saved).update();
        }
        audit.record("letter_template.saved", "letter_template", saved, Map.of("code", c, "active", active));
        return template(saved);
    }

    static Set<String> unknownFields(String body) {
        Set<String> unknown = new LinkedHashSet<>();
        Matcher m = FIELD.matcher(body == null ? "" : body);
        while (m.find()) {
            if (!FIELDS.containsKey(m.group(1))) {
                unknown.add(m.group(1));
            }
        }
        return unknown;
    }

    // ---- filling a template for one person -------------------------------------------------------------------

    /** What each field holds for this person today; an empty value is left out. */
    public Map<String, String> values(long userId) {
        return values(userId, null);
    }

    /**
     * What each field holds for this person today, for the given contract (or their latest one); an empty value is
     * left out, so that the document names it as missing.
     */
    public Map<String, String> values(long userId, Long contractId) {
        AppUser u = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found."));
        LocalDate today = LocalDate.now(clock);
        boolean fr = Texts.french();
        Map<String, String> v = new LinkedHashMap<>();
        v.put("employee.name", u.fullName());
        v.put("employee.first_name", u.getFirstName());
        v.put("employee.last_name", u.getLastName());
        v.put("employee.postnom", u.getMiddleName() == null ? "" : u.getMiddleName());
        v.put("employee.registration_no", u.getRegistrationNo());
        v.put("employee.email", u.getEmail());
        v.put("employee.phone", u.getPhone());
        EmployeeFile.Profile p = files.profile(userId);
        if (p.birthDate() != null) {
            v.put("employee.birth_date", Texts.day(p.birthDate()));
        }
        v.put("employee.nationality", files.nationality(p));
        v.put("employee.birth_place", files.birthPlace(p));
        v.put("employee.address", files.address(p));
        if (p.gender() != null) {
            boolean woman = p.gender().equals("FEMALE");
            v.put("employee.gender", woman ? Texts.t("Female") : Texts.t("Male"));
            v.put("employee.civility", woman ? Texts.t("Ms") : Texts.t("Mr"));
            v.put("employee.born", fr ? (woman ? "née" : "né") : "born");
        }
        v.put("employee.marital_status", maritalStatus(p.maritalStatus()));
        v.put("employee.cnss_no", p.cnssNumber() == null || p.cnssNumber().isBlank()
                ? Texts.t("affiliation in progress") : p.cnssNumber());
        if (p.idDocumentNumber() != null && !p.idDocumentNumber().isBlank()) {
            v.put("employee.id_document", idDocument(p));
        }
        List<EmployeeFile.Dependant> family = files.dependants(userId);
        String spouse = family.stream().filter(d -> d.relationship().equals("SPOUSE")).map(EmployeeFile.Dependant::fullName)
                .collect(java.util.stream.Collectors.joining(", "));
        v.put("employee.spouse", spouse.isEmpty() ? Texts.t("none") : spouse);
        String children = family.stream().filter(d -> d.relationship().equals("CHILD") && d.inCharge())
                .map(d -> d.birthDate() == null ? d.fullName() : Texts.t("%s, born on %s", d.fullName(), Texts.day(d.birthDate())))
                .collect(java.util.stream.Collectors.joining("; "));
        v.put("employee.children", children.isEmpty() ? Texts.t("none") : children);
        var contracts = files.contracts(userId);
        LocalDate hired = u.getHireDate() != null ? u.getHireDate()
                : contracts.stream().map(EmployeeFile.Contract::startDate).min(LocalDate::compareTo).orElse(null);
        if (hired != null) {
            v.put("employee.hire_date", Texts.day(hired));
        }
        var chosen = contractId != null ? contracts.stream().filter(c -> c.id() == contractId).findFirst()
                : contracts.stream().max(java.util.Comparator.comparing(EmployeeFile.Contract::startDate));
        LocalDate payDay = today;
        if (chosen.isPresent()) {
            EmployeeFile.Contract c = chosen.get();
            ContractTerms.Terms t = terms.terms(c.id());
            payDay = today.isBefore(c.startDate()) ? c.startDate() : today;
            v.put("contract.type", contractType(c.contractType()));
            v.put("contract.start", Texts.day(c.startDate()));
            if (c.endDate() != null) {
                v.put("contract.end", Texts.day(c.endDate()));
            }
            v.put("contract.duration", duration(c, fr));
            if (t.fixedTermReason() != null) {
                String reason = Texts.t(REASON_LABELS.get(t.fixedTermReason()));
                v.put("contract.reason", t.fixedTermDetail() == null ? reason : reason + " (" + t.fixedTermDetail() + ")");
            }
            v.put("contract.trial", t.trialMonths() == 0 ? Texts.t("none")
                    : NumberWords.of(t.trialMonths(), fr) + " (" + t.trialMonths() + ") " + (fr ? "mois" : t.trialMonths() == 1 ? "month" : "months"));
            v.put("contract.notice", notice(c, fr));
            v.put("contract.work_place", t.workPlace() != null ? t.workPlace() : terms.defaultWorkPlace(userId));
            v.put("contract.supervisor", t.supervisor() != null ? t.supervisor() : terms.defaultSupervisor(userId).orElse(null));
            v.put("contract.hours", hours(userId, c.startDate().isAfter(today) ? c.startDate() : today, fr));
            if (t.medicalFitOn() != null) {
                v.put("contract.medical", t.medicalDoctor() == null
                        ? Texts.t("fit for work, medical certificate of %s", Texts.day(t.medicalFitOn()))
                        : Texts.t("fit for work, medical certificate of %s (%s)", Texts.day(t.medicalFitOn()), t.medicalDoctor()));
            }
            v.put("contract.signed_place", t.signedPlace() != null ? t.signedPlace() : company.representative().signingPlace());
            v.put("job.title", c.occupationName());
            if (c.occupationId() != null) {
                Integer signed = jdbc.sql("select occupation_version from employee_contract where id = ?").param(c.id())
                        .query(Integer.class).optional().orElse(null);
                jdbc.sql("""
                        select purpose, duties, concat_ws('. ', education, experience) from occupation_version
                        where occupation_id = ? and version = coalesce(?, (select version from occupation where id = ?))""")
                        .params(c.occupationId(), signed, c.occupationId())
                        .query(rs -> {
                            if (rs.getString(1) != null) {
                                v.put("job.purpose", rs.getString(1));
                            }
                            if (rs.getString(2) != null) {
                                v.put("job.duties", rs.getString(2));
                            }
                            if (rs.getString(3) != null && !rs.getString(3).isBlank()) {
                                v.put("job.requirements", rs.getString(3));
                            }
                        });
            }
            v.put("job.grade", c.gradeCode() + " · " + c.gradeName());
            v.put("job.grade_code", c.gradeCode());
            v.put("job.echelon", c.gradeName());
            jdbc.sql("select name from grade_category where id = ?").param(c.categoryId()).query(String.class).optional()
                    .ifPresent(n -> v.put("job.category", n));
        }
        LocalDate on = payDay;
        salaries.agreed(userId, on).ifPresent(s -> v.put("salary.gross", amount(s.grossMonthly()) + " " + s.currency()));
        salaries.on(userId, on).ifPresent(s -> {
            v.put("salary.basic", amount(s.grossMonthly()) + " " + s.currency());
            BigDecimal total = salaries.agreed(userId, on).map(SalaryService.Salary::grossMonthly).orElse(s.grossMonthly());
            v.put("salary.complement", amount(total.subtract(s.grossMonthly())) + " " + s.currency());
        });
        if (u.getSite() != null) {
            v.put("site.name", u.getSite().getName());
        }
        jdbc.sql("""
                select u.name from org_assignment a join org_unit u on u.id = a.unit_id
                where a.user_id = ? and a.valid_to is null order by a.valid_from desc limit 1""")
                .param(userId).query(String.class).optional().ifPresent(n -> v.put("unit.name", n));
        terms.unitPath(userId).or(() -> java.util.Optional.ofNullable(v.get("unit.name"))).ifPresent(n -> v.put("unit.path", n));
        Company.Details employer = company.get();
        v.put("company.name", employer.name());
        v.put("company.address", employer.address());
        v.put("company.rccm", employer.rccm());
        v.put("company.national_id", employer.nationalId());
        v.put("company.tax_no", employer.taxNumber());
        v.put("company.cnss_no", employer.cnssNumber());
        Company.Representative rep = company.representative();
        v.put("company.representative", rep.name());
        v.put("company.representative_title", rep.title());
        Long me = CurrentUser.id();
        if (me != null) {
            users.findById(me).ifPresent(s -> v.put("signatory.name", s.fullName()));
        }
        v.put("today", Texts.day(today));
        v.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isBlank());
        return v;
    }

    static final Map<String, String> REASON_LABELS = Map.of(
            "TEMPORARY_NEED", "temporary need of the business",
            "REPLACEMENT", "replacement of a worker temporarily absent",
            "SPECIFIC_WORK", "a defined piece of work",
            "SEASONAL", "seasonal work",
            "WORKS", "building or public works",
            "PROJECT", "a programme or project of fixed duration",
            "HUMANITARIAN", "humanitarian activities",
            "PART_TIME", "provisional part-time work",
            "AWAITING_HOLDER", "provisional work until the recruited holder arrives",
            "ACTIVITY_PEAK", "temporary increase in activity");

    private String maritalStatus(String code) {
        return switch (code == null ? "" : code) {
            case "MARRIED" -> Texts.t("married");
            case "DIVORCED" -> Texts.t("divorced");
            case "WIDOWED" -> Texts.t("widowed");
            default -> Texts.t("single");
        };
    }

    private String idDocument(EmployeeFile.Profile p) {
        String kind = switch (p.idDocumentType() == null ? "" : p.idDocumentType()) {
            case "NATIONAL_ID" -> Texts.t("national identity card");
            case "ELECTOR_CARD" -> Texts.t("voter card");
            case "PASSPORT" -> Texts.t("passport");
            case "RESIDENCE_PERMIT" -> Texts.t("residence permit");
            case "DRIVING_LICENCE" -> Texts.t("driving licence");
            default -> Texts.t("identity document");
        };
        String s = kind + " " + Texts.t("no. %s", p.idDocumentNumber());
        if (p.idDocumentIssuedOn() != null) {
            s += ", " + Texts.t("issued on %s", Texts.day(p.idDocumentIssuedOn()));
        }
        if (p.idDocumentIssuedAt() != null && !p.idDocumentIssuedAt().isBlank()) {
            s += " " + Texts.t("in %s", p.idDocumentIssuedAt());
        }
        return s;
    }

    /** "douze (12) mois" when the contract runs whole months, otherwise the days; "indéterminée" for a permanent one. */
    static String duration(EmployeeFile.Contract c, boolean fr) {
        if (c.endDate() == null) {
            return fr ? "indéterminée" : "indefinite";
        }
        LocalDate after = c.endDate().plusDays(1);
        long months = java.time.temporal.ChronoUnit.MONTHS.between(c.startDate(), after);
        if (months > 0 && c.startDate().plusMonths(months).equals(after)) {
            return NumberWords.of(months, fr) + " (" + months + ") " + (fr ? "mois" : months == 1 ? "month" : "months");
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(c.startDate(), after);
        return NumberWords.of(days, fr) + " (" + days + ") " + (fr ? (days == 1 ? "jour" : "jours") : days == 1 ? "day" : "days");
    }

    /**
     * The notice: none for a fixed-term contract, which ends at its term (Labour Code art. 69); for a permanent one,
     * what the grade category sets (art. 62-64 and arrêté 12/CAB.MIN/TPS/117/2005).
     */
    private String notice(EmployeeFile.Contract c, boolean fr) {
        if ("FIXED_TERM".equals(c.contractType())) {
            return Texts.t("none: the fixed-term contract ends at its term without notice (Labour Code art. 69)");
        }
        record Notice(BigDecimal noticeBase, String noticeBaseUnit, BigDecimal noticeDaysPerYear, String noticeDayKind) {}
        return jdbc.sql("select notice_base, notice_base_unit, notice_days_per_year, notice_day_kind from grade_category where id = ?")
                .param(c.categoryId()).query(Notice.class).optional().map(n -> {
                    boolean working = "WORKING".equals(n.noticeDayKind());
                    String dayWord = working ? Texts.t("working days") : Texts.t("calendar days");
                    String base = "MONTHS".equals(n.noticeBaseUnit())
                            ? NumberWords.of(n.noticeBase().longValue(), fr) + " (" + n.noticeBase().stripTrailingZeros().toPlainString() + ") "
                              + (fr ? "mois" : n.noticeBase().intValue() == 1 ? "month" : "months")
                            : n.noticeBase().stripTrailingZeros().toPlainString() + " " + dayWord;
                    return n.noticeDaysPerYear().signum() == 0 ? base
                            : Texts.t("%s, plus %s %s per full year of service", base,
                                    n.noticeDaysPerYear().stripTrailingZeros().toPlainString(), dayWord);
                }).orElse(null);
    }

    /** "40 heures par semaine, du lundi au vendredi : 08h30-12h00 et 12h30-17h00", from the person's schedule. */
    private String hours(long userId, LocalDate on, boolean fr) {
        return calendar.scheduleOn(userId, on).map(s -> {
            List<String> groups = new ArrayList<>();
            java.time.format.TextStyle style = java.time.format.TextStyle.FULL;
            Locale locale = fr ? Locale.FRENCH : Locale.ENGLISH;
            int i = 1;
            while (i <= 7) {
                var day = s.day(java.time.DayOfWeek.of(i));
                if (day == null || !day.isWorkday() || day.getStartTime() == null) {
                    i++;
                    continue;
                }
                String spans = spans(day, fr);
                int j = i;
                while (j < 7) {
                    var next = s.day(java.time.DayOfWeek.of(j + 1));
                    if (next == null || !next.isWorkday() || next.getStartTime() == null || !spans(next, fr).equals(spans)) {
                        break;
                    }
                    j++;
                }
                String first = java.time.DayOfWeek.of(i).getDisplayName(style, locale);
                String last = java.time.DayOfWeek.of(j).getDisplayName(style, locale);
                String days = i == j ? (fr ? "le " + first : "on " + first)
                        : (fr ? "du " + first + " au " + last : "from " + first + " to " + last);
                groups.add(days + " : " + spans);
                i = j + 1;
            }
            int minutes = s.weeklyMinutes();
            String total = (minutes % 60 == 0 ? Integer.toString(minutes / 60)
                    : new BigDecimal(minutes).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
                    + (fr ? " heures par semaine" : " hours a week");
            String text = groups.isEmpty() ? total : total + ", " + String.join(" ; ", groups);
            return fr ? text : text.replace(" : ", ": ").replace(" ; ", "; ");
        }).orElse(null);
    }

    private static String spans(cd.hrpay.schedule.ScheduleDay d, boolean fr) {
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern(fr ? "HH'h'mm" : "HH:mm");
        if (d.isLunchEnabled() && d.getLunchStart() != null && d.getLunchMinutes() != null && d.getLunchMinutes() > 0) {
            var back = d.getLunchStart().plusMinutes(d.getLunchMinutes());
            return f.format(d.getStartTime()) + "-" + f.format(d.getLunchStart()) + (fr ? " et " : " and ")
                    + f.format(back) + "-" + f.format(d.getEndTime());
        }
        return f.format(d.getStartTime()) + "-" + f.format(d.getEndTime());
    }

    static String contractType(String code) {
        return switch (code == null ? "" : code) {
            case "PERMANENT" -> Texts.t("permanent contract (CDI)");
            case "FIXED_TERM" -> Texts.t("fixed-term contract (CDD)");
            case "INTERN" -> Texts.t("internship agreement");
            case "CONSULTANT" -> Texts.t("consultancy agreement");
            case "APPRENTICE" -> Texts.t("apprenticeship contract");
            case "DAILY" -> Texts.t("day labour contract");
            default -> code;
        };
    }

    static String amount(BigDecimal v) {
        java.text.NumberFormat f = java.text.NumberFormat.getNumberInstance(Texts.locale());
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return f.format(v);
    }

    /** The text with every field filled in; a field with no value for this person shows as "…" and is named. */
    public Draft fill(long userId, String title, String body) {
        return fill(userId, null, title, body);
    }

    public Draft fill(long userId, Long contractId, String title, String body) {
        Map<String, String> v = Texts.in(company.language(), () -> values(userId, contractId));
        Set<String> missing = new LinkedHashSet<>();
        String filled = replace(body, v, missing);
        String t = replace(title, v, missing);
        return new Draft(t, filled, List.copyOf(missing));
    }

    private static String replace(String text, Map<String, String> values, Set<String> missing) {
        Matcher m = FIELD.matcher(text == null ? "" : text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null) {
                missing.add(m.group(1));
                value = "…";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    public Draft preview(long userId, long templateId) {
        return preview(userId, templateId, null);
    }

    /** The template filled for the person (and the contract, for a contract), with the legal points to look at. */
    public Draft preview(long userId, long templateId, Long contractId) {
        Template t = template(templateId);
        Draft d = fill(userId, contractId, t.name(), t.body());
        return new Draft(d.title(), d.body(), d.missing(), contractIssues(userId, t.kind(), contractId));
    }

    /** The contract the document is for: the one given, else the person's latest. */
    private Long contractFor(long userId, Long contractId) {
        if (contractId != null) {
            terms.contract(userId, contractId);
            return contractId;
        }
        return files.contracts(userId).stream().max(java.util.Comparator.comparing(EmployeeFile.Contract::startDate))
                .map(EmployeeFile.Contract::id).orElse(null);
    }

    private List<ContractTerms.Issue> contractIssues(long userId, String kind, Long contractId) {
        if (!"CONTRACT".equals(kind)) {
            return List.of();
        }
        Long id = contractFor(userId, contractId);
        if (id == null) {
            return List.of(new ContractTerms.Issue("BLOCK", "NO_CONTRACT", Texts.in(company.language(),
                    () -> Texts.t("Record the contract on the employee file first."))));
        }
        EmployeeFile.Contract c = terms.contract(userId, id);
        return Texts.in(company.language(), () -> terms.issues(userId, c, terms.terms(id)));
    }

    // ---- letters ------------------------------------------------------------------------------------------------

    public List<Letter> of(long userId) {
        return jdbc.sql(SELECT + " where l.user_id = ? and l.status <> 'CANCELLED' order by l.created_at desc").param(userId)
                .query(Letter.class).list().stream().map(this::withSignable).toList();
    }

    /**
     * A contract may be signed two working days after its draft reached the person (Labour Code art. 46): after it
     * was sent in HR Pay, or after the paper draft was handed over when that came first.
     */
    private Letter withSignable(Letter l) {
        if (!"CONTRACT".equals(l.kind()) || !l.requiresSignature() || l.sentAt() == null) {
            return l;
        }
        LocalDate sent = l.sentAt().atZoneSameInstant(ZoneId.of("Africa/Kinshasa")).toLocalDate();
        LocalDate given = l.contractId() == null ? null : terms.terms(l.contractId()).draftGivenOn();
        LocalDate from = terms.earliestSigning(given != null && given.isBefore(sent) ? given : sent);
        return new Letter(l.id(), l.userId(), l.employeeName(), l.templateId(), l.kind(), l.number(), l.title(), l.body(),
                l.requiresSignature(), l.status(), l.sha256(), l.sentBy(), l.sentByName(), l.sentAt(), l.signedSha256(), l.signedAt(),
                l.signedName(), l.signedIp(), l.declinedReason(), l.documentId(), l.createdBy(), l.createdAt(), l.contractId(), from);
    }

    /** What the person sees: everything sent to them. */
    public List<Letter> mine(long userId) {
        return jdbc.sql(SELECT + " where l.user_id = ? and l.status in ('SENT', 'SIGNED', 'DECLINED') order by l.sent_at desc")
                .param(userId).query(Letter.class).list().stream().map(this::withSignable).toList();
    }

    public List<Letter> waiting() {
        return jdbc.sql(SELECT + " where l.status = 'SENT' and l.requires_signature order by l.sent_at").query(Letter.class).list();
    }

    public Letter find(long id) {
        return jdbc.sql(SELECT + " where l.id = ?").param(id).query(Letter.class).optional().map(this::withSignable)
                .orElseThrow(() -> new NotFoundException("Document not found."));
    }

    @Transactional
    public Letter create(long userId, Long templateId, String title, String body, String kind, boolean requiresSignature) {
        return create(userId, templateId, title, body, kind, requiresSignature, null);
    }

    @Transactional
    public Letter create(long userId, Long templateId, String title, String body, String kind, boolean requiresSignature,
                         Long contractId) {
        Template t = templateId == null ? null : template(templateId);
        String k = t != null ? t.kind() : kind == null ? "LETTER" : kind;
        if (!Set.of("CONTRACT", "LETTER").contains(k)) {
            throw new BusinessRuleException("A template is a contract or a letter.");
        }
        Set<String> unknown = unknownFields(body);
        if (!unknown.isEmpty()) {
            throw new BusinessRuleException("Unknown fields: %s.".formatted(String.join(", ", unknown)));
        }
        Long contract = "CONTRACT".equals(k) ? contractFor(userId, contractId) : null;
        long id = jdbc.sql("""
                insert into employee_letter (user_id, template_id, kind, title, body, requires_signature, created_by, contract_id)
                values (?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(userId, templateId, k, title.trim(), body, requiresSignature, CurrentUser.email(), contract)
                .query(Long.class).single();
        audit.record("letter.created", "user", userId, Map.of("letter", id, "kind", k));
        return find(id);
    }

    @Transactional
    public Letter update(long id, String title, String body, boolean requiresSignature) {
        Letter l = find(id);
        if (!l.status().equals("DRAFT")) {
            throw new BusinessRuleException("A sent document cannot change: cancel it and send a new one.");
        }
        Set<String> unknown = unknownFields(body);
        if (!unknown.isEmpty()) {
            throw new BusinessRuleException("Unknown fields: %s.".formatted(String.join(", ", unknown)));
        }
        jdbc.sql("update employee_letter set title = ?, body = ?, requires_signature = ? where id = ?")
                .params(title.trim(), body, requiresSignature, id).update();
        return find(id);
    }

    /** Numbers the document, makes its PDF and sends it: to sign, or to keep when it needs no signature. */
    @Transactional
    public Letter send(long id) {
        Letter l = find(id);
        if (!l.status().equals("DRAFT")) {
            throw new BusinessRuleException("This document was sent already.");
        }
        Draft d = fill(l.userId(), l.contractId(), l.title(), l.body());
        if (!d.missing().isEmpty()) {
            throw new BusinessRuleException("Fill in first, in the employee's file or in the text: %s."
                    .formatted(String.join(", ", d.missing().stream().map(k -> Texts.t(FIELDS.get(k))).toList())));
        }
        List<String> blocking = contractIssues(l.userId(), l.kind(), l.contractId()).stream()
                .filter(i -> i.level().equals("BLOCK")).map(ContractTerms.Issue::message).toList();
        if (!blocking.isEmpty()) {
            throw new BusinessRuleException(String.join(" ", blocking));
        }
        AppUser sender = users.findById(CurrentUser.id()).orElseThrow();
        AppUser employee = users.findById(l.userId()).orElseThrow();
        String senderName = sender.fullName();
        int year = LocalDate.now(clock).getYear();
        int n = jdbc.sql("select count(*) from employee_letter where number like ?").param("LT-" + year + "-%")
                .query(Integer.class).single() + 1;
        String number = "LT-%d-%04d".formatted(year, n);
        byte[] file = Texts.in(company.language(), () -> {
            if ("CONTRACT".equals(l.kind())) {
                return pdf.contract(sheet(l, d, number, employee));
            }
            List<LifecyclePdf.Signature> signatures = new ArrayList<>();
            signatures.add(new LifecyclePdf.Signature(senderName, Texts.t("For the employer")));
            if (l.requiresSignature()) {
                signatures.add(new LifecyclePdf.Signature(employee.fullName(),
                        Texts.t("The employee (signed electronically, see the last page)")));
            }
            return pdf.letter(d.title(), number, d.body(), Texts.t("Done on %s.", Texts.day(LocalDate.now(clock))), signatures);
        });
        String sha = Documents.sha256(file);
        jdbc.sql("""
                update employee_letter set number = ?, title = ?, body = ?, status = 'SENT', pdf = ?, sha256 = ?, sent_by = ?,
                    sent_by_name = ?, sent_at = now() where id = ?""")
                .params(number, d.title(), d.body(), file, sha, sender.getEmail(), senderName, id).update();
        if (!l.requiresSignature()) {
            long doc = documents.add(l.userId(), documentType(l), number + ".pdf", file, null, null, d.title(), "SIGNED").id();
            jdbc.sql("update employee_letter set document_id = ? where id = ?").params(doc, id).update();
        }
        audit.record("letter.sent", "user", l.userId(), Map.of("letter", id, "number", number, "sha256", sha));
        notifications.send(l.userId(), "letter.sent",
                () -> l.requiresSignature() ? Texts.t("A document to sign") : Texts.t("A new document"),
                () -> l.requiresSignature() ? Texts.t("%s waits for your signature in HR Pay.", d.title())
                        : Texts.t("%s was added to your documents.", d.title()), "/my/documents");
        return find(id);
    }

    @Transactional
    public Letter cancel(long id) {
        Letter l = find(id);
        if (!Set.of("DRAFT", "SENT").contains(l.status())) {
            throw new BusinessRuleException("A signed document stays as it is.");
        }
        jdbc.sql("update employee_letter set status = 'CANCELLED' where id = ?").param(id).update();
        audit.record("letter.cancelled", "user", l.userId(), Map.of("letter", id));
        return find(id);
    }

    public byte[] file(long id) {
        record Files(byte[] pdf, byte[] signedPdf) {}
        Files f = jdbc.sql("select pdf, signed_pdf from employee_letter where id = ?").param(id).query(Files.class).single();
        if (f.signedPdf() != null) {
            return f.signedPdf();
        }
        if (f.pdf() == null) {
            throw new BusinessRuleException("This document is still a draft.");
        }
        return f.pdf();
    }

    /** The draft's PDF as it would be sent, for HR to read before sending. */
    public byte[] draftPdf(long id) {
        Letter l = find(id);
        Draft d = fill(l.userId(), l.contractId(), l.title(), l.body());
        if ("CONTRACT".equals(l.kind())) {
            AppUser employee = users.findById(l.userId()).orElseThrow();
            return Texts.in(company.language(), () -> pdf.contract(sheet(l, d, null, employee)));
        }
        return Texts.in(company.language(), () -> pdf.letter(d.title(), null, d.body(), Texts.t("Done on %s.", Texts.day(LocalDate.now(clock))),
                List.of(new LifecyclePdf.Signature(" ", Texts.t("For the employer")))));
    }

    /** A Word copy of the document, filled as it would be sent, for HR to adjust by hand. */
    public byte[] wordCopy(long id) {
        Letter l = find(id);
        AppUser employee = users.findById(l.userId()).orElseThrow();
        Draft d = l.status().equals("DRAFT") ? fill(l.userId(), l.contractId(), l.title(), l.body())
                : new Draft(l.title(), l.body(), List.of());
        return Texts.in(company.language(), () -> {
            if ("CONTRACT".equals(l.kind())) {
                return docx.write(sheet(l, d, l.number(), employee));
            }
            return docx.write(new ContractSheet(d.title(), null, l.number(), d.body(), null,
                    Texts.t("Done on %s.", Texts.day(LocalDate.now(clock))), l.sentByName() == null ? " " : l.sentByName(),
                    Texts.t("For the employer"), null, null, null, null, List.of(), Control.NONE, null));
        });
    }

    /** The contract as a document: its articles, the place and date, the two signatures, the notes and the annex. */
    private ContractSheet sheet(Letter l, Draft d, String number, AppUser employee) {
        Map<String, String> v = values(l.userId(), l.contractId());
        Control control = l.templateId() == null ? Control.NONE : jdbc.sql("""
                select doc_code as code, doc_version as version, doc_classification as classification, doc_owner as owner,
                       doc_approver as approver, doc_adopted_on as adopted_on, doc_effective_on as effective_on
                from letter_template where id = ?""").param(l.templateId()).query(Control.class).optional().orElse(Control.NONE);
        String body = d.body().replace("\r\n", "\n");
        String notes = null;
        Matcher split = Pattern.compile("(?m)^-{3,}\\s*$").matcher(body);
        if (split.find()) {
            notes = body.substring(split.end()).strip();
            body = body.substring(0, split.start()).strip();
        }
        ContractTerms.Terms t = l.contractId() == null ? null : terms.terms(l.contractId());
        LocalDate on = t != null && t.signedOn() != null ? t.signedOn() : LocalDate.now(clock);
        String place = v.get("contract.signed_place");
        String placeAndDate = place == null ? Texts.t("Done on %s.", Texts.day(on)) : Texts.t("Done at %s, on %s.", place, Texts.day(on));
        Company.Details employer = company.get();
        Company.Representative rep = company.representative();
        List<ContractSheet.Section> annex = new ArrayList<>();
        annex.add(new ContractSheet.Section(Texts.t("The employee"), lines(v,
                "employee.last_name", Texts.t("Surname"), "employee.postnom", Texts.t("Postnom"),
                "employee.first_name", Texts.t("First name"), "employee.gender", Texts.t("Sex"),
                "employee.registration_no", Texts.t("Registration no."), "employee.birth_date", Texts.t("Date of birth"),
                "employee.birth_place", Texts.t("Place of birth"), "employee.nationality", Texts.t("Nationality"),
                "employee.marital_status", Texts.t("Marital status"), "employee.address", Texts.t("Address"),
                "employee.id_document", Texts.t("Identity document"), "employee.cnss_no", Texts.t("CNSS no."),
                "employee.phone", Texts.t("Phone"), "employee.email", Texts.t("Email"))));
        annex.add(new ContractSheet.Section(Texts.t("Family situation"), lines(v,
                "employee.spouse", Texts.t("Spouse"), "employee.children", Texts.t("Children in charge"))));
        annex.add(new ContractSheet.Section(Texts.t("The employer"), lines(v,
                "company.name", Texts.t("Name"), "company.rccm", "RCCM", "company.national_id", Texts.t("National identification"),
                "company.tax_no", Texts.t("Tax number (NIF)"), "company.cnss_no", Texts.t("CNSS employer no."),
                "company.address", Texts.t("Registered office"), "company.representative", Texts.t("Represented by"),
                "company.representative_title", Texts.t("Capacity"))));
        List<LifecyclePdf.Line> contractLines = new ArrayList<>(lines(v, "contract.type", Texts.t("Kind of contract"),
                "contract.duration", Texts.t("Duration"), "contract.start", Texts.t("Start"), "contract.end", Texts.t("End"),
                "contract.trial", Texts.t("Trial period"), "contract.notice", Texts.t("Notice"),
                "contract.medical", Texts.t("Medical fitness")));
        if (t != null && t.draftGivenOn() != null) {
            contractLines.add(new LifecyclePdf.Line(Texts.t("Draft handed over on"), Texts.day(t.draftGivenOn())));
        }
        contractLines.add(new LifecyclePdf.Line(Texts.t("ONEM visa"), t != null && t.onemVisaNo() != null
                ? Texts.t("no. %s of %s", t.onemVisaNo(), t.onemVisaOn() == null ? "…" : Texts.day(t.onemVisaOn()))
                : Texts.t("Seen by us on ____ / ____ / ________ under no. ____________ (ONEM, within 15 days of signing)")));
        annex.add(new ContractSheet.Section(Texts.t("The contract"), contractLines));
        String reference = join(" · ", control.code() == null ? null : Texts.t("Code: %s", control.code()),
                control.version() == null ? null : Texts.t("Version: %s", control.version()),
                control.classification() == null ? null : Texts.t("Classification: %s", control.classification()));
        return new ContractSheet(d.title(), reference, number, body, notes, placeAndDate, rep.name(),
                Texts.t("For %s", employer.name()), rep.title(), employee.fullName(), Texts.t("The employee"),
                l.requiresSignature() && number != null ? Texts.t("Signed electronically, see the last page") : Texts.t("Read and approved"),
                annex, control, Texts.t("Approved for %s by:", employer.name()));
    }

    private static List<LifecyclePdf.Line> lines(Map<String, String> v, String... keyAndLabel) {
        List<LifecyclePdf.Line> out = new ArrayList<>();
        for (int i = 0; i < keyAndLabel.length; i += 2) {
            String value = v.get(keyAndLabel[i]);
            if (value != null) {
                out.add(new LifecyclePdf.Line(keyAndLabel[i + 1], value));
            }
        }
        return out;
    }

    private static String join(String sep, String... parts) {
        String s = java.util.Arrays.stream(parts).filter(p -> p != null && !p.isBlank())
                .collect(java.util.stream.Collectors.joining(sep));
        return s.isEmpty() ? null : s;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ---- the employee signs ---------------------------------------------------------------------------------

    private Letter toSign(long id, AppUser user) {
        Letter l = find(id);
        if (l.userId() != user.getId()) {
            throw new NotFoundException("Document not found.");
        }
        if (!l.status().equals("SENT") || !l.requiresSignature()) {
            throw new BusinessRuleException("This document is not waiting for your signature.");
        }
        return l;
    }

    private void requireSignable(Letter l) {
        if (l.signableFrom() != null && LocalDate.now(clock).isBefore(l.signableFrom())) {
            throw new BusinessRuleException(("You can sign this contract from %s: the law gives you two working days to read "
                    + "the draft (Labour Code art. 46).").formatted(Texts.day(l.signableFrom())));
        }
    }

    @Transactional
    public CodeSent sendCode(long id, AppUser user) {
        Letter l = toSign(id, user);
        requireSignable(l);
        OtpService.Challenge c = otp.issue(user, PURPOSE, json.writeValueAsString(Map.of("letter", l.id(), "sha256", l.sha256())));
        List<String> channels = c.deliveries().stream().filter(d -> !d.status().equals("FAILED")).map(d -> d.channel().name()).toList();
        jdbc.sql("update employee_letter set signed_channels = ? where id = ?").params(String.join(",", channels), id).update();
        return new CodeSent(c.id(), channels, c.expiresAt());
    }

    /** Signs with the code. The name typed must be the person's own name, as HR Pay knows it. */
    public Letter sign(long id, AppUser user, UUID challengeId, String code, String typedName, String ip, String agent) {
        Letter l = toSign(id, user);
        requireSignable(l);
        String expected = user.fullName();
        if (!sameName(typedName, expected)) {
            throw new BusinessRuleException("Type your name as HR Pay knows it: %s.".formatted(expected));
        }
        OtpService.Verified ok = otp.verify(challengeId, user, PURPOSE, code);
        Map<?, ?> context = json.readValue(ok.contextJson(), Map.class);
        if (!String.valueOf(context.get("letter")).equals(String.valueOf(l.id())) || !l.sha256().equals(context.get("sha256"))) {
            throw new BusinessRuleException("This code was asked for another document. Ask for a new code.");
        }
        return tx.execute(status -> signNow(l, user, typedName.trim(), ip, agent));
    }

    private Letter signNow(Letter l, AppUser user, String typedName, String ip, String agent) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        ZoneId zone = ZoneId.of(user.getSite() == null ? "Africa/Kinshasa" : user.getSite().getTimeZone());
        String channels = jdbc.sql("select signed_channels from employee_letter where id = ?").param(l.id()).query(String.class)
                .optional().orElse("");
        byte[] original = file(l.id());
        byte[] page = Texts.in(company.language(), () -> pdf.signaturePage(Texts.t("Electronic signature"), List.of(
                new LifecyclePdf.Line(Texts.t("Document"), l.title() + " · " + Texts.t("No. %s", l.number())),
                new LifecyclePdf.Line(Texts.t("Signed by"), user.fullName() + " · "
                        + Texts.t("registration no. %s", user.getRegistrationNo())),
                new LifecyclePdf.Line(Texts.t("Name typed"), typedName),
                new LifecyclePdf.Line(Texts.t("Date and time"), now.atZoneSameInstant(zone)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")) + " (" + zone.getId() + ")"),
                new LifecyclePdf.Line(Texts.t("Confirmed with"), Texts.t("a one-time code sent by %s", channels(channels))),
                new LifecyclePdf.Line(Texts.t("Address"), ip),
                new LifecyclePdf.Line(Texts.t("Device"), agent == null ? null : agent.length() > 200 ? agent.substring(0, 200) : agent),
                new LifecyclePdf.Line(Texts.t("Sent by"), l.sentByName()),
                new LifecyclePdf.Line(Texts.t("Fingerprint (SHA-256) of the document read"), l.sha256())),
                Texts.t("HR Pay added this page when the person confirmed with the one-time code sent to them. The fingerprint "
                        + "identifies the exact document they read: any change to its pages gives another fingerprint. "
                        + "The signature is also recorded in HR Pay's audit log.")));
        byte[] signed = LifecyclePdf.append(original, page);
        String sha = Documents.sha256(signed);
        long doc = documents.add(l.userId(), documentType(l), l.number() + "-signed.pdf", signed, null, null, l.title(), "SIGNED").id();
        jdbc.sql("""
                update employee_letter set status = 'SIGNED', signed_pdf = ?, signed_sha256 = ?, signed_at = ?, signed_name = ?,
                    signed_ip = ?, signed_agent = ?, document_id = ? where id = ?""")
                .params(signed, sha, now, typedName, ip, agent == null ? null : agent.length() > 300 ? agent.substring(0, 300) : agent,
                        doc, l.id()).update();
        if (l.contractId() != null) {
            LocalDate day = now.atZoneSameInstant(zone).toLocalDate();
            jdbc.sql("""
                    insert into contract_terms (contract_id, signed_on, updated_by) values (?, ?, ?)
                    on conflict (contract_id) do update set signed_on = excluded.signed_on, updated_at = now()""")
                    .params(l.contractId(), day, "signature").update();
        }
        audit.record("letter.signed", "user", l.userId(), Map.of("letter", l.id(), "number", l.number(), "sha256", l.sha256(),
                "signedSha256", sha));
        users.findByEmailIgnoreCase(l.sentBy()).ifPresent(s -> notifications.send(s.getId(), "letter.signed",
                () -> Texts.t("Document signed"), () -> Texts.t("%s signed %s.", user.fullName(), l.title()),
                "/users/" + l.userId() + "/lifecycle"));
        return find(l.id());
    }

    @Transactional
    public Letter decline(long id, AppUser user, String reason) {
        Letter l = toSign(id, user);
        String why = reason == null || reason.isBlank() ? null : reason.trim();
        jdbc.sql("update employee_letter set status = 'DECLINED', declined_reason = ? where id = ?").params(why, id).update();
        audit.record("letter.declined", "user", l.userId(), Map.of("letter", id));
        users.findByEmailIgnoreCase(l.sentBy()).ifPresent(s -> notifications.send(s.getId(), "letter.declined",
                () -> Texts.t("Document refused"),
                () -> Texts.t("%s did not sign %s: %s", user.fullName(), l.title(), why == null ? "—" : why),
                "/users/" + l.userId() + "/lifecycle"));
        return find(id);
    }

    private long documentType(Letter l) {
        Long t = l.templateId() == null ? null
                : jdbc.sql("select document_type_id from letter_template where id = ?").param(l.templateId()).query(Long.class)
                        .optional().orElse(null);
        return t != null ? t : documents.typeId(l.kind().equals("CONTRACT") ? "CONTRACT" : "LETTER");
    }

    private static String channels(String list) {
        List<String> names = new ArrayList<>();
        for (String c : list.split(",")) {
            switch (c.trim()) {
                case "EMAIL" -> names.add(Texts.t("email"));
                case "WHATSAPP" -> names.add("WhatsApp");
                case "SMS" -> names.add("SMS");
                default -> { }
            }
        }
        return names.isEmpty() ? Texts.t("email") : String.join(" + ", names);
    }

    static boolean sameName(String typed, String expected) {
        return typed != null && simple(typed).equals(simple(expected));
    }

    /** The name's words without accents or case, in any order (Nom Postnom Prénom or Prénom Nom both match). */
    private static String simple(String s) {
        return java.util.Arrays.stream(Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                        .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim().split(" "))
                .sorted().collect(java.util.stream.Collectors.joining(" "));
    }
}
