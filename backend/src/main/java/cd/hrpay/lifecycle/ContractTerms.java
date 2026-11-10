package cd.hrpay.lifecycle;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.holiday.HolidayService;
import cd.hrpay.i18n.Texts;
import cd.hrpay.payment.Company;
import cd.hrpay.payroll.EmployeeFile;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a written contract states beyond its kind, dates and grade (DRC Code du travail art. 40-47 and 212), and the
 * checks made before a contract document goes out: the length and number of fixed-term contracts (art. 41, arrêté
 * 063/2011), the trial clause (art. 43), the draft handed over two working days before signing (art. 46) and the
 * ONEM visa within fifteen days (art. 47, arrêté 062/2011).
 */
@Service
public class ContractTerms {

    /** Fixed-term reasons; the last seven are the cases of arrêté 063/2011 art. 2 that allow more than one renewal. */
    public static final List<String> REASONS = List.of("TEMPORARY_NEED", "REPLACEMENT", "SPECIFIC_WORK", "SEASONAL", "WORKS",
            "PROJECT", "HUMANITARIAN", "PART_TIME", "AWAITING_HOLDER", "ACTIVITY_PEAK");
    static final Set<String> RENEWABLE = Set.of("SEASONAL", "WORKS", "PROJECT", "HUMANITARIAN", "PART_TIME", "AWAITING_HOLDER",
            "ACTIVITY_PEAK");

    public record Terms(long contractId, int trialMonths, boolean unskilledLabourer, String fixedTermReason,
                        String fixedTermDetail, boolean permanentJob, String workPlace, String supervisor, String signedPlace,
                        LocalDate signedOn, LocalDate draftGivenOn, LocalDate medicalFitOn, String medicalDoctor,
                        String onemVisaNo, LocalDate onemVisaOn, String updatedBy, OffsetDateTime updatedAt,
                        Long workSiteId, Long signedPlaceId) {}

    /** What the form shows: the terms saved, and what HR Pay proposes where nothing was typed. */
    public record View(Terms terms, String defaultWorkPlace, String defaultSupervisor, String defaultSignedPlace,
                       List<Issue> issues, String signedCountry) {}

    /** A point to fix (BLOCK: the document cannot go out) or to look at (WARN), with the article it comes from. */
    public record Issue(String level, String code, String message) {}

    private final JdbcClient jdbc;
    private final EmployeeFile files;
    private final UserRepository users;
    private final Company company;
    private final HolidayService holidays;
    private final AuditService audit;
    private final cd.hrpay.reference.References refs;

    public ContractTerms(JdbcClient jdbc, EmployeeFile files, UserRepository users, Company company, HolidayService holidays,
                         AuditService audit, cd.hrpay.reference.References refs) {
        this.refs = refs;
        this.jdbc = jdbc;
        this.files = files;
        this.users = users;
        this.company = company;
        this.holidays = holidays;
        this.audit = audit;
    }

    public EmployeeFile.Contract contract(long userId, long contractId) {
        return files.contracts(userId).stream().filter(c -> c.id() == contractId).findFirst()
                .orElseThrow(() -> new NotFoundException("Contract not found."));
    }

    public Terms terms(long contractId) {
        return jdbc.sql("select * from contract_terms where contract_id = ?").param(contractId).query(Terms.class).optional()
                .orElse(new Terms(contractId, 0, false, null, null, false, null, null, null, null, null, null, null, null, null,
                        null, null, null, null));
    }

    public View view(long userId, long contractId) {
        EmployeeFile.Contract c = contract(userId, contractId);
        Terms t = terms(contractId);
        Company.Representative r = company.representative();
        String signedCountry = t.signedPlaceId() == null ? r.signingCountry()
                : jdbc.sql("select country_code from place where id = ?").param(t.signedPlaceId()).query(String.class)
                        .optional().orElse(r.signingCountry());
        return new View(t, defaultWorkPlace(userId), defaultSupervisor(userId).orElse(null), r.signingPlace(),
                Texts.in(company.language(), () -> issues(userId, c, t)), signedCountry);
    }

