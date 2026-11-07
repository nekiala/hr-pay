package cd.hrpay.payroll;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
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
                          String bankAccount, String mobileMoneyOperator, String mobileMoneyNumber) {}

    public record Dependant(long id, String fullName, String relationship, LocalDate birthDate, boolean inCharge,
                            String note) {}

    private static final String CONTRACT_SELECT = """
            select c.id, c.user_id, c.contract_type, c.start_date, c.end_date, c.grade_id, g.code as grade_code,
                   g.name as grade_name, g.category_id, c.occupation_id, o.name as occupation_name, c.expatriate,
                   c.note, c.created_by, c.created_at
            from employee_contract c join grade g on g.id = c.grade_id left join occupation o on o.id = c.occupation_id
            """;

    private final JdbcClient jdbc;
    private final UserService users;
    private final MasterData masterData;
    private final SalaryService salaries;
    private final AuditService audit;

    public EmployeeFile(JdbcClient jdbc, UserService users, MasterData masterData, SalaryService salaries,
                        AuditService audit) {
        this.jdbc = jdbc;
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
        AppUser user = users.find(userId);
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

    public Profile profile(long userId) {
        return jdbc.sql("""
                select birth_date, gender, nationality, marital_status, cnss_number, tax_number, payment_method,
                       bank_name, bank_account, mobile_money_operator, mobile_money_number
                from employee_profile where user_id = ?""").param(userId).query(Profile.class).optional()
                .orElse(new Profile(null, null, null, "SINGLE", null, null, "BANK", null, null, null, null));
    }

    @Transactional
    public Profile saveProfile(long userId, Profile p) {
        users.find(userId);
        if (p.paymentMethod().equals("BANK") && (blank(p.bankName()) || blank(p.bankAccount()))) {
            throw new BusinessRuleException("Enter the bank and the account number, or choose another payment method.");
        }
        if (p.paymentMethod().equals("MOBILE_MONEY") && (blank(p.mobileMoneyOperator()) || blank(p.mobileMoneyNumber()))) {
            throw new BusinessRuleException("Enter the mobile money operator and number.");
        }
        jdbc.sql("""
                insert into employee_profile (user_id, birth_date, gender, nationality, marital_status, cnss_number,
                    tax_number, payment_method, bank_name, bank_account, mobile_money_operator, mobile_money_number,
                    updated_by, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (user_id) do update set birth_date = excluded.birth_date, gender = excluded.gender,
                    nationality = excluded.nationality, marital_status = excluded.marital_status,
                    cnss_number = excluded.cnss_number, tax_number = excluded.tax_number,
                    payment_method = excluded.payment_method, bank_name = excluded.bank_name,
                    bank_account = excluded.bank_account, mobile_money_operator = excluded.mobile_money_operator,
                    mobile_money_number = excluded.mobile_money_number, updated_by = excluded.updated_by,
                    updated_at = now()""")
                .params(userId, p.birthDate(), p.gender(), trim(p.nationality()), p.maritalStatus(), trim(p.cnssNumber()),
                        trim(p.taxNumber()), p.paymentMethod(), trim(p.bankName()), trim(p.bankAccount()),
                        trim(p.mobileMoneyOperator()), trim(p.mobileMoneyNumber()), CurrentUser.email())
                .update();
        audit.record("profile.saved", "user", userId, Map.of("maritalStatus", p.maritalStatus(),
                "paymentMethod", p.paymentMethod()));
        return profile(userId);
    }

    public List<Dependant> dependants(long userId) {
        return jdbc.sql("select id, full_name, relationship, birth_date, in_charge, note from dependant where user_id = ? order by relationship desc, birth_date")
                .param(userId).query(Dependant.class).list();
    }

    @Transactional
    public Dependant addDependant(long userId, String name, String relationship, LocalDate birth, boolean inCharge,
                                  String note) {
        users.find(userId);
        if (relationship.equals("SPOUSE") && dependants(userId).stream().anyMatch(d -> d.relationship().equals("SPOUSE"))) {
            throw new ConflictException("A spouse is already recorded.");
        }
        if (relationship.equals("CHILD") && birth == null) {
            throw new BusinessRuleException("Enter the child's date of birth; allowances depend on age.");
        }
        long id = jdbc.sql("insert into dependant (user_id, full_name, relationship, birth_date, in_charge, note) values (?, ?, ?, ?, ?, ?) returning id")
                .params(userId, name.trim(), relationship, birth, inCharge, trim(note)).query(Long.class).single();
        audit.record("dependant.added", "user", userId, Map.of("relationship", relationship, "name", name.trim()));
        return dependants(userId).stream().filter(d -> d.id() == id).findFirst().orElseThrow();
    }

    @Transactional
    public void updateDependant(long userId, long id, boolean inCharge, String note) {
        if (jdbc.sql("update dependant set in_charge = ?, note = ? where id = ? and user_id = ?")
                .params(inCharge, trim(note), id, userId).update() == 0) {
            throw new NotFoundException("Dependant not found.");
        }
        audit.record("dependant.updated", "user", userId, Map.of("dependantId", id, "inCharge", inCharge));
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
