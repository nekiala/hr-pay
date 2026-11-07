package cd.hrpay.dataimport;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.SpreadsheetReader;
import cd.hrpay.i18n.Texts;
import cd.hrpay.leave.LeaveService;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.payroll.EmployeeFile;
import cd.hrpay.payroll.MasterData;
import cd.hrpay.salary.SalaryService;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.site.SiteRepository;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import cd.hrpay.user.UserService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Imports from a spreadsheet (Excel or CSV): employees, contracts, salaries, leave balances and the year-to-date
 * totals paid before HR Pay. Every row goes through the same rules as a change made on screen.
 *
 * <p>The whole file is worked through in one transaction, each row in its own savepoint, so a check reports every
 * row's problems at once and saves nothing; an import saves all the rows, or none when any row has a problem.
 */
@Service
public class Imports {

    public enum Kind { EMPLOYEES, CONTRACTS, SALARIES, LEAVE_BALANCES, YEAR_TO_DATE }

    public record Column(String key, String header, boolean required, String help) {}

    public record RowResult(int row, String registrationNo, String name, String action, List<String> errors,
                            List<String> warnings) {}

    public record Result(Kind kind, boolean saved, int rows, int withErrors, List<String> problems, List<RowResult> results) {}

    private static final Map<Kind, List<Column>> COLUMNS = new LinkedHashMap<>();

    static {
        COLUMNS.put(Kind.EMPLOYEES, List.of(
                col("registration_no", "Registration no.", true, "The person's number in the company. A number already in HR Pay updates that person."),
                col("first_name", "First name", true, null),
                col("last_name", "Last name", true, null),
                col("email", "Email", true, "Used to sign in; unique."),
                col("phone", "Phone", true, "International format, for example +243812345678."),
                col("site", "Site code", false, "Code of the site on the Sites page."),
                col("unit", "Unit code", false, "Code of the unit in the structure. Empty: the top unit."),
                col("hire_date", "Hire date", false, "Date: 2026-03-01 or 01/03/2026."),
                col("grade_category", "Grade category code", false, null),
                col("manager", "Manager's registration no.", false, "Only when the manager is not the head of the unit."),
                col("clock_pin", "Time clock number", false, "The number the person is enrolled under on the time clock. Empty: the registration number."),
                col("birth_date", "Birth date", false, null),
                col("gender", "Gender", false, "F or M."),
                col("nationality", "Nationality", false, null),
                col("postnom", "Postnom", false, "The middle name, as on the identity card."),
                col("commune", "Commune or territory", false, "Where the person lives, as the tax declaration asks."),
                col("marital_status", "Marital status", false, "Single, married, divorced or widowed."),
                col("cnss_number", "CNSS number", false, null),
                col("tax_number", "Tax number", false, null),
                col("payment_method", "Payment method", false, "Bank, mobile money or cash."),
                col("bank_name", "Bank", false, null),
                col("bank_account", "Bank account", false, null),
                col("mobile_money_operator", "Mobile money operator", false, "M-Pesa, Orange Money or Airtel Money."),
                col("mobile_money_number", "Mobile money number", false, null)));
        COLUMNS.put(Kind.CONTRACTS, List.of(
                col("registration_no", "Registration no.", true, null),
                col("contract_type", "Contract type", true, "Permanent (CDI), fixed-term (CDD), intern or consultant."),
                col("start_date", "Start date", true, null),
                col("end_date", "End date", false, "Required for a fixed-term contract."),
                col("grade", "Grade code", true, "Code of the grade on the Grades page."),
                col("occupation", "Occupation code", false, null),
                col("expatriate", "Expatriate", false, "Yes or no."),
                col("negotiated_basic", "Agreed salary", false, "At least the grade's basic; the rest is the salary complement. Empty: the grade's basic salary."),
                col("agreed_net", "Agreed net salary", false, "Instead of the agreed salary: HR Pay works out the complement that gives this net."),
                col("note", "Note", false, null)));
        COLUMNS.put(Kind.SALARIES, List.of(
                col("registration_no", "Registration no.", true, null),
                col("effective_from", "Effective from", true, null),
                col("gross_monthly", "Monthly basic salary", true, null),
                col("currency", "Currency", true, "USD, CDF or XAF."),
                col("note", "Note", false, null)));
        COLUMNS.put(Kind.LEAVE_BALANCES, List.of(
                col("registration_no", "Registration no.", true, null),
                col("leave_type", "Leave type code", false, "Empty: annual leave (ANNUAL)."),
                col("days", "Days", true, "Days to add to the balance (negative to remove)."),
                col("note", "Note", false, "Empty: Opening balance.")));
        COLUMNS.put(Kind.YEAR_TO_DATE, List.of(
                col("registration_no", "Registration no.", true, null),
                col("year", "Year", true, null),
                col("through_month", "Through month", true, "Last month paid by the old payroll, 1 to 12."),
                col("currency", "Currency", true, null),
                col("gross", "Gross", true, null),
                col("cnss", "Employee CNSS", false, null),
                col("tax", "Income tax", false, "IPR or ITS withheld."),
                col("other_deductions", "Other deductions", false, null),
                col("net", "Net", true, null),
                col("taxable_cdf", "Taxable income in local currency", false, null),
                col("tax_cdf", "Income tax in local currency", false, null),
                col("note", "Note", false, null)));
    }