    @Transactional
    public View save(long userId, long contractId, Terms in) {
        EmployeeFile.Contract c = contract(userId, contractId);
        if (in.unskilledLabourer() && in.trialMonths() > 1) {
            throw new BusinessRuleException("The trial of an unskilled labourer is one month at most (Labour Code art. 43).");
        }
        if (in.trialMonths() < 0 || in.trialMonths() > 6) {
            throw new BusinessRuleException("The trial is six months at most (Labour Code art. 43).");
        }
        if (in.fixedTermReason() != null && !REASONS.contains(in.fixedTermReason())) {
            throw new BusinessRuleException("Choose the reason from the list.");
        }
        if (in.onemVisaOn() != null && in.onemVisaOn().isBefore(c.startDate().minusYears(1))) {
            throw new BusinessRuleException("Check the date of the ONEM visa.");
        }
        // The site is one of the company's sites, the town one of the lists; their names are kept for printing. With
        // none picked, what was typed before the lists stays until one is.
        Terms before = terms(contractId);
        String workPlace = before.workSiteId() == null ? before.workPlace() : null;
        if (in.workSiteId() != null) {
            workPlace = jdbc.sql("select name from site where id = ? and (active or id is not distinct from ?)")
                    .params(in.workSiteId(), before.workSiteId()).query(String.class)
                    .optional().orElseThrow(() -> new BusinessRuleException("Choose the site from the company's sites."));
        }
        String signedPlace = before.signedPlaceId() == null ? before.signedPlace() : null;
        if (in.signedPlaceId() != null) {
            signedPlace = refs.requireCity(in.signedPlaceId()).name();
        }
        jdbc.sql("""
                insert into contract_terms (contract_id, trial_months, unskilled_labourer, fixed_term_reason, fixed_term_detail,
                    permanent_job, work_place, supervisor, signed_place, signed_on, draft_given_on, medical_fit_on, medical_doctor,
                    onem_visa_no, onem_visa_on, updated_by, work_site_id, signed_place_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (contract_id) do update set trial_months = excluded.trial_months,
                    unskilled_labourer = excluded.unskilled_labourer, fixed_term_reason = excluded.fixed_term_reason,
                    fixed_term_detail = excluded.fixed_term_detail, permanent_job = excluded.permanent_job,
                    work_place = excluded.work_place, supervisor = excluded.supervisor, signed_place = excluded.signed_place,
                    signed_on = excluded.signed_on, draft_given_on = excluded.draft_given_on,
                    medical_fit_on = excluded.medical_fit_on, medical_doctor = excluded.medical_doctor,
                    onem_visa_no = excluded.onem_visa_no, onem_visa_on = excluded.onem_visa_on,
                    updated_by = excluded.updated_by, updated_at = now(), work_site_id = excluded.work_site_id,
                    signed_place_id = excluded.signed_place_id""")
                .params(contractId, in.trialMonths(), in.unskilledLabourer(),
                        "FIXED_TERM".equals(c.contractType()) ? in.fixedTermReason() : null,
                        "FIXED_TERM".equals(c.contractType()) ? blank(in.fixedTermDetail()) : null, in.permanentJob(),
                        workPlace, blank(in.supervisor()), signedPlace, in.signedOn(), in.draftGivenOn(),
                        in.medicalFitOn(), blank(in.medicalDoctor()), blank(in.onemVisaNo()), in.onemVisaOn(), CurrentUser.email(),
                        in.workSiteId(), in.signedPlaceId())
                .update();
        audit.record("contract.terms_saved", "user", userId, Map.of("contract", contractId, "trialMonths", in.trialMonths()));
        return view(userId, contractId);
    }

    // ---- defaults --------------------------------------------------------------------------------------------------

    String defaultWorkPlace(long userId) {
        AppUser u = users.findById(userId).orElseThrow();
        return u.getSite() == null ? null : u.getSite().getName();
    }

    /** The head of the person's unit, by the title of the post (the head of the unit above when they head it). */
    Optional<String> defaultSupervisor(long userId) {
        return jdbc.sql("""
                with recursive up as (
                    select u.id, u.parent_id, 0 as depth from org_assignment a join org_unit u on u.id = a.unit_id
                    where a.user_id = :user and a.valid_to is null
                    union all
                    select p.id, p.parent_id, up.depth + 1 from up join org_unit p on p.id = up.parent_id where up.depth < 20)
                select coalesce(h.title, l.head_title || ', ' || un.name) from up
                join org_head h on h.unit_id = up.id and h.role = 'HEAD' and h.valid_to is null and h.user_id <> :user
                join org_unit un on un.id = up.id join org_level l on l.id = un.level_id
                order by up.depth limit 1""").param("user", userId).query(String.class).optional();
    }

    /** The person's unit and the units above it, from the top: "Direction informatique / Service support". */
    Optional<String> unitPath(long userId) {
        List<String> names = jdbc.sql("""
                with recursive up as (
                    select u.id, u.parent_id, u.name, 0 as depth from org_assignment a join org_unit u on u.id = a.unit_id
                    where a.user_id = ? and a.valid_to is null
                    union all
                    select p.id, p.parent_id, p.name, up.depth + 1 from up join org_unit p on p.id = up.parent_id
                    where up.depth < 20)
                select name from up where parent_id is not null order by depth desc""").param(userId).query(String.class).list();
        return names.isEmpty() ? Optional.empty() : Optional.of(String.join(" / ", names));
    }

    // ---- checks --------------------------------------------------------------------------------------------------

