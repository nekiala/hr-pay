package cd.hrpay.payroll;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.salary.SalaryService;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** An employee's contracts, personal and payment details, and dependants. */
@Service
public class EmployeeFile {

    public record Contract(long id, long userId, String contractType, LocalDate startDate, LocalDate endDate,
                           long gradeId, String gradeCode, String gradeName, long categoryId, Long occupationId,
                           String occupationName, boolean expatriate, String note, String createdBy,
                           OffsetDateTime createdAt) {}

    public record Profile(LocalDate birthDate, String gender, String nationality, String maritalStatus,
                          String cnssNumber, String taxNumber, String paymentMethod, String bankName,
                          String bankAccount, String mobileMoneyOperator, String mobileMoneyNumber,
                          String commune, LocalDate nursingBreakUntil,
                          // From the reference lists (the texts above then hold the names of what was picked).
                          String nationalityCode, String birthCountryCode, Long birthPlaceId, String birthPlaceText,
                          Long addressPlaceId, String addressQuarter, String addressStreet, Long bankId,
                          Long mobileMoneyOperatorId, String idDocumentType, String idDocumentNumber,
                          LocalDate idDocumentIssuedOn, String idDocumentIssuedAt) {

        /** The details before the reference lists: the new fields empty. */
        public Profile(LocalDate birthDate, String gender, String nationality, String maritalStatus, String cnssNumber,
                       String taxNumber, String paymentMethod, String bankName, String bankAccount,
                       String mobileMoneyOperator, String mobileMoneyNumber, String commune, LocalDate nursingBreakUntil) {
            this(birthDate, gender, nationality, maritalStatus, cnssNumber, taxNumber, paymentMethod, bankName, bankAccount,
                    mobileMoneyOperator, mobileMoneyNumber, commune, nursingBreakUntil, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);
        }
    }

    public record Dependant(long id, String fullName, String relationship, LocalDate birthDate, boolean inCharge,
                            boolean student, boolean disabled, String note) {}

    /**
     * Whether a dependant counts for family allowances and the tax reduction on a day, until when, and why not. The
     * worker's family is the spouse and the unmarried children until majority, until 25 if they study full time, and
     * without age limit if a disability stops them earning a living (DRC Code du travail art. 7 point 11). The ages
     * are the company's rates CHILD_AGE_LIMIT and CHILD_STUDENT_AGE_LIMIT; with no age limit set, a child in charge
     * always counts.
     */
    public record Eligibility(boolean counts, LocalDate until, String reason) {}

    /** A dependant as the employee file shows it, with whether they count today. */
    public record DependantRow(long id, String fullName, String relationship, LocalDate birthDate, boolean inCharge,
                               boolean student, boolean disabled, String note, Integer age, boolean counts,
                               LocalDate countsUntil, String reason) {}

    /** The children and all the dependants that count for a payroll month (on its first day). */
    public record Counted(int children, int dependants) {}

    private static final String CONTRACT_SELECT = """
            select c.id, c.user_id, c.contract_type, c.start_date, c.end_date, c.grade_id, g.code as grade_code,
                   g.name as grade_name, g.category_id, c.occupation_id, o.name as occupation_name, c.expatriate,
                   c.note, c.created_by, c.created_at
            from employee_contract c join grade g on g.id = c.grade_id left join occupation o on o.id = c.occupation_id
            """;

    private final JdbcClient jdbc;
    private final cd.hrpay.security.DataCrypto crypto;
    private final UserService users;
    private final MasterData masterData;
    private final SalaryService salaries;
    private final AuditService audit;
    private final StatutoryRates statutory;
    private final cd.hrpay.reference.References refs;

    public EmployeeFile(JdbcClient jdbc, UserService users, MasterData masterData, SalaryService salaries,
                        AuditService audit, cd.hrpay.security.DataCrypto crypto, StatutoryRates statutory,
                        cd.hrpay.reference.References refs) {
        this.jdbc = jdbc;
        this.refs = refs;
        this.statutory = statutory;
        this.crypto = crypto;
        this.users = users;
        this.masterData = masterData;
        this.salaries = salaries;
        this.audit = audit;
    }

