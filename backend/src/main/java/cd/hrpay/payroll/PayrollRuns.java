package cd.hrpay.payroll;

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
    private final cd.hrpay.evaluation.ThirteenthMonth thirteenthMonth;
    private final AuditService audit;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entities;
    private cd.hrpay.org.OrgStructure org;

    @org.springframework.beans.factory.annotation.Autowired
    void setOrg(cd.hrpay.org.OrgStructure org) {
        this.org = org;
    }

    public PayrollRuns(JdbcClient jdbc, UserRepository users, AttendanceDays attendance, SalaryService salaries,
                       EmployeeFile files, PayElements pay, StatutoryRates statutory, ExchangeRates exchange, Loans loans,
                       cd.hrpay.evaluation.ThirteenthMonth thirteenthMonth, AuditService audit, Clock clock, cd.hrpay.security.DataCrypto crypto) {
        this.thirteenthMonth = thirteenthMonth;
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
                select p.id, p.user_id, u.first_name || ' ' || u.last_name as user_name, u.registration_no, p.number,
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
        return new PayslipCalculator.Rates(usdLocal, statutory.rate("CNSS_EMPLOYEE", on), statutory.rate("CNSS_EMPLOYER", on),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, statutory.rate("DEBT_MAX_SHARE", on),
                statutory.brackets(on).brackets(), BigDecimal.ZERO, 0, BigDecimal.valueOf(100), null, "CG", local, Map.copyOf(more));
    }

    String localCurrency() {
        return jdbc.sql("select local_currency from company where id = current_company_id()").query(String.class).single();
    }

    /** The month's rates, at the given price of a dollar in the local currency. */
    PayslipCalculator.Rates rates(LocalDate last, BigDecimal usdLocal) {
        String country = jdbc.sql("select country_code from company where id = current_company_id()").query(String.class).single();
        return "CG".equals(country)
                ? congoRates(usdLocal, last, localCurrency())
                : new PayslipCalculator.Rates(usdLocal,
                        statutory.rate("CNSS_EMPLOYEE", last), statutory.rate("CNSS_EMPLOYER", last), statutory.inppRate(last),
                        statutory.rate("ONEM", last), statutory.rate("IERE", last), statutory.rate("DEBT_MAX_SHARE", last),
                        statutory.brackets(last).brackets(), statutory.rate("IPR_DEPENDANT_REDUCTION", last),
                        statutory.rate("IPR_MAX_DEPENDANTS", last).intValue(), statutory.rate("IPR_CAP", last),
                        statutory.rate("SMIG_DAILY", last));
    }

    /** What working out the payslips of one month needs, gathered once. */
    record MonthContext(YearMonth month, LocalDate first, LocalDate last, LocalDate today, PayslipCalculator.Rates rates,
                        BigDecimal usdLocal, List<PayElements.Element> elements, boolean payThirteenth,
                        Map<Long, cd.hrpay.org.OrgStructure.Placement> placements,
                        Map<Long, cd.hrpay.org.OrgStructure.Placement> nowPlaced) {}

    MonthContext context(YearMonth month, PayslipCalculator.Rates rates, BigDecimal usdLocal) {
        LocalDate today = LocalDate.now(clock);
        var t13 = thirteenthMonth.settings();
        return new MonthContext(month, month.atDay(1), month.atEndOfMonth(), today, rates, usdLocal, pay.elements(),
                t13.enabled() && month.getMonthValue() == t13.payMonth(), org.placementsOn(month.atEndOfMonth()),
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

    /**
     * Works out one person's payslip for the context's month from today's data. {@code withLoans} false leaves loan
     * repayments out (a correction of a closed month: its repayments are already recorded).
     */
    Computed compute(MonthContext ctx, long userId, boolean withLoans) {
        return compute(ctx, userId, withLoans, List.of());
    }

    /** The same, with one-off lines that are not stored as pay entries (a correction's additions). */
    Computed compute(MonthContext ctx, long userId, boolean withLoans, List<PayElements.Entry> extra) {
        YearMonth month = ctx.month();
        LocalDate first = ctx.first();
        LocalDate last = ctx.last();
        LocalDate today = ctx.today();
        BigDecimal usdLocal = ctx.usdLocal();
        AppUser user = users.findById(userId).orElseThrow();
        String name = user.getFirstName() + " " + user.getLastName();
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
        LocalDate leaves = lastDayIn(userId, month).orElse(null);
        var summary = attendance.month(user, month, true);
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
        BigDecimal overtime = summary.earnings() == null ? BigDecimal.ZERO
                : ExchangeRates.convert(summary.earnings().overtimeEarned(), summary.earnings().currency(), salary.currency(), usdLocal);
        List<EmployeeFile.Dependant> dependants = files.dependants(userId);
        int children = (int) dependants.stream().filter(d -> d.inCharge() && d.relationship().equals("CHILD")).count();
        int inCharge = (int) dependants.stream().filter(EmployeeFile.Dependant::inCharge).count();
        var thirteenth = ctx.payThirteenth() ? thirteenthMonth.forEmployee(userId, name, month.getYear()).orElse(null) : null;
        List<PayslipCalculator.LoanDue> loansDue = !withLoans ? List.of() : leaves == null ? loans.dueFor(userId, month)
                // On the last payslip, what is left of each loan is due.
                : loans.dueFor(userId, month).stream().map(l -> new PayslipCalculator.LoanDue(l.loanId(), l.kind(),
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
        PayslipCalculator.Result r = PayslipCalculator.calculate(new PayslipCalculator.Input(salary.currency(),
                salary.grossMonthly(), summary.scheduledMinutesMonth(), paid, scheduledDays, daysWorked, overtime,
                summary.overtimeApprovedMinutes(), thirteenth == null ? null
                        : ExchangeRates.convert(thirteenth.amount(), thirteenth.currency(), salary.currency(), usdLocal),
                thirteenth == null ? null : thirteenthNote(thirteenth), children, inCharge, contract.expatriate(), ctx.elements(),
                pay.standingFor(userId, month),
                java.util.stream.Stream.concat(pay.entriesFor(userId, month).stream(), extra.stream()).toList(), loansDue, ctx.rates(),
                allocatedMinutes));
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
        snapshot.put("occupation", contract.occupationName());
        snapshot.put("expatriate", contract.expatriate());
        snapshot.put("basicMonthly", salary.grossMonthly());
        snapshot.put("childrenInCharge", children);
        snapshot.put("maritalStatus", profile.maritalStatus());
        LocalDate hired = user.getHireDate() != null ? user.getHireDate() : contract.startDate();
        snapshot.put("hireDate", hired == null ? null : hired.toString());
        snapshot.put("dependantsInCharge", inCharge);
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
                    snapshot, warnings, unit_id, kind, corrects_id, reason, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?, ?, ?, ?) returning id""")
                .params(runId, c.userId(), r.currency(), r.gross(), r.cnssBase(), r.taxableBase(), r.taxableBaseCdf(),
                        r.employeeDeductions(), r.net(), r.employerCharges(), c.scheduledMinutes(), c.paidMinutes(),
                        c.daysWorked(), json.writeValueAsString(c.snapshot()), json.writeValueAsString(c.warnings()),
                        c.unitId(), kind, correctsId, reason, CurrentUser.email())
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
        for (long userId : employees) {
            Computed c = compute(ctx, userId, true);
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
        jdbc.sql("update payroll_run set employee_count = ? where id = ?").params(count, runId).update();
        audit.record("payroll.calculated", "payroll_run", runId, Map.of("month", month.toString(), "payslips", count,
                "usdCdf", usd.cdfPerUnit().toPlainString(), "projected", projected));
        return view(month, skipped);
    }

    private static String thirteenthNote(cd.hrpay.evaluation.ThirteenthMonth.Row r) {
        String score = r.scorePercent().stripTrailingZeros().toPlainString() + "%";
        return (r.scoreSource().equals("EVALUATIONS") ? "evaluations " + score : "no evaluation, " + score)
                + (r.monthsWorked() < 12 ? ", " + r.monthsWorked() + "/12 months" : "");
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
        List<Long> slips = jdbc.sql("""
                select p.id from payslip p join app_user u on u.id = p.user_id where p.run_id = ? and p.kind = 'REGULAR'
                order by u.last_name, u.first_name, p.id""").param(run.id()).query(Long.class).list();
        int n = 0;
        for (long id : slips) {
            n++;
            jdbc.sql("update payslip set number = ? where id = ?")
                    .params("PS-%s-%04d".formatted(month, n), id).update();
        }
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
        jdbc.sql("update payroll_run set status = 'CLOSED', closed_by = ?, closed_at = now() where id = ?")
                .params(CurrentUser.email(), run.id()).update();
        audit.record("payroll.closed", "payroll_run", run.id(), Map.of("month", month.toString(), "payslips", n));
        return view(month, List.of());
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
    public List<PayslipSummary> closedPayslipsOf(long userId) {
        return jdbc.sql("""
                select p.id, p.user_id, u.first_name || ' ' || u.last_name as user_name, u.registration_no, p.number,
                       trim(p.currency) as currency, p.gross, p.employee_deductions, p.net, p.employer_charges, 0 as warning_count,
                       p.unit_id, null as unit_name, null::bigint as direction_id, null as direction_name, p.kind, p.corrects_id
                from payslip p join payroll_run r on r.id = p.run_id join app_user u on u.id = p.user_id
                where p.user_id = ? and r.status = 'CLOSED' and p.number is not null order by r.month desc, p.id desc""").param(userId)
                .query(PayslipSummary.class).list();
    }
}