    /** The legal points of this contract, in the company's language (call inside Texts.in). */
    public List<Issue> issues(long userId, EmployeeFile.Contract c, Terms t) {
        List<Issue> out = new ArrayList<>();
        if ("FIXED_TERM".equals(c.contractType()) && c.endDate() != null) {
            long months = ChronoUnit.MONTHS.between(c.startDate(), c.endDate().plusDays(1));
            boolean overTwoYears = c.endDate().isAfter(c.startDate().plusYears(2).minusDays(1));
            if (overTwoYears) {
                out.add(new Issue("BLOCK", "CDD_TOO_LONG", Texts.t(
                        "A fixed-term contract lasts two years at most (Labour Code art. 41); otherwise it is a permanent contract.")));
            } else if (months > 12) {
                out.add(new Issue("WARN", "CDD_FAMILY", Texts.t("A fixed-term contract lasts one year at most for a married worker "
                        + "living apart from the family, or a widowed, separated or divorced worker living apart from children "
                        + "in their care (Labour Code art. 41).")));
            }
            List<EmployeeFile.Contract> fixed = files.contracts(userId).stream()
                    .filter(x -> "FIXED_TERM".equals(x.contractType()) && !x.startDate().isAfter(c.startDate())).toList();
            if (fixed.size() > 2 && !RENEWABLE.contains(t.fixedTermReason())) {
                out.add(new Issue("BLOCK", "CDD_COUNT", Texts.t("This is fixed-term contract no. %d with this person: the "
                        + "Labour Code allows two, renewed once (art. 41), except for the work listed in arrêté 063/2011 "
                        + "(seasonal, works, project, humanitarian, part-time, awaiting the holder, temporary increase). "
                        + "Choose that reason or make it a permanent contract.", fixed.size())));
            } else if (fixed.size() > 1) {
                out.add(new Issue("WARN", "CDD_RENEWAL", Texts.t("This renews a fixed-term contract: tell the provincial ONEM "
                        + "office and the labour inspectorate within 15 days when it is renewed more than once (arrêté 063/2011 art. 3).")));
            }
            if (t.fixedTermReason() == null) {
                out.add(new Issue("BLOCK", "CDD_REASON", Texts.t("Say why the contract is fixed-term: a written contract that does "
                        + "not say so is a permanent contract (Labour Code art. 40 and 45).")));
            } else if ("REPLACEMENT".equals(t.fixedTermReason()) && blank(t.fixedTermDetail()) == null) {
                out.add(new Issue("BLOCK", "CDD_REPLACEMENT", Texts.t("A replacement contract names the person replaced and why "
                        + "(Labour Code art. 45).")));
            }
            if (t.permanentJob()) {
                out.add(new Issue("WARN", "CDD_PERMANENT_JOB", Texts.t("The job is permanent: the contract must be a permanent "
                        + "contract, or it will be held to be one (Labour Code art. 42).")));
            }
        }
        if (t.medicalFitOn() == null) {
            out.add(new Issue("WARN", "MEDICAL", Texts.t("Record the medical fitness for work: the contract must state it "
                    + "(Labour Code art. 212 point 15).")));
        }
        AppUser u = users.findById(userId).orElseThrow();
        EmployeeFile.Profile p = files.profile(userId);
        if (p.cnssNumber() == null || p.cnssNumber().isBlank()) {
            out.add(new Issue("WARN", "CNSS", Texts.t("No CNSS number on the file: the contract will say the affiliation is in "
                    + "progress (art. 212 point 4).")));
        }
        if (p.gender() == null) {
            out.add(new Issue("BLOCK", "GENDER", Texts.t("Give the person's sex on the employee file (art. 212 point 3).")));
        }
        if (p.birthDate() == null) {
            out.add(new Issue("BLOCK", "BIRTH", Texts.t("Give the date of birth on the employee file (art. 212 point 5).")));
        }
        if (files.birthPlace(p) == null || files.nationality(p) == null) {
            out.add(new Issue("BLOCK", "BIRTH_PLACE", Texts.t("Give the place of birth and the nationality on the employee file "
                    + "(art. 212 point 6).")));
        }
        Company.Details employer = company.get();
        if (employer.cnssNumber() == null) {
            out.add(new Issue("BLOCK", "EMPLOYER_CNSS", Texts.t("Give the employer's CNSS number on the Company page "
                    + "(art. 212 point 2).")));
        }
        if (company.representative().name() == null) {
            out.add(new Issue("BLOCK", "REPRESENTATIVE", Texts.t("Give who signs for the employer on the Company page.")));
        }
        if (u.getSite() == null && blank(t.workPlace()) == null) {
            out.add(new Issue("BLOCK", "PLACE", Texts.t("Give the place of work (art. 212 point 10).")));
        }
        if (blank(t.signedPlace()) == null && company.representative().signingPlace() == null) {
            out.add(new Issue("BLOCK", "SIGNED_PLACE", Texts.t("Give the town where the contract is signed (art. 212 point 14).")));
        }
        if (t.onemVisaNo() == null && t.signedOn() != null) {
            LocalDate due = t.signedOn().plusDays(15);
            out.add(new Issue("WARN", "ONEM_VISA", Texts.t("Submit the signed contract to the ONEM visa by %s (Labour Code art. 47, "
                    + "arrêté 062/2011 art. 8).", Texts.day(due))));
        }
        return out;
    }

    /** The day the person may sign: two working days after the draft reached them (Labour Code art. 46). */
    public LocalDate earliestSigning(LocalDate draftGiven) {
        LocalDate d = draftGiven;
        Set<LocalDate> off = holidays.observedDates(company.country(), draftGiven, draftGiven.plusDays(30));
        int counted = 0;
        while (counted < 2) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SUNDAY && !off.contains(d)) {
                counted++;
            }
        }
        return d;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