    public List<Contract> contracts(long userId) {
        return jdbc.sql(CONTRACT_SELECT + " where c.user_id = ? order by c.start_date desc").param(userId)
                .query(Contract.class).list();
    }

    public Optional<Contract> contractOn(long userId, LocalDate date) {
        return jdbc.sql(CONTRACT_SELECT + """
                 where c.user_id = ? and c.start_date <= ? and (c.end_date is null or c.end_date >= ?)
                 order by c.start_date desc limit 1""").params(userId, date, date).query(Contract.class).optional();
    }

    /**
     * Starts a contract. The basic salary from its start is the negotiated amount if given, otherwise the grade's
     * base salary; an existing salary entry on that date is kept.
     */
    @Transactional
    public Contract startContract(long userId, String type, LocalDate start, LocalDate end, long gradeId,
                                  Long occupationId, boolean expatriate, BigDecimal negotiatedBasic, String note) {
        return startContract(userId, type, start, end, gradeId, occupationId, expatriate, negotiatedBasic, note, null);
    }

    /**
     * Starts a contract; the first one also sets the hire date: its start, or the earlier date given ("in the company
     * since", for someone hired before HR Pay's records). Nobody has a hire date without a contract.
     */
    @Transactional
    public Contract startContract(long userId, String type, LocalDate start, LocalDate end, long gradeId,
                                  Long occupationId, boolean expatriate, BigDecimal negotiatedBasic, String note,
                                  LocalDate since) {
        AppUser user = users.find(userId);
        if (user.isNotEmployee()) {
            throw new BusinessRuleException("This account is marked as not an employee: an administrator must change that first.");
        }
        boolean first = contracts(userId).isEmpty();
        if (since != null && since.isAfter(start)) {
            throw new BusinessRuleException("The date in the company cannot be after the contract start.");
        }
        if (since != null && !first && !since.equals(user.getHireDate())) {
            throw new BusinessRuleException("The date in the company is given with the first contract only.");
        }
        if (java.util.Objects.equals(userId, CurrentUser.id())) {
            throw new org.springframework.security.access.AccessDeniedException("You cannot change your own contract.");
        }
        MasterData.Grade grade = masterData.grade(gradeId);
        if (!grade.active()) {
            throw new BusinessRuleException("This grade is no longer in use.");
        }
        if (type.equals("FIXED_TERM") && end == null) {
            throw new BusinessRuleException("A fixed-term contract needs an end date.");
        }
        if (end != null && end.isBefore(start)) {
            throw new BusinessRuleException("The contract must end after it starts.");
        }
        if (contracts(userId).stream().anyMatch(c -> c.startDate().equals(start))) {
            throw new ConflictException("A contract already starts on " + start + ".");
        }
        // The previous open contract ends the day before.
        jdbc.sql("update employee_contract set end_date = ? where user_id = ? and start_date < ? and (end_date is null or end_date >= ?)")
                .params(start.minusDays(1), userId, start, start).update();
        long id = jdbc.sql("""
                insert into employee_contract (user_id, contract_type, start_date, end_date, grade_id, occupation_id,
                                               expatriate, note, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(userId, type, start, end, gradeId, occupationId, expatriate, note, CurrentUser.email())
                .query(Long.class).single();
        user.setGradeCategoryId(grade.categoryId());
        if (first) {
            // A hire date typed before contracts were required stays when it is not after the start.
            LocalDate hired = since != null ? since
                    : user.getHireDate() != null && !user.getHireDate().isAfter(start) ? user.getHireDate() : start;
            user.setHireDate(hired);
        } else if (user.getHireDate() == null || user.getHireDate().isAfter(start)) {
            user.setHireDate(start);
        }
        if (salaries.history(userId).stream().noneMatch(s -> s.effectiveFrom().equals(start))) {
            BigDecimal basic = negotiatedBasic != null ? negotiatedBasic : grade.baseSalary();
            salaries.add(userId, start, basic, grade.currency(),
                    negotiatedBasic != null ? "Negotiated basic, contract start" : "Grade " + grade.code() + " basic");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", type);
        details.put("start", start.toString());
        details.put("grade", grade.code());
        details.put("expatriate", expatriate);
        audit.record("contract.started", "user", userId, details);
        return contracts(userId).stream().filter(c -> c.id() == id).findFirst().orElseThrow();
    }

    /** Refuses a change from a day whose payroll month is approved or closed. */
    void requireOpen(LocalDate from) {
        PayrollRuns.requireOpen(jdbc, java.time.YearMonth.from(from));
    }

    /**
     * Fixes the grade of the latest contract, typed wrongly: allowed while no closed payslip of the person covers the
     * contract. The basic recorded at the contract's start follows when it was the old grade's.
     */
    @Transactional
    public Contract correctGrade(long userId, long contractId, long gradeId) {
        AppUser user = users.find(userId);
        if (java.util.Objects.equals(userId, CurrentUser.id())) {
            throw new org.springframework.security.access.AccessDeniedException("You cannot change your own contract.");
        }
        List<Contract> all = contracts(userId);
        Contract c = all.stream().filter(x -> x.id() == contractId).findFirst()
                .orElseThrow(() -> new NotFoundException("Contract not found."));
        if (all.get(0).id() != contractId) {
            throw new BusinessRuleException("Only the latest contract can be corrected. To change the grade from a date, use Change grade.");
        }
        MasterData.Grade old = masterData.grade(c.gradeId());
        MasterData.Grade grade = masterData.grade(gradeId);
        if (!grade.active()) {
            throw new BusinessRuleException("This grade is no longer in use.");
        }
        if (gradeId == c.gradeId()) {
            return c;
        }
        long closed = jdbc.sql("""
                select count(*) from payslip p join payroll_run r on r.id = p.run_id
                where p.user_id = ? and r.status <> 'CALCULATED' and r.month >= ?""")
                .params(userId, c.startDate().withDayOfMonth(1)).query(Long.class).single();
        if (closed > 0) {
            throw new BusinessRuleException("A payroll already approved or closed uses this contract: use Change grade from a later date instead.");
        }
        jdbc.sql("update employee_contract set grade_id = ? where id = ?").params(gradeId, contractId).update();
        user.setGradeCategoryId(grade.categoryId());
        salaries.history(userId).stream()
                .filter(s -> s.effectiveFrom().equals(c.startDate()) && s.grossMonthly().compareTo(old.baseSalary()) == 0
                        && s.currency().equals(old.currency()))
                .findFirst().ifPresent(s -> jdbc.sql("update employee_salary set gross_monthly = ?, currency = ?, note = ? where id = ?")
                        .params(grade.baseSalary(), grade.currency(), "Grade " + grade.code() + " basic (corrected)", s.id()).update());
        audit.record("contract.grade_corrected", "user", userId, Map.of("contractId", contractId, "from", old.code(),
                "to", grade.code()));
        return contracts(userId).stream().filter(x -> x.id() == contractId).findFirst().orElseThrow();
    }

    public Profile profile(long userId) {
        return jdbc.sql("""
                select birth_date, gender, nationality, marital_status, cnss_number, tax_number, payment_method,
                       bank_name, bank_account, mobile_money_operator, mobile_money_number, commune,
                       nursing_break_until, nationality_code, birth_country_code, birth_place_id, birth_place_text,
                       address_place_id, address_quarter, address_street, bank_id, mobile_money_operator_id,
                       id_document_type, id_document_number, id_document_issued_on, id_document_issued_at
                from employee_profile where user_id = ?""").param(userId).query(Profile.class).optional()
                .map(p -> new Profile(p.birthDate(), p.gender(), p.nationality(), p.maritalStatus(),
                        crypto.decrypt(p.cnssNumber()), crypto.decrypt(p.taxNumber()), p.paymentMethod(), p.bankName(),
                        crypto.decrypt(p.bankAccount()), p.mobileMoneyOperator(), crypto.decrypt(p.mobileMoneyNumber()),
                        p.commune(), p.nursingBreakUntil(), p.nationalityCode(), p.birthCountryCode(), p.birthPlaceId(),
                        p.birthPlaceText(), p.addressPlaceId(), p.addressQuarter(), p.addressStreet(), p.bankId(),
                        p.mobileMoneyOperatorId(), p.idDocumentType(), crypto.decrypt(p.idDocumentNumber()),
                        p.idDocumentIssuedOn(), p.idDocumentIssuedAt()))
                .orElse(new Profile(null, null, null, "SINGLE", null, null, "BANK", null, null, null, null, null, null));
    }

    /** Where the person was born, in words: "Ngaliema, Kinshasa" or "Lyon (France)". */
    public String birthPlace(Profile p) {
        if (p.birthPlaceId() != null) {
            return refs.label(p.birthPlaceId());
        }
        if (p.birthPlaceText() == null) {
            return null;
        }
        return refs.country(p.birthCountryCode()).map(c -> p.birthPlaceText() + " (" + c.name() + ")").orElse(p.birthPlaceText());
    }

    /** The current address in words: avenue and number, quartier, commune and town. */
    public String address(Profile p) {
        List<String> parts = new java.util.ArrayList<>();
        if (p.addressStreet() != null) {
            parts.add(p.addressStreet());
        }
        if (p.addressQuarter() != null) {
            parts.add(p.addressQuarter());
        }
        String place = p.addressPlaceId() != null ? refs.label(p.addressPlaceId()) : p.commune();
        if (place != null) {
            parts.add(place);
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /** The nationality in words, in the language of the moment. */
    public String nationality(Profile p) {
        return p.nationalityCode() != null ? refs.country(p.nationalityCode()).map(cd.hrpay.reference.References.Country::nationality)
                .orElse(p.nationality()) : p.nationality();
    }

    @Transactional
    public Profile saveProfile(long userId, Profile p) {
        users.find(userId);
        // What is picked from the lists gives the names kept with it; a value typed before stays until HR picks one.
        String nationality = p.nationalityCode() != null ? refs.requireCountry(p.nationalityCode()).nationality() : trim(p.nationality());
        String birthCountry = p.birthCountryCode() == null ? null : refs.requireCountry(p.birthCountryCode()).code();
        Long birthPlaceId = p.birthPlaceId();
        String birthPlaceText = trim(p.birthPlaceText());
        if (birthPlaceId != null) {
            var place = refs.place(birthPlaceId);
            if (birthCountry != null && !birthCountry.equals(place.countryCode())) {
                throw new BusinessRuleException("The place of birth is not in the country of birth.");
            }
            birthCountry = place.countryCode();
            birthPlaceText = null;
        }
        String commune = trim(p.commune());
        if (p.addressPlaceId() != null) {
            commune = refs.place(p.addressPlaceId()).name();
        }
        String bankName = trim(p.bankName());
        if (p.bankId() != null) {
            var bank = refs.institution(p.bankId());
            if (!bank.kind().equals("BANK")) {
                throw new BusinessRuleException("Choose a bank from the list.");
            }
            bankName = bank.name();
        }
        String operator = trim(p.mobileMoneyOperator());
        if (p.mobileMoneyOperatorId() != null) {
            var op = refs.institution(p.mobileMoneyOperatorId());
            if (!op.kind().equals("MOBILE_MONEY")) {
                throw new BusinessRuleException("Choose a mobile money operator from the list.");
            }
            operator = op.name();
        }
        if (p.paymentMethod().equals("BANK") && (blank(bankName) || blank(p.bankAccount()))) {
            throw new BusinessRuleException("Choose the bank and enter the account number, or choose another payment method.");
        }
        if (p.paymentMethod().equals("MOBILE_MONEY") && (blank(operator) || blank(p.mobileMoneyNumber()))) {
            throw new BusinessRuleException("Choose the mobile money operator and enter the number.");
        }
        if (p.idDocumentType() != null && !java.util.Set.of("NATIONAL_ID", "ELECTOR_CARD", "PASSPORT", "RESIDENCE_PERMIT",
                "DRIVING_LICENCE", "OTHER").contains(p.idDocumentType())) {
            throw new BusinessRuleException("Choose the kind of identity document.");
        }
        jdbc.sql("""
                insert into employee_profile (user_id, birth_date, gender, nationality, marital_status, cnss_number,
                    tax_number, payment_method, bank_name, bank_account, mobile_money_operator, mobile_money_number,
                    commune, nursing_break_until, nationality_code, birth_country_code, birth_place_id, birth_place_text,
                    address_place_id, address_quarter, address_street, bank_id, mobile_money_operator_id,
                    id_document_type, id_document_number, id_document_issued_on, id_document_issued_at,
                    updated_by, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (user_id) do update set birth_date = excluded.birth_date, gender = excluded.gender,
                    nationality = excluded.nationality, marital_status = excluded.marital_status,
                    cnss_number = excluded.cnss_number, tax_number = excluded.tax_number,
                    payment_method = excluded.payment_method, bank_name = excluded.bank_name,
                    bank_account = excluded.bank_account, mobile_money_operator = excluded.mobile_money_operator,
                    mobile_money_number = excluded.mobile_money_number,
                    commune = excluded.commune, nursing_break_until = excluded.nursing_break_until,
                    nationality_code = excluded.nationality_code, birth_country_code = excluded.birth_country_code,
                    birth_place_id = excluded.birth_place_id, birth_place_text = excluded.birth_place_text,
                    address_place_id = excluded.address_place_id, address_quarter = excluded.address_quarter,
                    address_street = excluded.address_street, bank_id = excluded.bank_id,
                    mobile_money_operator_id = excluded.mobile_money_operator_id,
                    id_document_type = excluded.id_document_type, id_document_number = excluded.id_document_number,
                    id_document_issued_on = excluded.id_document_issued_on,
                    id_document_issued_at = excluded.id_document_issued_at,
                    updated_by = excluded.updated_by, updated_at = now()""")
                .params(userId, p.birthDate(), p.gender(), nationality, p.maritalStatus(),
                        crypto.encrypt(trim(p.cnssNumber())), crypto.encrypt(trim(p.taxNumber())), p.paymentMethod(),
                        bankName, crypto.encrypt(trim(p.bankAccount())), operator,
                        crypto.encrypt(trim(p.mobileMoneyNumber())), commune, p.nursingBreakUntil(),
                        p.nationalityCode() == null ? null : p.nationalityCode().toUpperCase(), birthCountry, birthPlaceId,
                        birthPlaceText, p.addressPlaceId(), trim(p.addressQuarter()), trim(p.addressStreet()), p.bankId(),
                        p.mobileMoneyOperatorId(), p.idDocumentType(), crypto.encrypt(trim(p.idDocumentNumber())),
                        p.idDocumentIssuedOn(), trim(p.idDocumentIssuedAt()), CurrentUser.email())
                .update();
        audit.record("profile.saved", "user", userId, Map.of("maritalStatus", p.maritalStatus(),
                "paymentMethod", p.paymentMethod()));
        return profile(userId);
    }

    public List<Dependant> dependants(long userId) {
        return jdbc.sql("""
                select id, full_name, relationship, birth_date, in_charge, student, disabled, note from dependant
                where user_id = ? order by relationship desc, birth_date""")
                .param(userId).query(Dependant.class).list();
    }

    /** The dependants with whether each counts on a day. */
    public List<DependantRow> dependantRows(long userId, LocalDate on) {
        Integer[] limits = childLimits(on);
        return dependants(userId).stream().map(d -> {
            Eligibility e = eligibility(d, on, limits[0], limits[1]);
            Integer age = d.birthDate() == null ? null : java.time.Period.between(d.birthDate(), on).getYears();
            return new DependantRow(d.id(), d.fullName(), d.relationship(), d.birthDate(), d.inCharge(), d.student(),
                    d.disabled(), d.note(), age, e.counts(), e.until(), e.reason());
        }).toList();
    }

    /** The children and dependants that count for family allowances and the tax reduction in a month. */
    public Counted counted(long userId, java.time.YearMonth month) {
        LocalDate on = month.atDay(1);
        Integer[] limits = childLimits(on);
        int children = 0;
        int all = 0;
        for (Dependant d : dependants(userId)) {
            if (eligibility(d, on, limits[0], limits[1]).counts()) {
                all++;
                if (d.relationship().equals("CHILD")) {
                    children++;
                }
            }
        }
        return new Counted(children, all);
    }

    private Integer[] childLimits(LocalDate on) {
        BigDecimal age = statutory.rateOrNull("CHILD_AGE_LIMIT", on);
        BigDecimal student = statutory.rateOrNull("CHILD_STUDENT_AGE_LIMIT", on);
        return new Integer[] {age == null || age.signum() <= 0 ? null : age.intValue(),
                student == null || student.signum() <= 0 ? null : student.intValue()};
    }

    /** Whether a dependant counts on a day, given the age limits (null: none). */
    static Eligibility eligibility(Dependant d, LocalDate on, Integer ageLimit, Integer studentLimit) {
        if (!d.inCharge()) {
            return new Eligibility(false, null, "Not in the employee's charge.");
        }
        if (d.relationship().equals("SPOUSE") || d.birthDate() == null || ageLimit == null) {
            return new Eligibility(true, null, null);
        }
        if (d.disabled()) {
            return new Eligibility(true, null, "Disabled: no age limit.");
        }
        LocalDate adult = d.birthDate().plusYears(ageLimit);
        if (on.isBefore(adult)) {
            LocalDate until = d.student() && studentLimit != null && studentLimit > ageLimit
                    ? d.birthDate().plusYears(studentLimit).minusDays(1) : adult.minusDays(1);
            return new Eligibility(true, until, null);
        }
        if (d.student() && studentLimit != null) {
            LocalDate end = d.birthDate().plusYears(studentLimit);
            if (on.isBefore(end)) {
                return new Eligibility(true, end.minusDays(1), null);
            }
            return new Eligibility(false, null, Texts.t("Reached %s years.", studentLimit));
        }
        return new Eligibility(false, null, Texts.t("Reached %s years and does not study full time.", ageLimit));
    }

    @Transactional
    public Dependant addDependant(long userId, String name, String relationship, LocalDate birth, boolean inCharge,
                                  boolean student, boolean disabled, String note) {
        users.find(userId);
        check(userId, null, relationship, birth);
        long id = jdbc.sql("""
                insert into dependant (user_id, full_name, relationship, birth_date, in_charge, student, disabled, note)
                values (?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(userId, name.trim(), relationship, birth, inCharge, child(relationship) && student,
                        child(relationship) && disabled, trim(note)).query(Long.class).single();
        audit.record("dependant.added", "user", userId, Map.of("relationship", relationship, "name", name.trim()));
        return dependants(userId).stream().filter(d -> d.id() == id).findFirst().orElseThrow();
    }

    @Transactional
    public void updateDependant(long userId, long id, String name, String relationship, LocalDate birth,
                                boolean inCharge, boolean student, boolean disabled, String note) {
        Dependant before = dependants(userId).stream().filter(d -> d.id() == id).findFirst()
                .orElseThrow(() -> new NotFoundException("Dependant not found."));
        check(userId, id, relationship, birth);
        jdbc.sql("""
                update dependant set full_name = ?, relationship = ?, birth_date = ?, in_charge = ?, student = ?,
                                     disabled = ?, note = ?
                where id = ? and user_id = ?""")
                .params(name.trim(), relationship, birth, inCharge, child(relationship) && student,
                        child(relationship) && disabled, trim(note), id, userId).update();
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("dependantId", id);
        if (!before.fullName().equals(name.trim())) changes.put("name", name.trim());
        if (!before.relationship().equals(relationship)) changes.put("relationship", relationship);
        if (!java.util.Objects.equals(before.birthDate(), birth)) changes.put("birthDate", String.valueOf(birth));
        changes.put("inCharge", inCharge);
        changes.put("student", child(relationship) && student);
        changes.put("disabled", child(relationship) && disabled);
        audit.record("dependant.updated", "user", userId, changes);
    }

    private void check(long userId, Long self, String relationship, LocalDate birth) {
        if (relationship.equals("SPOUSE") && dependants(userId).stream()
                .anyMatch(d -> d.relationship().equals("SPOUSE") && !java.util.Objects.equals(d.id(), self))) {
            throw new ConflictException("A spouse is already recorded.");
        }
        if (child(relationship) && birth == null) {
            throw new BusinessRuleException("Enter the child's date of birth; allowances depend on age.");
        }
    }

    private static boolean child(String relationship) {
        return relationship.equals("CHILD");
    }

    @Transactional
    public void removeDependant(long userId, long id) {
        if (jdbc.sql("delete from dependant where id = ? and user_id = ?").params(id, userId).update() == 0) {
            throw new NotFoundException("Dependant not found.");
        }
        audit.record("dependant.removed", "user", userId, Map.of("dependantId", id));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return blank(s) ? null : s.trim();
    }
}
