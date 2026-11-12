package cd.hrpay.payroll;

import org.springframework.security.core.context.SecurityContextHolder;
import cd.hrpay.attendance.AttendanceDays;
import cd.hrpay.attendance.DayCalculator;
import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.salary.SalaryService;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** The monthly payroll: calculate (HR), approve (Finance), close (HR). Closing numbers the payslips and locks them. */
@Service
public class PayrollRuns {

    public record Run(long id, LocalDate month, String status, BigDecimal usdCdf, LocalDate rateDate, String rateSource,
                      int employeeCount, boolean projected, String calculatedBy, OffsetDateTime calculatedAt,
                      String approvedBy, OffsetDateTime approvedAt, String closedBy, OffsetDateTime closedAt) {}

    public record Total(String currency, int payslips, BigDecimal gross, BigDecimal employeeDeductions,
                        BigDecimal net, BigDecimal employerCharges) {}

    public record PayslipSummary(long id, long userId, String userName, String registrationNo, String number,
                                 String currency, BigDecimal gross, BigDecimal employeeDeductions, BigDecimal net,
                                 BigDecimal employerCharges, int warningCount, Long unitId, String unitName,
                                 Long directionId, String directionName, String kind, Long correctsId) {

        PayslipSummary placed(Map<Long, cd.hrpay.org.OrgStructure.Placement> units) {
            var p = unitId == null ? null : units.get(unitId);
            return p == null ? this : new PayslipSummary(id, userId, userName, registrationNo, number, currency, gross,
                    employeeDeductions, net, employerCharges, warningCount, unitId, p.unitName(), p.directionId(), p.directionName(),
                    kind, correctsId);
        }
    }

    public record Line(String elementCode, String label, String kind, BigDecimal base, BigDecimal rate,
                       BigDecimal amount, int displayOrder, Long loanId) {}

    public record Payslip(long id, long runId, LocalDate month, String runStatus, long userId, String number,
                          String currency, BigDecimal gross, BigDecimal cnssBase, BigDecimal taxableBase,
                          BigDecimal taxableBaseCdf, BigDecimal employeeDeductions, BigDecimal net,
                          BigDecimal employerCharges, int scheduledMinutes, int paidMinutes, int daysWorked,
                          BigDecimal usdCdf, LocalDate rateDate, boolean projected, Map<String, Object> snapshot,
                          List<String> warnings, List<Line> lines, String kind, Long correctsId,
                          String correctsNumber, String reason) {

        public boolean complementary() {
            return "COMPLEMENTARY".equals(kind);
        }

        /** A leave allowance payslip (bulletin d'allocation de congé). */
        public boolean leave() {
            return "LEAVE".equals(kind);
        }
    }

    public record RunView(Run run, List<Total> totals, List<PayslipSummary> payslips, List<String> skipped) {}

    private static final String RUN_SELECT = """
            select id, month, status, usd_cdf, rate_date, rate_source, employee_count, projected, calculated_by,
                   calculated_at, approved_by, approved_at, closed_by, closed_at from payroll_run""";

    private final JdbcClient jdbc;
    private final cd.hrpay.security.DataCrypto crypto;
    private final UserRepository users;
    private final AttendanceDays attendance;
    private final SalaryService salaries;
    private final EmployeeFile files;
    private final PayElements pay;
    private final StatutoryRates statutory;
    private final ExchangeRates exchange;
    private final Loans loans;
    private final Thirteenths thirteenths;
    private final AuditService audit;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private final SeniorityBonus seniority;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entities;
    private cd.hrpay.org.OrgStructure org;

    @org.springframework.beans.factory.annotation.Autowired
    void setOrg(cd.hrpay.org.OrgStructure org) {
        this.org = org;
    }

    private cd.hrpay.leave.LeaveService leave;
    private cd.hrpay.tenancy.Tenants tenants;
    private int threads = 4;
    private int parallelFrom = 100;

    @org.springframework.beans.factory.annotation.Autowired
    void setTenants(cd.hrpay.tenancy.Tenants tenants,
                    @org.springframework.beans.factory.annotation.Value("${app.payroll.threads:4}") int threads,
                    @org.springframework.beans.factory.annotation.Value("${app.payroll.parallel-from:100}") int parallelFrom) {
        this.tenants = tenants;
        this.threads = threads;
        this.parallelFrom = parallelFrom;
    }

    private PayrollChecks checks;