    private static Column col(String key, String header, boolean required, String help) {
        return new Column(key, header, required, help);
    }

    private cd.hrpay.payroll.NegotiatedPay negotiated;

    @org.springframework.beans.factory.annotation.Autowired
    void setNegotiated(cd.hrpay.payroll.NegotiatedPay negotiated) {
        this.negotiated = negotiated;
    }

    private final JdbcClient jdbc;
    private final UserService users;
    private final UserRepository userRepository;
    private final SiteRepository sites;
    private final OrgStructure org;
    private final EmployeeFile employees;
    private final MasterData masterData;
    private final SalaryService salaries;
    private final LeaveService leave;
    private final AuditService audit;
    private final TransactionTemplate outer;
    private final javax.sql.DataSource dataSource;

    @PersistenceContext
    private EntityManager em;

    public Imports(JdbcClient jdbc, UserService users, UserRepository userRepository, SiteRepository sites, OrgStructure org,
                   EmployeeFile employees, MasterData masterData, SalaryService salaries, LeaveService leave,
                   AuditService audit, PlatformTransactionManager txManager, javax.sql.DataSource dataSource) {
        this.jdbc = jdbc;
        this.users = users;
        this.userRepository = userRepository;
        this.sites = sites;
        this.org = org;
        this.employees = employees;
        this.masterData = masterData;
        this.salaries = salaries;
        this.leave = leave;
        this.audit = audit;
        this.outer = new TransactionTemplate(txManager);
        this.dataSource = dataSource;
    }

    /** The columns of a kind, with headers in the user's language. */
    public List<Column> columns(Kind kind) {
        return COLUMNS.get(kind).stream()
                .map(c -> new Column(c.key(), Texts.t(c.header()), c.required(), c.help() == null ? null : Texts.t(c.help())))
                .toList();
    }