    @org.springframework.beans.factory.annotation.Autowired
    void setChecks(PayrollChecks checks) {
        this.checks = checks;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setLeave(cd.hrpay.leave.LeaveService leave) {
        this.leave = leave;
    }

    public PayrollRuns(JdbcClient jdbc, UserRepository users, AttendanceDays attendance, SalaryService salaries,
                       EmployeeFile files, PayElements pay, StatutoryRates statutory, ExchangeRates exchange, Loans loans,
                       Thirteenths thirteenths, AuditService audit, Clock clock, cd.hrpay.security.DataCrypto crypto) {
        this.seniority = new SeniorityBonus(jdbc);
        this.thirteenths = thirteenths;
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.users = users;
        this.attendance = attendance;
        this.salaries = salaries;
        this.files = files;
        this.pay = pay;
        this.statutory = statutory;
        this.exchange = exchange;
        this.loans = loans;
        this.audit = audit;
        this.clock = clock;
    }

    /** Refuses a change to pay data of a month whose payroll is approved or closed. */
    /** The same, for other packages. */
    public static void requireOpenMonth(JdbcClient jdbc, YearMonth month) {
        requireOpen(jdbc, month);
    }

    static void requireOpen(JdbcClient jdbc, YearMonth month) {
        jdbc.sql("select status from payroll_run where month = ?").param(month.atDay(1)).query(String.class).optional()
                .filter(s -> !s.equals("CALCULATED"))
                .ifPresent(s -> {
                    throw new BusinessRuleException("The payroll for " + month + " is " + s.toLowerCase()
                            + ": changes go into a later month.");
                });
    }

    public List<Run> runs() {
        return jdbc.sql(RUN_SELECT + " order by month desc").query(Run.class).list();
    }

    public java.util.Optional<Run> run(YearMonth month) {
        return jdbc.sql(RUN_SELECT + " where month = ?").param(month.atDay(1)).query(Run.class).optional();
    }

    private Run require(YearMonth month) {
        return run(month).orElseThrow(() -> new NotFoundException("No payroll for %s yet.".formatted(month)));
    }

    public RunView view(YearMonth month, List<String> skipped) {
        Run run = require(month);
        List<PayslipSummary> slips = jdbc.sql("""
                select p.id, p.user_id, person_name(u.first_name, u.middle_name, u.last_name) as user_name, u.registration_no, p.number,
                       trim(p.currency) as currency, p.gross, p.employee_deductions, p.net, p.employer_charges,
                       jsonb_array_length(p.warnings) as warning_count, p.unit_id, null as unit_name,
                       null::bigint as direction_id, null as direction_name, p.kind, p.corrects_id
                from payslip p join app_user u on u.id = p.user_id where p.run_id = ?
                order by u.last_name, u.first_name, p.id""").param(run.id()).query(PayslipSummary.class).list();
        var units = org.placementsOfUnits();
        slips = slips.stream().map(x -> x.placed(units)).toList();
        List<Total> totals = jdbc.sql("""
                select trim(currency) as currency, count(*) as payslips, sum(gross) as gross,
                       sum(employee_deductions) as employee_deductions, sum(net) as net,
                       sum(employer_charges) as employer_charges
                from payslip where run_id = ? and (kind = 'REGULAR' or number is not null)
                group by currency order by currency""").param(run.id())
                .query(Total.class).list();
        return new RunView(run, totals, slips, skipped);
    }

    // ---- calculate ---------------------------------------------------------------------------------------------

    /** The Republic of the Congo's rates: CNSS with its ceilings, the tax on salaries (TUS) and the ITS bands. */
    private PayslipCalculator.Rates congoRates(BigDecimal usdLocal, LocalDate on, String local) {
        Map<String, BigDecimal> more = new java.util.HashMap<>();
        for (String code : List.of("CNSS_CEILING", "CNSS_FAMILY", "CNSS_ACCIDENT", "CNSS_FAMILY_CEILING", "TUS",
                "ITS_PROFESSIONAL_DEDUCTION", "ITS_MAX_PARTS", "SMIG_MONTHLY")) {
            more.put(code, statutory.rate(code, on));
        }
        // Loi n° 42-2025 (finance law 2026): the ITS first band as a flat amount, and the minimum below the SMIG.
        // The CAMU solidarity contribution and the TOL: only when the company has their rates.
        for (String code : List.of("ITS_FIRST_BAND_FLAT", "ITS_MINIMUM_YEARLY", "CAMU_SOLIDARITY",
                "CAMU_SOLIDARITY_THRESHOLD", "TOL_MONTHLY")) {
            more.put(code, statutory.rateOrZero(code, on));
        }
        return new PayslipCalculator.Rates(usdLocal, statutory.rate("CNSS_EMPLOYEE", on), statutory.rate("CNSS_EMPLOYER", on),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, statutory.rate("DEBT_MAX_SHARE", on),
                statutory.brackets(on).brackets(), BigDecimal.ZERO, 0, BigDecimal.valueOf(100), null, "CG", local, Map.copyOf(more));
    }

    /** The grade's minimum-wage coefficient (100 = manœuvre ordinaire), or null when the grade has none. */
    BigDecimal smigCoefficient(Long gradeId) {
        return gradeId == null ? null : jdbc.sql("select smig_coefficient from grade where id = ?").param(gradeId)
                .query(BigDecimal.class).optional().orElse(null);
    }

    String localCurrency() {
        return jdbc.sql("select local_currency from company where id = current_company_id()").query(String.class).single();
    }

    /** The month's rates, at the given price of a dollar in the local currency. */
    PayslipCalculator.Rates rates(LocalDate last, BigDecimal usdLocal) {
        String country = jdbc.sql("select country_code from company where id = current_company_id()").query(String.class).single();
        if (!"CD".equals(country) && !"CG".equals(country)) {
            CountryRules rules = statutory.countryRules(last);
            if (rules == null) {
                throw new cd.hrpay.common.BusinessRuleException("No contributions are set for this country.");
            }
            return PayslipCalculator.Rates.of(usdLocal, country, localCurrency(), statutory.rate("DEBT_MAX_SHARE", last),
                    statutory.brackets(last).brackets(), rules);
        }
        return "CG".equals(country)
                ? congoRates(usdLocal, last, localCurrency())
                : new PayslipCalculator.Rates(usdLocal,
                        statutory.rate("CNSS_EMPLOYEE", last), statutory.rate("CNSS_EMPLOYER", last), statutory.inppRate(last),
                        statutory.rate("ONEM", last), statutory.rate("IERE", last), statutory.rate("DEBT_MAX_SHARE", last),
                        statutory.brackets(last).brackets(), statutory.rate("IPR_DEPENDANT_REDUCTION", last),
                        statutory.rate("IPR_MAX_DEPENDANTS", last).intValue(), statutory.rate("IPR_CAP", last),
                        statutory.rate("SMIG_DAILY", last), "CD", "CDF",
                        Map.of("IPR_MIN_MONTHLY", statutory.rateOrZero("IPR_MIN_MONTHLY", last)));
    }

    /** What working out the payslips of one month needs, gathered once. */
    record MonthContext(YearMonth month, LocalDate first, LocalDate last, LocalDate today, PayslipCalculator.Rates rates,
                        BigDecimal usdLocal, List<PayElements.Element> elements, Map<Long, Thirteenths.Due> thirteenth,
                        Map<Long, cd.hrpay.org.OrgStructure.Placement> placements,
                        Map<Long, cd.hrpay.org.OrgStructure.Placement> nowPlaced) {}

    MonthContext context(YearMonth month, PayslipCalculator.Rates rates, BigDecimal usdLocal) {
        LocalDate today = LocalDate.now(clock);
        return new MonthContext(month, month.atDay(1), month.atEndOfMonth(), today, rates, usdLocal, pay.elements(),
                thirteenths.dueIn(month), org.placementsOn(month.atEndOfMonth()),
                org.placementsOn(today));
    }

    /** One person's payslip as worked out today, before it is stored; or why there is none. */
    record Computed(long userId, String name, PayslipCalculator.Result result, Map<String, Object> snapshot,
                    List<String> warnings, int scheduledMinutes, int paidMinutes, int daysWorked, Long unitId,
                    String skipped) {}

    /** The confirmed departure whose last day falls in the month, if any. */
    java.util.Optional<LocalDate> lastDayIn(long userId, YearMonth month) {
        return jdbc.sql("select last_day from departure where user_id = ? and status = 'CONFIRMED' and last_day between ? and ?")
                .params(userId, month.atDay(1), month.atEndOfMonth()).query(LocalDate.class).optional();
    }

    SalaryService salaries() {
        return salaries;
    }

    SeniorityBonus seniority() {
        return seniority;
    }

    /**
     * Works out one person's payslip for the context's month from today's data. {@code withLoans} false leaves loan
     * repayments out (a correction of a closed month: its repayments are already recorded).
     */
    Computed compute(MonthContext ctx, long userId, boolean withLoans) {
        return compute(ctx, userId, withLoans, List.of());
    }

    /** The same, with one-off lines that are not stored as pay entries (a correction's additions). */
    Computed compute(MonthContext ctx, long userId, boolean withLoans, List<PayElements.Entry> extra) {
        return compute(ctx, userId, withLoans, extra, Scenario.NONE);
    }

    /**
     * Changes to the recorded data for a simulation or a projection, never stored: each null or zero field keeps what
     * is recorded. {@code basic} replaces the basic salary, {@code complement} the negotiated salary complement;
     * {@code lastDay} makes the month the person's last; {@code extraOvertimeMinutes} adds weekday overtime priced with
     * the overtime policy; {@code unpaidDays} takes days off the paid time; {@code thirteenth} replaces the approved 13th
     * month when {@code thirteenthSet}; {@code loans} replaces the loan installments due; {@code leaveRanges} are annual
     * leave periods paid by an allocation de congé (the days leave the monthly payslip, as when the allocation is approved).
     */
    public record Scenario(BigDecimal basic, BigDecimal complement, LocalDate lastDay, int extraOvertimeMinutes,
                           BigDecimal unpaidDays, Integer children, Integer dependants, boolean thirteenthSet,
                           Thirteenths.Due thirteenth, List<PayslipCalculator.LoanDue> loans, List<LocalDate[]> leaveRanges) {
        public static final Scenario NONE = new Scenario(null, null, null, 0, null, null, null, false, null, null, List.of());
    }

    /** One person's payslip worked out with a scenario's changes (see {@link Scenario}). */
    Computed compute(MonthContext ctx, long userId, boolean withLoans, List<PayElements.Entry> extra, Scenario sc) {
        YearMonth month = ctx.month();
        LocalDate first = ctx.first();
        LocalDate last = ctx.last();
        LocalDate today = ctx.today();
        BigDecimal usdLocal = ctx.usdLocal();
        AppUser user = users.findById(userId).orElseThrow();
        String name = user.fullName();
        var salary = salaries.on(userId, last.isAfter(today) ? today : last).orElse(null);
        if (salary == null) {
            return new Computed(userId, name, null, null, null, 0, 0, 0, null, name + ": no basic salary recorded.");
        }
        var contract = files.contractOn(userId, last.isAfter(today) ? today : last)
                .or(() -> files.contracts(userId).stream()
                        .filter(c -> !c.startDate().isAfter(last) && (c.endDate() == null || !c.endDate().isBefore(first)))
                        .findFirst())
                .orElse(null);
        if (contract == null) {
            return new Computed(userId, name, null, null, null, 0, 0, 0, null, name + ": no contract in this month.");
        }
        LocalDate leaves = sc.lastDay() != null && !sc.lastDay().isBefore(first) && !sc.lastDay().isAfter(last)
                ? sc.lastDay() : lastDayIn(userId, month).orElse(null);
        var summary = attendance.month(user, month, true);
        BigDecimal basicMonthly = sc.basic() != null ? sc.basic() : salary.grossMonthly();
        int paid;
        int daysWorked;
        if (leaves == null) {
            int futureScheduled = summary.scheduledMinutesMonth() - summary.scheduledMinutesToDate();
            paid = summary.payableMinutesToDate() + futureScheduled;
            daysWorked = (int) summary.days().stream().filter(d -> !d.date().isAfter(summary.asOf())
                    && (d.workedMinutes() > 0 || d.mission())).count()
                    + (int) summary.days().stream().filter(d -> d.date().isAfter(summary.asOf()) && d.scheduledMinutes() > 0
                    && d.status() != DayCalculator.Status.HOLIDAY).count();
        } else {
            // The last month: the days after the last day are not paid.
            paid = summary.days().stream().filter(d -> !d.date().isAfter(leaves))
                    .mapToInt(d -> d.date().isAfter(summary.asOf()) ? d.scheduledMinutes() : d.payableMinutes()).sum();
            daysWorked = (int) summary.days().stream().filter(d -> !d.date().isAfter(leaves)).filter(d -> d.date().isAfter(summary.asOf())
                    ? d.scheduledMinutes() > 0 && d.status() != DayCalculator.Status.HOLIDAY
                    : d.workedMinutes() > 0 || d.mission()).count();
        }
        int scheduledDays = (int) summary.days().stream().filter(d -> d.scheduledMinutes() > 0).count();
        // Travel mission days are worked days; the transport allowance (paid per day worked) stops on them when the
        // company says so: the employee does not commute.
        final LocalDate lastPaid = leaves;
        int travelDays = (int) summary.days().stream()
                .filter(d -> d.status() == DayCalculator.Status.MISSION && (lastPaid == null || !d.date().isAfter(lastPaid)))
                .count();
        boolean transportStops = travelDays > 0 && "STOP".equals(jdbc.sql(
                "select value from payroll_setting where key = 'MISSION_TRANSPORT_ON_TRAVEL'").query(String.class).optional().orElse("STOP"));
        BigDecimal overtime = summary.earnings() == null ? BigDecimal.ZERO
                : ExchangeRates.convert(summary.earnings().overtimeEarned(), summary.earnings().currency(), salary.currency(), usdLocal);
        int overtimeMinutes = summary.overtimeApprovedMinutes();
        // A simulation's changes: days not paid, and more overtime priced like the recorded overtime.
        if (sc.unpaidDays() != null && sc.unpaidDays().signum() > 0 && scheduledDays > 0) {
            int perDay = summary.scheduledMinutesMonth() / scheduledDays;
            int off = sc.unpaidDays().multiply(BigDecimal.valueOf(perDay)).intValue();
            paid = Math.max(0, paid - off);
            daysWorked = Math.max(0, daysWorked - sc.unpaidDays().setScale(0, java.math.RoundingMode.CEILING).intValue());
        }
        if (sc.extraOvertimeMinutes() > 0 && summary.scheduledMinutesMonth() > 0) {
            BigDecimal agreed = basicMonthly.add(sc.complement() != null ? sc.complement()
                    : pay.standingFor(userId, month).stream().filter(a -> a.elementCode().equals(NegotiatedPay.COMPLEMENT)
                            && a.amount() != null && salary.currency().equals(a.currency()))
                    .map(PayElements.Assignment::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
            BigDecimal perMinute = agreed.divide(BigDecimal.valueOf(summary.scheduledMinutesMonth()), 10, java.math.RoundingMode.HALF_UP);
            overtime = overtime.add(perMinute.multiply(attendance.weightedWeekdayOvertime(user, last, sc.extraOvertimeMinutes()))
                    .setScale(2, java.math.RoundingMode.HALF_UP));
            overtimeMinutes += sc.extraOvertimeMinutes();
        }
        // Only the dependants who count this month: in charge, and children within the age limits (art. 7).
        EmployeeFile.Counted counted = files.counted(userId, month);
        int children = sc.children() != null ? sc.children() : counted.children();
        int inCharge = sc.dependants() != null ? sc.dependants() : counted.dependants();
        // The 13th month comes from its own approved run for this payroll month.
        Thirteenths.Due thirteenth = sc.thirteenthSet() ? sc.thirteenth() : ctx.thirteenth().get(userId);
        List<PayslipCalculator.LoanDue> due = sc.loans() != null ? sc.loans() : withLoans ? loans.dueFor(userId, month) : List.of();
        List<PayslipCalculator.LoanDue> loansDue = !withLoans ? List.of() : leaves == null ? due
                // On the last payslip, what is left of each loan is due.
                : due.stream().map(l -> new PayslipCalculator.LoanDue(l.loanId(), l.kind(),
                        l.balance(), l.balance(), l.currency())).toList();

        // Annual leave paid by its own allocation payslip: the allocation pays the remuneration of those days, the
        // allowances outside it (housing, family allowances) stay on this payslip, and the days count as paid time
        // (for the absence rate), not as absence.
        List<LocalDate[]> allocated = jdbc.sql("""
                select r.start_date, r.end_date from leave_allowance a join leave_request r on r.id = a.leave_request_id
                where a.user_id = ? and a.status in ('APPROVED', 'PAID') and r.start_date <= ? and r.end_date >= ?""")
                .params(userId, last, first).query((rs, i) -> new LocalDate[] {rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)})
                .list();
        int allocatedMinutes = summary.days().stream().filter(d -> d.status() == DayCalculator.Status.LEAVE
                        && allocated.stream().anyMatch(x -> !d.date().isBefore(x[0]) && !d.date().isAfter(x[1])))
                .mapToInt(d -> Math.max(0, d.scheduledMinutes() - d.payableMinutes())).sum();
        // A projection's leave paid by an allocation not worked out yet: its days leave the monthly payslip the same way.
        if (sc.leaveRanges() != null && !sc.leaveRanges().isEmpty()) {
            final LocalDate cut = leaves;
            int moved = summary.days().stream()
                    .filter(d -> d.scheduledMinutes() > 0 && d.status() != DayCalculator.Status.HOLIDAY)
                    .filter(d -> cut == null || !d.date().isAfter(cut))
                    .filter(d -> allocated.stream().noneMatch(x -> !d.date().isBefore(x[0]) && !d.date().isAfter(x[1])))
                    .filter(d -> sc.leaveRanges().stream().anyMatch(x -> !d.date().isBefore(x[0]) && !d.date().isAfter(x[1])))
                    .mapToInt(d -> d.date().isAfter(summary.asOf()) ? d.scheduledMinutes() : d.payableMinutes()).sum();
            paid = Math.max(0, paid - moved);
            allocatedMinutes += moved;
        }
        List<PayElements.Assignment> standing = pay.standingFor(userId, month);
        if (sc.complement() != null) {
            standing = new ArrayList<>(standing.stream().filter(a -> !a.elementCode().equals(NegotiatedPay.COMPLEMENT)).toList());
            if (sc.complement().signum() > 0) {
                PayElements.Element ce = ctx.elements().stream().filter(e -> e.code().equals(NegotiatedPay.COMPLEMENT))
                        .findFirst().orElse(null);
                if (ce != null) {
                    standing.add(new PayElements.Assignment(0, userId, ce.id(), ce.code(), ce.name(), sc.complement(),
                            salary.currency(), null, false, null, null, null, null, null));
                }
            }
        }
        PayslipCalculator.Result r = PayslipCalculator.calculate(new PayslipCalculator.Input(salary.currency(),
                basicMonthly, summary.scheduledMinutesMonth(), paid, scheduledDays, daysWorked, overtime,
                overtimeMinutes, thirteenth == null ? null
                        : ExchangeRates.convert(thirteenth.amount(), thirteenth.currency(), salary.currency(), usdLocal),
                thirteenth == null ? null : thirteenth.note(), children, inCharge, contract.expatriate(),
                pay.forContract(ctx.elements(), contract.contractType()),
                standing,
                java.util.stream.Stream.concat(pay.entriesFor(userId, month).stream(), extra.stream()).toList(), loansDue, ctx.rates(),
                allocatedMinutes, smigCoefficient(contract.gradeId()), files.profile(userId).maritalStatus(),
                seniority.percent(user.getHireDate() != null ? user.getHireDate() : contract.startDate(),
                        leaves != null && leaves.isBefore(last) ? leaves : last),
                transportStops ? Math.max(0, daysWorked - travelDays) : null));
        List<String> warnings = new ArrayList<>(r.warnings());
        if (summary.overtimePendingMinutes() > 0) {
            warnings.add(summary.overtimePendingMinutes() / 60 + " h " + summary.overtimePendingMinutes() % 60
                    + " min of overtime still wait for approval and are not paid.");
        }
        if (summary.missingCheckOutDays() > 0) {
            warnings.add(summary.missingCheckOutDays() + " day(s) without a check-out.");
        }
        if (leaves != null) {
            warnings.add(cd.hrpay.i18n.Texts.t("Last payslip: the person leaves on %s.", leaves));
            for (var l : loansDue) {
                BigDecimal taken = r.lines().stream().filter(x -> Objects.equals(x.loanId(), l.loanId()))
                        .map(PayslipCalculator.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (ExchangeRates.convert(taken, r.currency(), l.currency(), usdLocal).compareTo(l.balance()) < 0) {
                    warnings.add(cd.hrpay.i18n.Texts.t("Loan #%s is not fully repaid by the last payslip: settle the rest with the person.", l.loanId()));
                }
            }
        }

        EmployeeFile.Profile profile = files.profile(userId);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", name);
        snapshot.put("registrationNo", user.getRegistrationNo());
        snapshot.put("email", user.getEmail());
        snapshot.put("contractType", contract.contractType());
        snapshot.put("contractStart", contract.startDate().toString());
        snapshot.put("grade", contract.gradeCode() + " · " + contract.gradeName());
        // Kept for the payroll reports: grouping by grade, grade category and site as the month was paid.
        snapshot.put("gradeId", contract.gradeId());
        snapshot.put("categoryId", contract.categoryId());
        snapshot.put("siteId", user.getSite() == null ? null : user.getSite().getId());
        snapshot.put("scheduledDays", scheduledDays);
        Map<String, Integer> leaveDays = new java.util.TreeMap<>();
        summary.days().stream().filter(d -> d.status() == DayCalculator.Status.LEAVE && d.leaveType() != null)
                .forEach(d -> leaveDays.merge(d.leaveType(), 1, Integer::sum));
        snapshot.put("leaveDays", leaveDays);
        if (travelDays > 0) {
            snapshot.put("missionDays", travelDays);
        }
        // For the checks before approval (PayrollChecks).
        snapshot.put("absentDays", summary.absentDays());
        snapshot.put("overtimePendingMinutes", summary.overtimePendingMinutes());
        snapshot.put("overtimeWeekMaxMinutes", summary.overtime().stream()
                .filter(o -> o.status() != AttendanceDays.OvertimeStatus.REJECTED)
                .collect(java.util.stream.Collectors.groupingBy(o -> o.date().with(java.time.DayOfWeek.MONDAY),
                        java.util.stream.Collectors.summingInt(AttendanceDays.OvertimeDay::minutes)))
                .values().stream().mapToInt(Integer::intValue).max().orElse(0));
        snapshot.put("occupation", contract.occupationName());
        snapshot.put("expatriate", contract.expatriate());
        snapshot.put("basicMonthly", basicMonthly);
        snapshot.put("childrenInCharge", children);
        snapshot.put("maritalStatus", profile.maritalStatus());
        LocalDate hired = user.getHireDate() != null ? user.getHireDate() : contract.startDate();
        snapshot.put("hireDate", hired == null ? null : hired.toString());
        snapshot.put("dependantsInCharge", inCharge);
        // Annual leave earned this month (DRC art. 141), credited when the payroll is closed: the scheduled days in
        // employment, less unpaid leave and unjustified absence, which are not service.
        String unpaidType = leave.unpaidTypeName();
        LocalDate serviceFrom = hired != null && hired.isAfter(first) ? hired : first;
        LocalDate serviceTo = leaves != null && leaves.isBefore(last) ? leaves : last;
        List<DayCalculator.DayResult> scheduled = summary.days().stream().filter(d -> d.scheduledMinutes() > 0).toList();
        int serviceDays = (int) scheduled.stream()
                .filter(d -> !d.date().isBefore(serviceFrom) && !d.date().isAfter(serviceTo))
                .filter(d -> !(d.status() == DayCalculator.Status.ABSENT && !d.date().isAfter(summary.asOf())))
                .filter(d -> !(d.status() == DayCalculator.Status.LEAVE && unpaidType != null && unpaidType.equals(d.leaveType())))
                .count();
        leave.earned(userId, month, scheduled.size(), serviceDays).ifPresent(e -> {
            snapshot.put("leaveEarned", e.days());
            boolean credited = jdbc.sql("""
                    select count(*) from leave_ledger l join leave_type t on t.id = l.leave_type_id
                    where l.user_id = ? and t.code = 'ANNUAL' and l.kind = 'EARNED' and l.entry_date = ?""")
                    .params(userId, month.atEndOfMonth()).query(Integer.class).single() > 0;
            snapshot.put("leaveBalance", leave.annualBalance(userId).add(credited ? BigDecimal.ZERO : e.days()));
        });

        if (leaves != null) {
            snapshot.put("lastDay", leaves.toString());
        }
        snapshot.put("cnssNumber", crypto.encrypt(profile.cnssNumber()));
        snapshot.put("taxNumber", crypto.encrypt(profile.taxNumber()));
        snapshot.put("paymentMethod", profile.paymentMethod());
        snapshot.put("bankName", profile.bankName());
        snapshot.put("bankAccount", crypto.encrypt(profile.bankAccount()));
        snapshot.put("mobileMoneyOperator", profile.mobileMoneyOperator());
        snapshot.put("mobileMoneyNumber", crypto.encrypt(profile.mobileMoneyNumber()));
        var place = ctx.placements().getOrDefault(userId, ctx.nowPlaced().get(userId));
        if (place != null) {
            snapshot.put("unit", place.unitName());
            snapshot.put("unitPath", place.path());
            snapshot.put("direction", place.directionName());
            snapshot.put("costCentre", place.costCentre());
        }
        return new Computed(userId, name, r, snapshot, warnings, summary.scheduledMinutesMonth(),
                Math.min(summary.scheduledMinutesMonth(), paid + allocatedMinutes), daysWorked,
                place == null ? null : place.unitId(), null);
    }

    /** Writes a payslip and its lines; returns its id. */
    long store(long runId, Computed c, String kind, Long correctsId, String reason) {
        PayslipCalculator.Result r = c.result();
        long slipId = jdbc.sql("""
                insert into payslip (run_id, user_id, currency, gross, cnss_base, taxable_base, taxable_base_cdf,
                    employee_deductions, net, employer_charges, scheduled_minutes, paid_minutes, days_worked,
                    snapshot, warnings, unit_id, kind, corrects_id, reason, created_by, site_id, category_id, grade_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(runId, c.userId(), r.currency(), r.gross(), r.cnssBase(), r.taxableBase(), r.taxableBaseCdf(),
                        r.employeeDeductions(), r.net(), r.employerCharges(), c.scheduledMinutes(), c.paidMinutes(),
                        c.daysWorked(), json.writeValueAsString(c.snapshot()), json.writeValueAsString(c.warnings()),
                        c.unitId(), kind, correctsId, reason, CurrentUser.email(), id(c.snapshot().get("siteId")),
                        id(c.snapshot().get("categoryId")), id(c.snapshot().get("gradeId")))
                .query(Long.class).single();
        for (PayslipCalculator.Line l : r.lines()) {
            jdbc.sql("""
                    insert into payslip_line (payslip_id, element_code, label, kind, base, rate, amount, display_order, loan_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(slipId, l.code(), l.label(), l.kind(), l.base(), l.rate(), l.amount(), l.order(), l.loanId())
                    .update();
        }
        return slipId;
    }

    private static Long id(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    @Transactional
    public RunView calculate(YearMonth month) {
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        LocalDate today = LocalDate.now(clock);
        if (first.isAfter(today)) {
            throw new BusinessRuleException("The payroll for " + month + " cannot be calculated before the month starts.");
        }
        Run existing = run(month).orElse(null);
        if (existing != null && !existing.status().equals("CALCULATED")) {
            throw new BusinessRuleException("The payroll for " + month + " is " + existing.status().toLowerCase()
                    + (existing.status().equals("APPROVED") ? ": send it back before calculating again." : "."));
        }
        String local = localCurrency();
        // The rate: last BCC rate of the month, or today's, as configured.
        LocalDate rateDay = statutory.setting("RATE_DAY").equals("RUN_DATE") || last.isAfter(today) ? today : last;
        ExchangeRates.Rate usd = exchange.local(local, rateDay);
        PayslipCalculator.Rates rates = rates(last, usd.cdfPerUnit());
        boolean projected = !last.isBefore(today);

        long runId;
        if (existing == null) {
            runId = jdbc.sql("""
                    insert into payroll_run (month, status, usd_cdf, rate_date, rate_source, projected, calculated_by, calculated_at)
                    values (?, 'CALCULATED', ?, ?, ?, ?, ?, now()) returning id""")
                    .params(first, usd.cdfPerUnit(), usd.rateDate(), usd.source(), projected, CurrentUser.email())
                    .query(Long.class).single();
        } else {
            runId = existing.id();
            jdbc.sql("delete from payslip where run_id = ?").param(runId).update();
            checks.reset(runId);
            jdbc.sql("""
                    update payroll_run set usd_cdf = ?, rate_date = ?, rate_source = ?, projected = ?, calculated_by = ?,
                           calculated_at = now() where id = ?""")
                    .params(usd.cdfPerUnit(), usd.rateDate(), usd.source(), projected, CurrentUser.email(), runId).update();
        }

        MonthContext ctx = context(month, rates, usd.cdfPerUnit());
        List<String> skipped = new ArrayList<>();
        int count = 0;
        List<Long> employees = jdbc.sql("""
                select distinct c.user_id from employee_contract c join app_user u on u.id = c.user_id
                where c.start_date <= ? and (c.end_date is null or c.end_date >= ?)
                  and (u.status = 'ACTIVE' or exists (select 1 from departure d where d.user_id = u.id
                       and d.status = 'CONFIRMED' and d.last_day >= ?))
                order by c.user_id""").params(last, first, first).query(Long.class).list();
        int done = 0;
        try (var computed = computeAll(ctx, employees)) {
            for (Computed c : (Iterable<Computed>) computed::iterator) {
                done++;
                cd.hrpay.job.Progress.report(done, employees.size(),
                        cd.hrpay.i18n.Texts.t("Payslip %s of %s", done, employees.size()));
                if (c.skipped() != null) {
                    skipped.add(c.skipped());
                    continue;
                }
                store(runId, c, "REGULAR", null, null);
                count++;
                // The loop only reads entities (payslips are written with SQL). Letting thousands pile up in the session
                // makes every query check them all for changes: clear it as we go.
                if (count % 50 == 0) {
                    entities.flush();
                    entities.clear();
                }
            }
        }
        jdbc.sql("update payroll_run set employee_count = ? where id = ?").params(count, runId).update();
        audit.record("payroll.calculated", "payroll_run", runId, Map.of("month", month.toString(), "payslips", count,
                "usdCdf", usd.cdfPerUnit().toPlainString(), "projected", projected));
        return view(month, skipped);
    }

    /**
     * Each person's payslip worked out, in the given order. A large payroll is worked out by several threads at once
     * (app.payroll.threads), each reading in its own connections in the same company, language and rights; the payslips
     * are still written one by one in the calling transaction, as they come.
     */
    private java.util.stream.Stream<Computed> computeAll(MonthContext ctx, List<Long> employees) {
        if (threads <= 1 || employees.size() < parallelFrom || tenants == null) {
            return employees.stream().map(id -> compute(ctx, id, true));
        }
        Long company = cd.hrpay.tenancy.TenantContext.companyId();
        var security = SecurityContextHolder.getContext();
        String language = cd.hrpay.i18n.Texts.current();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads, Thread.ofPlatform().name("payroll-", 1).daemon().factory());
        // At most a few hundred payslips ahead of the writer, so memory stays small.
        var permits = new java.util.concurrent.Semaphore(threads * 50);
        List<java.util.concurrent.Future<Computed>> futures = new ArrayList<>(employees.size());
        Thread feeder = Thread.ofPlatform().daemon().name("payroll-feeder").start(() -> {
            for (long id : employees) {
                try {
                    permits.acquire();
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (futures) {
                    futures.add(pool.submit(() -> {
                        var before = SecurityContextHolder.getContext();
                        SecurityContextHolder.setContext(security);
                        try {
                            return tenants.callAs(company, () -> cd.hrpay.i18n.Texts.in(language, () -> compute(ctx, id, true)));
                        } finally {
                            SecurityContextHolder.setContext(before);
                        }
                    }));
                    futures.notifyAll();
                }
            }
        });
        return java.util.stream.IntStream.range(0, employees.size()).mapToObj(i -> {
            java.util.concurrent.Future<Computed> f;
            synchronized (futures) {
                while (futures.size() <= i) {
                    try {
                        futures.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted", e);
                    }
                }
                f = futures.get(i);
                futures.set(i, null);
            }
            try {
                return f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                feeder.interrupt();
                pool.shutdownNow();
                if (e.getCause() instanceof RuntimeException r) {
                    throw r;
                }
                throw new IllegalStateException(e.getCause());
            } finally {
                permits.release();
            }
        }).onClose(() -> {
            feeder.interrupt();
            pool.shutdownNow();
        });
    }

    // ---- approve, send back, close ---------------------------------------------------------------------------------

    @Transactional
    public RunView approve(YearMonth month) {
        Run run = require(month);
        if (!run.status().equals("CALCULATED")) {
            throw new BusinessRuleException("Only a calculated payroll can be approved.");
        }
        if (Objects.equals(run.calculatedBy(), CurrentUser.email())) {
            throw new AccessDeniedException("The person who calculated the payroll cannot also approve it.");
        }
        if (run.projected() && !month.atEndOfMonth().isBefore(LocalDate.now(clock))) {
            throw new BusinessRuleException("This payroll counts the rest of the month as worked. Calculate it again after "
                    + month.atEndOfMonth() + ", or once the attendance is final.");
        }
        checks.requireClear(month);
        jdbc.sql("update payroll_run set status = 'APPROVED', approved_by = ?, approved_at = now() where id = ?")
                .params(CurrentUser.email(), run.id()).update();
        audit.record("payroll.approved", "payroll_run", run.id(), Map.of("month", month.toString()));
        return view(month, List.of());
    }

    @Transactional
    public RunView sendBack(YearMonth month, String reason) {
        Run run = require(month);
        if (!run.status().equals("APPROVED")) {
            throw new BusinessRuleException("Only an approved payroll can be sent back.");
        }
        jdbc.sql("update payroll_run set status = 'CALCULATED', approved_by = null, approved_at = null where id = ?")
                .param(run.id()).update();
        audit.record("payroll.sent_back", "payroll_run", run.id(), Map.of("month", month.toString(), "reason", reason));
        return view(month, List.of());
    }

    @Transactional
    public RunView close(YearMonth month) {
        Run run = require(month);
        if (!run.status().equals("APPROVED")) {
            throw new BusinessRuleException("Only an approved payroll can be closed.");
        }
        Quinzaines.requireSettled(jdbc, month);
        Thirteenths.requireSettled(jdbc, month, run.calculatedAt());
        checks.requireClear(month);
        List<Long> slips = jdbc.sql("""
                select p.id from payslip p join app_user u on u.id = p.user_id where p.run_id = ? and p.kind = 'REGULAR'
                order by u.last_name, u.first_name, p.id""").param(run.id()).query(Long.class).list();
        int n = 0;
        for (long id : slips) {
            n++;
            cd.hrpay.job.Progress.report(n, slips.size() * 2L, cd.hrpay.i18n.Texts.t("Numbering payslip %s of %s", n, slips.size()));
            jdbc.sql("update payslip set number = ? where id = ?")
                    .params("PS-%s-%04d".formatted(month, n), id).update();
        }
        cd.hrpay.job.Progress.report(slips.size() * 3L, slips.size() * 4L, cd.hrpay.i18n.Texts.t("Loan repayments and leave credits"));
        // Loan repayments, converted back to each loan's currency at the run's rate.
        jdbc.sql("""
                select l.loan_id, sum(l.amount) as amount, trim(p.currency) as currency, trim(o.currency) as loan_currency
                from payslip_line l join payslip p on p.id = l.payslip_id join loan o on o.id = l.loan_id
                where p.run_id = ? and l.loan_id is not null group by l.loan_id, p.currency, o.currency""")
                .param(run.id())
                .query((rs, i) -> {
                    BigDecimal amount = ExchangeRates.convert(rs.getBigDecimal("amount"), rs.getString("currency"),
                            rs.getString("loan_currency"), run.usdCdf());
                    loans.recordRepayment(rs.getLong("loan_id"), run.id(), amount);
                    return null;
                }).list();
        // Annual leave earned this month, as the payslip shows it.
        jdbc.sql("""
                select p.user_id, (p.snapshot ->> 'leaveEarned')::numeric as days from payslip p
                where p.run_id = ? and p.kind = 'REGULAR' and p.snapshot ->> 'leaveEarned' is not null""")
                .param(run.id())
                .query((rs, i) -> {
                    leave.credit(rs.getLong(1), month, rs.getBigDecimal(2));
                    return null;
                }).list();
        carryQuinzaine(run, month);
        Thirteenths.markPaid(jdbc, month);
        jdbc.sql("update payroll_run set status = 'CLOSED', closed_by = ?, closed_at = now() where id = ?")
                .params(CurrentUser.email(), run.id()).update();
        audit.record("payroll.closed", "payroll_run", run.id(), Map.of("month", month.toString(), "payslips", n));
        return view(month, List.of());
    }

    /**
     * A quinzaine the payslip could not take back in full (the month's net was too small) becomes an advance repaid
     * from the next month, so the whole quinzaine is always taken back.
     */
    private void carryQuinzaine(Run run, YearMonth month) {
        carry(run, month, "QUINZAINE", "Quinzaine of %s not fully taken back", "quinzaine.carried");
        carry(run, month, SalaryAdvances.ELEMENT, "Salary advances of %s not fully taken back", "advance.carried");
    }

    private void carry(Run run, YearMonth month, String code, String note, String event) {
        record Shortfall(long userId, String currency, BigDecimal entry, String entryCurrency, BigDecimal taken) {}
        List<Shortfall> rows = jdbc.sql("""
                select p.user_id, trim(p.currency) as currency, e.amount as entry, trim(e.currency) as entry_currency,
                       coalesce((select sum(l.amount) from payslip_line l where l.payslip_id = p.id and l.element_code = x.code), 0) as taken
                from payslip p join pay_entry e on e.user_id = p.user_id and e.month = ?
                join pay_element x on x.id = e.element_id and x.code = ?
                where p.run_id = ? and p.kind = 'REGULAR'""").params(month.atDay(1), code, run.id()).query(Shortfall.class).list();
        for (Shortfall r : rows) {
            BigDecimal due = ExchangeRates.convert(r.entry(), r.entryCurrency(), r.currency(), run.usdCdf())
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal left = due.subtract(r.taken());
            if (left.signum() <= 0) {
                continue;
            }
            long id = jdbc.sql("""
                    insert into loan (user_id, kind, principal, currency, installment, start_month, granted_on, note, created_by)
                    values (?, 'ADVANCE', ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(r.userId(), left, r.currency(), left, month.plusMonths(1).atDay(1), month.atEndOfMonth(),
                            cd.hrpay.i18n.Texts.t(note, month.toString()), CurrentUser.email())
                    .query(Long.class).single();
            audit.record(event, "user", r.userId(), Map.of("loan", id, "amount", left.toPlainString(),
                    "month", month.toString()));
        }
    }

    /** The identifiers a payslip keeps are stored encrypted: read back as they were entered. */
    static final List<String> ENCRYPTED_KEYS = List.of("cnssNumber", "taxNumber", "bankAccount", "mobileMoneyNumber");

    private Map<String, Object> readable(Map<String, Object> snapshot) {
        for (String key : ENCRYPTED_KEYS) {
            if (snapshot.get(key) instanceof String v) {
                snapshot.put(key, crypto.decrypt(v));
            }
        }
        return snapshot;
    }

    // ---- payslips ----------------------------------------------------------------------------------------------

    /** A leave allowance payslip (bulletin d'allocation de congé) in the shape of a payslip, for its PDF. */
    @SuppressWarnings("unchecked")
    Payslip leaveSlip(long id, LocalDate month, boolean numbered, long userId, String number, String currency,
                      BigDecimal gross, BigDecimal cnssBase, BigDecimal taxableBase, BigDecimal taxableBaseCdf,
                      BigDecimal deductions, BigDecimal net, BigDecimal charges, BigDecimal usdCdf, LocalDate rateDate,
                      String snapshot, List<Line> lines, String reason) {
        Payslip head = new Payslip(id, 0, month, numbered ? "CLOSED" : "CALCULATED", userId, number, currency, gross,
                cnssBase, taxableBase, taxableBaseCdf, deductions, net, charges, 0, 0, 0, usdCdf, rateDate, false,
                readable(json.readValue(snapshot, Map.class)), List.of(), lines, "LEAVE", null, null, reason);
        return new Payslip(head.id(), 0, month, head.runStatus(), userId, number, currency, gross, cnssBase, taxableBase,
                taxableBaseCdf, deductions, net, charges, 0, 0, 0, usdCdf, rateDate, false, withSeniority(head), List.of(),
                lines, "LEAVE", null, null, reason);
    }

    @SuppressWarnings("unchecked")
    public Payslip payslip(long id) {
        Payslip head = jdbc.sql("""
                select p.id, p.run_id, r.month, r.status as run_status, p.user_id, p.number, trim(p.currency) as currency,
                       p.gross, p.cnss_base, p.taxable_base, p.taxable_base_cdf, p.employee_deductions, p.net,
                       p.employer_charges, p.scheduled_minutes, p.paid_minutes, p.days_worked, r.usd_cdf, r.rate_date,
                       r.projected, p.snapshot::text as snapshot, p.warnings::text as warnings, p.kind, p.corrects_id,
                       (select o.number from payslip o where o.id = p.corrects_id) as corrects_number, p.reason
                from payslip p join payroll_run r on r.id = p.run_id where p.id = ?""")
                .param(id)
                .query((rs, i) -> new Payslip(rs.getLong("id"), rs.getLong("run_id"), rs.getObject("month", LocalDate.class),
                        rs.getString("run_status"), rs.getLong("user_id"), rs.getString("number"), rs.getString("currency"),
                        rs.getBigDecimal("gross"), rs.getBigDecimal("cnss_base"), rs.getBigDecimal("taxable_base"),
                        rs.getBigDecimal("taxable_base_cdf"), rs.getBigDecimal("employee_deductions"),
                        rs.getBigDecimal("net"), rs.getBigDecimal("employer_charges"), rs.getInt("scheduled_minutes"),
                        rs.getInt("paid_minutes"), rs.getInt("days_worked"), rs.getBigDecimal("usd_cdf"),
                        rs.getObject("rate_date", LocalDate.class), rs.getBoolean("projected"),
                        readable(json.readValue(rs.getString("snapshot"), Map.class)),
                        json.readValue(rs.getString("warnings"), List.class), List.of(), rs.getString("kind"),
                        rs.getObject("corrects_id", Long.class), rs.getString("corrects_number"), rs.getString("reason")))
                .optional().orElseThrow(() -> new NotFoundException("Payslip not found."));
        List<Line> lines = jdbc.sql("""
                select element_code, label, kind, base, rate, amount, display_order, loan_id from payslip_line
                where payslip_id = ? order by display_order, id""").param(id).query(Line.class).list();
        return new Payslip(head.id(), head.runId(), head.month(), head.runStatus(), head.userId(), head.number(),
                head.currency(), head.gross(), head.cnssBase(), head.taxableBase(), head.taxableBaseCdf(),
                head.employeeDeductions(), head.net(), head.employerCharges(), head.scheduledMinutes(),
                head.paidMinutes(), head.daysWorked(), head.usdCdf(), head.rateDate(), head.projected(),
                withSeniority(head), head.warnings().stream().map(cd.hrpay.i18n.Texts::translate).toList(), lines,
                head.kind(), head.correctsId(), head.correctsNumber(), head.reason());
    }

    /** Marital status codes printed on payslips. */
    public static final Map<String, String> MARITAL_CODES = Map.of("SINGLE", "S", "MARRIED", "M", "DIVORCED", "D", "WIDOWED", "W");

    /**
     * Adds the marital status code and the seniority at the end of the payslip's month. Payslips made before these
     * were kept take the hire date and marital status from the employee's current file.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> withSeniority(Payslip p) {
        Map<String, Object> s = new LinkedHashMap<>(p.snapshot());
        if (!s.containsKey("hireDate") || !s.containsKey("maritalStatus")) {
            var user = users.findById(p.userId()).orElse(null);
            if (!s.containsKey("maritalStatus")) {
                s.put("maritalStatus", files.profile(p.userId()).maritalStatus());
            }
            if (!s.containsKey("hireDate") && user != null) {
                LocalDate hired = user.getHireDate() != null ? user.getHireDate()
                        : s.get("contractStart") == null ? null : LocalDate.parse(s.get("contractStart").toString());
                s.put("hireDate", hired == null ? null : hired.toString());
            }
        }
        Object marital = s.get("maritalStatus");
        s.put("maritalCode", marital == null ? null : MARITAL_CODES.getOrDefault(marital.toString(), marital.toString()));
        if (s.get("hireDate") != null) {
            LocalDate hired = LocalDate.parse(s.get("hireDate").toString());
            LocalDate end = p.month().withDayOfMonth(p.month().lengthOfMonth());
            java.time.Period served = hired.isAfter(end) ? java.time.Period.ZERO : java.time.Period.between(hired, end.plusDays(1));
            s.put("seniorityYears", served.getYears());
            s.put("seniorityMonths", served.getMonths());
            s.put("seniority", served.getYears() + " y " + served.getMonths() + " m");
        }
        return s;
    }

    /** An employee's own payslips: closed months only. */
    /** The month's payslips (regular and corrections), in the order of the payroll: by name. */
    public List<Long> payslipIds(YearMonth month) {
        return jdbc.sql("""
                select p.id from payslip p join payroll_run r on r.id = p.run_id join app_user u on u.id = p.user_id
                where r.month = ? order by u.last_name, u.first_name, p.id""").param(month.atDay(1)).query(Long.class).list();
    }

    public List<PayslipSummary> closedPayslipsOf(long userId) {
        return jdbc.sql("""
                select p.id, p.user_id, person_name(u.first_name, u.middle_name, u.last_name) as user_name, u.registration_no, p.number,
                       trim(p.currency) as currency, p.gross, p.employee_deductions, p.net, p.employer_charges, 0 as warning_count,
                       p.unit_id, null as unit_name, null::bigint as direction_id, null as direction_name, p.kind, p.corrects_id
                from payslip p join payroll_run r on r.id = p.run_id join app_user u on u.id = p.user_id
                where p.user_id = ? and r.status = 'CLOSED' and p.number is not null order by r.month desc, p.id desc""").param(userId)
                .query(PayslipSummary.class).list();
    }
}