    public List<Map<String, Object>> history() {
        return jdbc.sql("select id, kind, file_name, row_count, created_by, created_at from import_batch order by id desc limit 50")
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("kind", rs.getString("kind"));
                    m.put("fileName", rs.getString("file_name"));
                    m.put("rows", rs.getInt("row_count"));
                    m.put("createdBy", rs.getString("created_by"));
                    m.put("createdAt", rs.getObject("created_at", java.time.OffsetDateTime.class));
                    return m;
                }).list();
    }

    // ---- running an import -------------------------------------------------------------------------------------

    /** Checks the file and, when {@code apply} and no row has a problem, saves it. */
    public Result run(Kind kind, String fileName, byte[] content, boolean apply) {
        List<List<String>> sheet = SpreadsheetReader.read(fileName, content);
        if (sheet.isEmpty()) {
            throw new BusinessRuleException("The file is empty.");
        }
        Map<String, Integer> index = headers(kind, sheet.getFirst());
        List<String> problems = new ArrayList<>();
        for (Column c : COLUMNS.get(kind)) {
            if (c.required() && !index.containsKey(c.key())) {
                problems.add(Texts.t("The column %s is missing.", Texts.t(c.header())));
            }
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < sheet.size(); i++) {
            List<String> cells = sheet.get(i);
            if (cells.stream().allMatch(String::isBlank)) {
                continue;
            }
            rows.add(new Row(i + 1, cells, index));
        }
        if (rows.isEmpty() && problems.isEmpty()) {
            problems.add(Texts.t("The file has no rows under the header."));
        }
        if (!problems.isEmpty()) {
            return new Result(kind, false, rows.size(), 0, problems, List.of());
        }
        Result result = outer.execute(status -> {
            List<RowResult> results = new ArrayList<>();
            Context ctx = new Context();
            for (Row row : rows) {
                results.add(one(kind, row, ctx));
            }
            if (kind == Kind.EMPLOYEES) {
                for (int i = 0; i < rows.size(); i++) {
                    if (results.get(i).errors().isEmpty()) {
                        results.set(i, managerOf(rows.get(i), results.get(i), ctx));
                    }
                }
            }
            int bad = (int) results.stream().filter(r -> !r.errors().isEmpty()).count();
            boolean save = apply && bad == 0;
            if (save) {
                if (kind == Kind.EMPLOYEES) {
                    org.placeUnassigned();
                }
                jdbc.sql("insert into import_batch (kind, file_name, row_count, created_by) values (?, ?, ?, ?)")
                        .params(kind.name(), fileName, rows.size(), CurrentUser.email()).update();
                audit.record("data.imported", "import", kind.name(), Map.of("kind", kind.name(), "rows", rows.size(),
                        "file", fileName == null ? "" : fileName));
            } else {
                status.setRollbackOnly();
            }
            return new Result(kind, save, rows.size(), bad, List.of(), results);
        });
        em.clear();
        return result;
    }

    /** What earlier rows of the same file did, for the rows after them. */
    private static final class Context {
        final Map<String, Long> created = new HashMap<>();
    }

    private RowResult one(Kind kind, Row row, Context ctx) {
        Outcome out = new Outcome(row);
        try {
            inSavepoint(() -> {
                switch (kind) {
                    case EMPLOYEES -> employee(row, out, ctx);
                    case CONTRACTS -> contract(row, out);
                    case SALARIES -> salary(row, out);
                    case LEAVE_BALANCES -> leaveBalance(row, out);
                    case YEAR_TO_DATE -> yearToDate(row, out);
                }
                if (!row.errors.isEmpty()) {
                    throw new RowFailed();
                }
            });
        } catch (RowFailed e) {
            // the row's own errors are listed
        } catch (RuntimeException e) {
            row.errors.add(message(e));
        }
        return out.result();
    }

    /**
     * Runs one row in a savepoint of the file's transaction: a row that fails is undone, and the rows after it still
     * run (PostgreSQL refuses every statement after an error until the savepoint is rolled back).
     */
    private void inSavepoint(Runnable work) {
        java.sql.Connection c = DataSourceUtils.getConnection(dataSource);
        java.sql.Savepoint sp;
        try {
            sp = c.setSavepoint();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Cannot set a savepoint", e);
        }
        try {
            work.run();
            em.flush();
            c.releaseSavepoint(sp);
        } catch (RuntimeException e) {
            try {
                c.rollback(sp);
            } catch (java.sql.SQLException ignored) {
                // the transaction is rolled back at the end anyway
            }
            em.clear();
            throw e;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class RowFailed extends RuntimeException {
        RowFailed() {
            super(null, null, false, false);
        }
    }

    private static String message(RuntimeException e) {
        if (e instanceof DataAccessException) {
            return Texts.t("This row breaks a rule of the database: check for a duplicate or a value that is too long.");
        }
        String m = e.getMessage();
        return m == null || m.isBlank() ? Texts.t("This row could not be saved.") : Texts.translate(m);
    }

    private final class Outcome {
        final Row row;
        String registration;
        String name;
        String action;

        Outcome(Row row) {
            this.row = row;
            this.registration = row.text("registration_no");
        }

        RowResult result() {
            return new RowResult(row.number, registration, name, row.errors.isEmpty() ? action : null, List.copyOf(row.errors),
                    List.copyOf(row.warnings));
        }
    }

    // ---- the kinds ---------------------------------------------------------------------------------------------

    private record Person(long id, String name) {}

    private Optional<Person> person(String registration) {
        if (registration == null) {
            return Optional.empty();
        }
        return jdbc.sql("select id, first_name || ' ' || last_name from app_user where upper(registration_no) = upper(?)")
                .param(registration.trim()).query((rs, i) -> new Person(rs.getLong(1), rs.getString(2))).optional();
    }

    private Person requirePerson(Row row, Outcome out) {
        Optional<Person> p = person(out.registration);
        if (p.isEmpty()) {
            throw new BusinessRuleException(Texts.t("No one has the registration number %s: import the employees first.", out.registration));
        }
        out.name = p.get().name();
        return p.get();
    }

    private void employee(Row row, Outcome out, Context ctx) {
        String registration = row.required("registration_no");
        String email = row.required("email");
        String phone = row.required("phone");
        String first = row.required("first_name");
        String last = row.required("last_name");
        Long siteId = null;
        String siteCode = row.text("site");
        if (siteCode != null) {
            siteId = sites.findByCodeIgnoreCase(siteCode).map(s -> s.getId())
                    .orElseThrow(() -> new BusinessRuleException(Texts.t("No site has the code %s.", siteCode)));
        }
        Long unitId = null;
        String unitCode = row.text("unit");
        if (unitCode != null) {
            unitId = jdbc.sql("select id from org_unit where upper(code) = upper(?) and valid_to is null").param(unitCode)
                    .query(Long.class).optional()
                    .orElseThrow(() -> new BusinessRuleException(Texts.t("No open unit has the code %s.", unitCode)));
        }
        Long categoryId = null;
        String categoryCode = row.text("grade_category");
        if (categoryCode != null) {
            categoryId = jdbc.sql("select id from grade_category where upper(code) = upper(?)").param(categoryCode)
                    .query(Long.class).optional()
                    .orElseThrow(() -> new BusinessRuleException(Texts.t("No grade category has the code %s.", categoryCode)));
        }
        LocalDate hire = row.date("hire_date");
        if (!row.errors.isEmpty()) {
            return;
        }
        out.name = first + " " + last;
        UserService.UserData data = new UserService.UserData(email, registration, phone, first, last, siteId);
        Optional<AppUser> existing = userRepository.findByRegistrationNoIgnoreCase(registration.trim());
        AppUser user;
        if (existing.isPresent()) {
            if (ctx.created.containsKey(registration.trim().toUpperCase())) {
                throw new BusinessRuleException(Texts.t("The registration number %s appears twice in the file.", registration));
            }
            user = users.update(existing.get().getId(), new UserService.UserData(email, registration, phone, first, last,
                    siteId != null ? siteId : existing.get().getSite() == null ? null : existing.get().getSite().getId()));
            out.action = "UPDATE";
            if (unitId != null && org.currentAssignment(user.getId()).map(a -> a.unitId()).filter(unitId::equals).isEmpty()) {
                row.warnings.add(Texts.t("The unit is not changed for someone already in HR Pay: move them on the Structure page."));
            }
        } else {
            user = users.create(data, List.of(), null);
            out.action = "CREATE";
            if (unitId != null) {
                org.assign(user.getId(), unitId, hire, "HIRE", null);
            }
        }
        ctx.created.put(registration.trim().toUpperCase(), user.getId());
        if (hire != null || categoryId != null) {
            users.setEmployment(user.getId(), categoryId != null ? categoryId : user.getGradeCategoryId(),
                    hire != null ? hire : user.getHireDate());
        }
        String pin = row.text("clock_pin");
        if (pin != null) {
            jdbc.sql("update app_user set clock_pin = ? where id = ?").params(pin, user.getId()).update();
        }
        profile(row, user.getId());
    }

    private void profile(Row row, long userId) {
        String[] keys = {"birth_date", "gender", "nationality", "marital_status", "cnss_number", "tax_number", "payment_method",
                "bank_name", "bank_account", "mobile_money_operator", "mobile_money_number", "postnom", "commune"};
        boolean any = false;
        for (String k : keys) {
            any |= row.text(k) != null;
        }
        if (!any) {
            return;
        }
        EmployeeFile.Profile p = employees.profile(userId);
        String gender = row.text("gender") == null ? p.gender() : choice(row, "gender", Map.of(
                "F", "FEMALE", "FEMALE", "FEMALE", "FEMME", "FEMALE", "M", "MALE", "MALE", "MALE", "H", "MALE", "HOMME", "MALE"));
        String marital = row.text("marital_status") == null ? p.maritalStatus() : choice(row, "marital_status", Map.ofEntries(
                Map.entry("SINGLE", "SINGLE"), Map.entry("CELIBATAIRE", "SINGLE"), Map.entry("MARRIED", "MARRIED"),
                Map.entry("MARIE", "MARRIED"), Map.entry("MARIEE", "MARRIED"), Map.entry("DIVORCED", "DIVORCED"),
                Map.entry("DIVORCE", "DIVORCED"), Map.entry("DIVORCEE", "DIVORCED"), Map.entry("WIDOWED", "WIDOWED"),
                Map.entry("VEUF", "WIDOWED"), Map.entry("VEUVE", "WIDOWED")));
        String method = row.text("payment_method") == null ? p.paymentMethod() : choice(row, "payment_method", Map.of(
                "BANK", "BANK", "BANQUE", "BANK", "VIREMENT", "BANK", "MOBILEMONEY", "MOBILE_MONEY", "MOBILE_MONEY", "MOBILE_MONEY",
                "CASH", "CASH", "ESPECES", "CASH", "CAISSE", "CASH"));
        if (!row.errors.isEmpty()) {
            throw new RowFailed();
        }
        LocalDate birth = row.text("birth_date") == null ? p.birthDate() : row.date("birth_date");
        String bank = or(row.text("bank_name"), p.bankName());
        String account = or(row.text("bank_account"), p.bankAccount());
        String operator = or(row.text("mobile_money_operator"), p.mobileMoneyOperator());
        String number = or(row.text("mobile_money_number"), p.mobileMoneyNumber());
        if (row.text("payment_method") == null) {
            // No method in the file: take it from the details given, else keep the stored one if complete, else cash.
            if (row.text("bank_name") != null || row.text("bank_account") != null) {
                method = "BANK";
            } else if (row.text("mobile_money_operator") != null || row.text("mobile_money_number") != null) {
                method = "MOBILE_MONEY";
            } else if ("BANK".equals(method) ? bank == null || account == null
                    : "MOBILE_MONEY".equals(method) ? operator == null || number == null : method == null) {
                method = "CASH";
            }
        }
        employees.saveProfile(userId, new EmployeeFile.Profile(birth, gender, or(row.text("nationality"), p.nationality()),
                marital == null ? "SINGLE" : marital, or(row.text("cnss_number"), p.cnssNumber()), or(row.text("tax_number"), p.taxNumber()),
                method, bank, account, operator, number, or(row.text("postnom"), p.postnom()), or(row.text("commune"), p.commune())));
    }

    private RowResult managerOf(Row row, RowResult r, Context ctx) {
        String manager = row.text("manager");
        if (manager == null) {
            return r;
        }
        Long userId = ctx.created.get(r.registrationNo().trim().toUpperCase());
        List<String> errors = new ArrayList<>();
        try {
            inSavepoint(() -> {
                Long managerId = Optional.ofNullable(ctx.created.get(manager.trim().toUpperCase()))
                        .or(() -> person(manager).map(Person::id))
                        .orElseThrow(() -> new BusinessRuleException(Texts.t("No one has the registration number %s.", manager)));
                users.setManager(userId, managerId);
            });
        } catch (RuntimeException e) {
            errors.add(message(e));
        }
        return errors.isEmpty() ? r : new RowResult(r.row(), r.registrationNo(), r.name(), null, errors, r.warnings());
    }

    private void contract(Row row, Outcome out) {
        row.required("registration_no");
        String type = choice(row, "contract_type", Map.of("PERMANENT", "PERMANENT", "CDI", "PERMANENT", "FIXED_TERM", "FIXED_TERM",
                "FIXEDTERM", "FIXED_TERM", "CDD", "FIXED_TERM", "INTERN", "INTERN", "STAGE", "INTERN", "STAGIAIRE", "INTERN",
                "CONSULTANT", "CONSULTANT"));
        LocalDate start = row.requiredDate("start_date");
        LocalDate end = row.date("end_date");
        String gradeCode = row.required("grade");
        BigDecimal basic = row.number("negotiated_basic");
        BigDecimal net = row.number("agreed_net");
        boolean expatriate = row.bool("expatriate");
        Person p = requirePerson(row, out);
        if (!row.errors.isEmpty()) {
            return;
        }
        MasterData.Grade grade = masterData.grades().stream().filter(g -> g.code().equalsIgnoreCase(gradeCode)).findFirst()
                .orElseThrow(() -> new BusinessRuleException(Texts.t("No grade has the code %s.", gradeCode)));
        Long occupationId = null;
        String occupation = row.text("occupation");
        if (occupation != null) {
            occupationId = masterData.occupations().stream().filter(o -> o.code().equalsIgnoreCase(occupation)).findFirst()
                    .map(MasterData.Occupation::id)
                    .orElseThrow(() -> new BusinessRuleException(Texts.t("No occupation has the code %s.", occupation)));
        }
        negotiated.startContract(p.id(), type, start, end, grade.id(), occupationId, expatriate, basic, net, row.text("note"));
        out.action = "ADD";
    }

    private void salary(Row row, Outcome out) {
        row.required("registration_no");
        LocalDate from = row.requiredDate("effective_from");
        BigDecimal gross = row.requiredNumber("gross_monthly");
        String currency = row.required("currency");
        Person p = requirePerson(row, out);
        if (!row.errors.isEmpty()) {
            return;
        }
        if (gross.signum() <= 0) {
            throw new BusinessRuleException(Texts.t("The salary must be above zero."));
        }
        salaries.add(p.id(), from, gross, currency(currency), or(row.text("note"), Texts.t("Imported")));
        out.action = "ADD";
    }

    private void leaveBalance(Row row, Outcome out) {
        row.required("registration_no");
        BigDecimal days = row.requiredNumber("days");
        String code = or(row.text("leave_type"), "ANNUAL");
        Person p = requirePerson(row, out);
        if (!row.errors.isEmpty()) {
            return;
        }
        LeaveService.LeaveType type = leave.types().stream().filter(t -> t.code().equalsIgnoreCase(code)).findFirst()
                .orElseThrow(() -> new BusinessRuleException(Texts.t("No leave type has the code %s.", code)));
        if (!type.usesBalance()) {
            throw new BusinessRuleException(Texts.t("%s has no balance to set.", type.name()));
        }
        leave.adjust(p.id(), type.id(), days, or(row.text("note"), Texts.t("Opening balance")));
        out.action = "ADD";
    }

    private void yearToDate(Row row, Outcome out) {
        row.required("registration_no");
        BigDecimal year = row.requiredNumber("year");
        BigDecimal through = row.requiredNumber("through_month");
        String currency = row.required("currency");
        BigDecimal gross = row.requiredNumber("gross");
        BigDecimal net = row.requiredNumber("net");
        Person p = requirePerson(row, out);
        if (!row.errors.isEmpty()) {
            return;
        }
        int y = year.intValue();
        int m = through.intValue();
        if (m < 1 || m > 12) {
            throw new BusinessRuleException(Texts.t("The month must be between 1 and 12."));
        }
        Integer closed = jdbc.sql("""
                select count(*) from payslip s join payroll_run r on r.id = s.run_id
                where s.user_id = ? and r.status = 'CLOSED' and extract(year from r.month) = ? and extract(month from r.month) <= ?""")
                .params(p.id(), y, m).query(Integer.class).single();
        if (closed > 0) {
            throw new BusinessRuleException(Texts.t("HR Pay already has closed payslips for these months: the totals would count twice."));
        }
        String cur = currency(currency);
        int updated = jdbc.sql("""
                update ytd_opening set through_month = ?, gross = ?, cnss = ?, tax = ?, other_deductions = ?, net = ?,
                    taxable_cdf = ?, tax_cdf = ?, note = ?, created_by = ?, created_at = now()
                where user_id = ? and year = ? and currency = ?""")
                .params(m, gross, zero(row.number("cnss")), zero(row.number("tax")), zero(row.number("other_deductions")), net,
                        zero(row.number("taxable_cdf")), zero(row.number("tax_cdf")), row.text("note"), CurrentUser.email(), p.id(), y, cur)
                .update();
        if (updated == 0) {
            jdbc.sql("""
                    insert into ytd_opening (user_id, year, through_month, currency, gross, cnss, tax, other_deductions, net,
                                             taxable_cdf, tax_cdf, note, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(p.id(), y, m, cur, gross, zero(row.number("cnss")), zero(row.number("tax")),
                            zero(row.number("other_deductions")), net, zero(row.number("taxable_cdf")), zero(row.number("tax_cdf")),
                            row.text("note"), CurrentUser.email()).update();
        }
        out.action = updated == 0 ? "ADD" : "UPDATE";
        audit.record("ytd_opening.saved", "user", p.id(), Map.of("year", y, "throughMonth", m, "currency", cur));
    }

    // ---- cells -------------------------------------------------------------------------------------------------

    private static String currency(String c) {
        String up = c.trim().toUpperCase(Locale.ROOT);
        if (up.equals("$")) {
            return "USD";
        }
        if (up.equals("FC")) {
            return "CDF";
        }
        if (up.equals("FCFA")) {
            return "XAF";
        }
        if (!up.matches("USD|CDF|XAF")) {
            throw new BusinessRuleException(Texts.t("Unknown currency %s: use USD, CDF or XAF.", c));
        }
        return up;
    }

    private static BigDecimal zero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String or(String v, String fallback) {
        return v == null ? fallback : v;
    }

    private static String choice(Row row, String key, Map<String, String> values) {
        String v = row.text(key);
        if (v == null) {
            if (COLUMNS.values().stream().flatMap(List::stream).anyMatch(c -> c.key().equals(key) && c.required())) {
                row.errors.add(Texts.t("%s is empty.", row.header(key)));
            }
            return null;
        }
        String k = normal(v).toUpperCase(Locale.ROOT).replace(" ", "").replace("(", "").replace(")", "");
        String found = values.get(k);
        if (found == null) {
            found = values.get(k.replace("_", ""));
        }
        if (found == null) {
            row.errors.add(Texts.t("%s: %s is not a known value.", row.header(key), v));
        }
        return found;
    }

    /** Lower case, no accents, single spaces: "Date d'embauche" and "date d embauche" match. */
    static String normal(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Other names people give these columns. */
    private static final Map<String, List<String>> ALIASES = Map.of(
            "registration_no", List.of("matricule", "n matricule", "no matricule", "numero matricule", "registration number",
                    "employee no", "employee number", "emp no"),
            "email", List.of("e mail", "courriel", "adresse email", "adresse e mail", "mail"),
            "phone", List.of("tel", "telephone", "phone number", "mobile", "gsm"),
            "first_name", List.of("prenom", "firstname"),
            "last_name", List.of("nom", "surname", "lastname", "nom de famille"),
            "gross_monthly", List.of("salaire de base", "basic salary", "salaire", "salary"),
            "effective_from", List.of("date d effet", "a partir du", "from"));

    private static Map<String, Integer> headers(Kind kind, List<String> header) {
        Map<String, String> names = new HashMap<>();
        for (Column c : COLUMNS.get(kind)) {
            for (String a : ALIASES.getOrDefault(c.key(), List.of())) {
                names.put(normal(a), c.key());
            }
        }
        for (Column c : COLUMNS.get(kind)) {
            names.put(normal(c.key()), c.key());
            names.put(normal(c.header()), c.key());
            names.put(normal(Texts.in(Texts.FR, () -> Texts.t(c.header()))), c.key());
        }
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String key = names.get(normal(header.get(i)));
            if (key != null && !index.containsKey(key)) {
                index.put(key, i);
            }
        }
        return index;
    }

    private static final List<DateTimeFormatter> DATES = List.of(DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/uuuu"), DateTimeFormatter.ofPattern("d.M.uuuu"), DateTimeFormatter.ofPattern("d-M-uuuu"));

    /** One row of the file, read by column key. Problems with a cell go to the row's errors. */
    static final class Row {
        final int number;
        final List<String> cells;
        final Map<String, Integer> index;
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        Row(int number, List<String> cells, Map<String, Integer> index) {
            this.number = number;
            this.cells = cells;
            this.index = index;
        }

        String header(String key) {
            return COLUMNS.values().stream().flatMap(List::stream).filter(c -> c.key().equals(key)).findFirst()
                    .map(c -> Texts.t(c.header())).orElse(key);
        }

        String text(String key) {
            Integer i = index.get(key);
            if (i == null || i >= cells.size()) {
                return null;
            }
            String v = cells.get(i).trim();
            return v.isEmpty() ? null : v;
        }

        String required(String key) {
            String v = text(key);
            if (v == null) {
                errors.add(Texts.t("%s is empty.", header(key)));
            }
            return v;
        }

        LocalDate date(String key) {
            String v = text(key);
            if (v == null) {
                return null;
            }
            if (v.matches("[0-9]{4,5}(\\.0+)?")) {
                // A date Excel stores as a day number (1 = 1900-01-01, with Excel's 1900 leap-year quirk).
                int serial = (int) Double.parseDouble(v);
                return LocalDate.of(1899, 12, 30).plusDays(serial);
            }
            String d = v.length() > 10 && v.charAt(10) == 'T' || v.length() > 10 && v.charAt(10) == ' ' ? v.substring(0, 10) : v;
            for (DateTimeFormatter f : DATES) {
                try {
                    return LocalDate.parse(d, f);
                } catch (DateTimeParseException e) {
                    // next format
                }
            }
            errors.add(Texts.t("%s: %s is not a date (write 2026-03-01 or 01/03/2026).", header(key), v));
            return null;
        }

        LocalDate requiredDate(String key) {
            if (text(key) == null) {
                errors.add(Texts.t("%s is empty.", header(key)));
                return null;
            }
            return date(key);
        }

        BigDecimal number(String key) {
            String v = text(key);
            if (v == null) {
                return null;
            }
            String n = v.replace(" ", "").replace(" ", "").replace("$", "");
            if (n.contains(",") && !n.contains(".")) {
                n = n.replace(",", ".");
            } else if (n.contains(",") && n.lastIndexOf(',') > n.lastIndexOf('.')) {
                n = n.replace(".", "").replace(",", ".");   // 1.500,50
            } else {
                n = n.replace(",", "");                     // 1,500.50
            }
            try {
                return new BigDecimal(n);
            } catch (NumberFormatException e) {
                errors.add(Texts.t("%s: %s is not a number.", header(key), v));
                return null;
            }
        }

        BigDecimal requiredNumber(String key) {
            if (text(key) == null) {
                errors.add(Texts.t("%s is empty.", header(key)));
                return null;
            }
            return number(key);
        }

        boolean bool(String key) {
            String v = text(key);
            if (v == null) {
                return false;
            }
            String n = normal(v);
            if (List.of("yes", "oui", "y", "o", "true", "vrai", "1", "x").contains(n)) {
                return true;
            }
            if (List.of("no", "non", "n", "false", "faux", "0").contains(n)) {
                return false;
            }
            errors.add(Texts.t("%s: write yes or no.", header(key)));
            return false;
        }
    }
}
