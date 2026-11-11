package cd.hrpay.payroll;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.payment.Company;
import cd.hrpay.salary.SalaryService;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfWriter;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A departure and its final settlement (décompte final): notice pay when the notice is not worked, unused annual
 * leave paid out, severance where the reason gives one, and the share of the 13th month. Confirming it writes these
 * as pay entries of the month of the last day and ends the contract on that day: the payroll of that month is then
 * the last payslip, which also takes what is left of the person's loans. The rules are rates on the Rates page.
 */
@Service
public class Departures {

    public static final Set<String> REASONS = Set.of("RESIGNATION", "DISMISSAL", "GROSS_MISCONDUCT", "REDUNDANCY",
            "END_OF_CONTRACT", "END_OF_TRIAL", "MUTUAL_AGREEMENT", "RETIREMENT", "DEATH");

    /** The reasons that give severance pay, when the rate is set. */
    static final Set<String> SEVERANCE_REASONS = Set.of("DISMISSAL", "REDUNDANCY", "RETIREMENT");

    /** HR's choices; the amounts are worked out from them unless HR gives its own. */
    public record Input(LocalDate lastDay, String reason, LocalDate noticeDate, String noticeGivenBy, String notice,
                        BigDecimal noticeDays, BigDecimal leaveDays, BigDecimal severanceAmount,
                        BigDecimal thirteenthAmount, String note) {}

    /**
     * A grade category's notice: a base (days or months), days added per full year of service, whether days are
     * working days (every day but Sunday and public holidays) or calendar days, and the share of it a person who
     * resigns owes.
     */
    public record NoticeRule(String category, BigDecimal base, String baseUnit, BigDecimal daysPerYear, String dayKind,
                             BigDecimal resignationShare) {}

    public record Departure(Long id, long userId, String name, String registrationNo, LocalDate hireDate,
                            LocalDate lastDay, String reason, LocalDate noticeDate, String noticeGivenBy,
                            LocalDate noticeEnd, String notice, String currency, BigDecimal basicMonthly,
                            BigDecimal dailyRate, BigDecimal serviceYears, BigDecimal noticeDays, BigDecimal noticeAmount,
                            BigDecimal noticeOwed,
                            BigDecimal leaveDays, BigDecimal leaveAmount, BigDecimal severanceAmount,
                            BigDecimal endOfCareerAmount, BigDecimal thirteenthAmount, BigDecimal total, BigDecimal loanBalance, String note,
                            String status, String createdBy, String confirmedBy, OffsetDateTime confirmedAt,
                            String certificateNo, String monthStatus, List<String> explanation) {}

    public record Summary(long id, long userId, String name, String registrationNo, LocalDate lastDay, String reason,
                          String status, String currency, BigDecimal total) {}

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final SalaryService salaries;
    private final EmployeeFile files;
    private final StatutoryRates statutory;
    private final Loans loans;
    private final cd.hrpay.evaluation.ThirteenthMonth thirteenthMonth;
    private final Company company;
    private final cd.hrpay.payment.Letterhead letterhead;
    private final AuditService audit;
    private final Clock clock;
    private cd.hrpay.representation.StaffRepresentation representation;

    @org.springframework.beans.factory.annotation.Autowired
    void setRepresentation(@org.springframework.context.annotation.Lazy cd.hrpay.representation.StaffRepresentation representation) {
        this.representation = representation;
    }

    /** The reasons by which the employer ends the contract: a staff delegate's needs the inspector's authorisation. */
    static final Set<String> EMPLOYER_ENDS = Set.of("DISMISSAL", "GROSS_MISCONDUCT", "REDUNDANCY");

    public Departures(JdbcClient jdbc, UserRepository users, SalaryService salaries, EmployeeFile files,
                      StatutoryRates statutory, Loans loans, cd.hrpay.evaluation.ThirteenthMonth thirteenthMonth,
                      Company company, cd.hrpay.payment.Letterhead letterhead, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.salaries = salaries;
        this.files = files;
        this.statutory = statutory;
        this.loans = loans;
        this.thirteenthMonth = thirteenthMonth;
        this.company = company;
        this.letterhead = letterhead;
        this.audit = audit;
        this.clock = clock;
    }

    private AppUser user(long userId) {
        return users.findById(userId).orElseThrow(() -> new NotFoundException("User not found."));
    }

    private LocalDate hired(AppUser u) {
        if (u.getHireDate() != null) {
            return u.getHireDate();
        }
        return files.contracts(u.getId()).stream().map(EmployeeFile.Contract::startDate).min(LocalDate::compareTo).orElse(null);
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** The settlement as the rules give it for these choices, with how each amount was found. */
    public Departure propose(long userId, Input in) {
        if (in.lastDay() == null || in.reason() == null || !REASONS.contains(in.reason())) {
            throw new BusinessRuleException("Give the last day and the reason for leaving.");
        }
        AppUser u = user(userId);
        LocalDate hiredOn = hired(u);
        if (hiredOn != null && in.lastDay().isBefore(hiredOn)) {
            throw new BusinessRuleException("The last day is before the hire date.");
        }
        // The agreed salary: the basic and the negotiated complement.
        var salary = salaries.agreed(userId, in.lastDay()).orElseThrow(
                () -> new BusinessRuleException("There is no basic salary on the last day: record it first."));
        LocalDate on = in.lastDay();
        List<String> why = new ArrayList<>();
        BigDecimal perMonth = statutory.rate("SETTLEMENT_DAYS_PER_MONTH", on);
        BigDecimal daily = salary.grossMonthly().divide(perMonth, 4, RoundingMode.HALF_UP);
        why.add(Texts.t("Daily rate: monthly salary %s %s ÷ %s days = %s.", salary.grossMonthly().toPlainString(),
                salary.currency(), perMonth.stripTrailingZeros().toPlainString(), money(daily).toPlainString()));

        Period served = hiredOn == null ? Period.ZERO : Period.between(hiredOn, on.plusDays(1));
        BigDecimal years = BigDecimal.valueOf(served.getYears())
                .add(BigDecimal.valueOf(served.getMonths()).divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP));
        why.add(Texts.t("Service: %s years and %s months since %s.", served.getYears(), served.getMonths(),
                hiredOn == null ? "?" : Texts.day(hiredOn)));

        Notice n = notice(u, in, hiredOn, why);
        String notice = n.mode();
        BigDecimal noticeDays = n.unobserved();
        BigDecimal noticeAmount = "PAID".equals(notice) ? money(daily.multiply(noticeDays)) : BigDecimal.ZERO.setScale(2);
        BigDecimal noticeOwed = "OWED".equals(notice) ? money(daily.multiply(noticeDays)) : BigDecimal.ZERO.setScale(2);

        BigDecimal leaveDays = in.leaveDays();
        if (leaveDays == null) {
            leaveDays = leaveLeft(userId, on, why);
        }
        BigDecimal leaveAmount = money(daily.multiply(leaveDays));
        boolean leavePaid = Boolean.parseBoolean(jdbc.sql("select value from payroll_setting where key = 'LEAVE_ANNUAL_PAID'")
                .query(String.class).optional().orElse("true"));
        if (!leavePaid && leaveDays.signum() > 0) {
            leaveAmount = BigDecimal.ZERO.setScale(2);
            why.add(Texts.t("Annual leave is not paid in this company (Leave settings): the days left are not paid out."));
        }

        BigDecimal severance = in.severanceAmount();
        if (severance == null) {
            BigDecimal perYear = statutory.rate("SEVERANCE_DAYS_PER_YEAR", on);
            severance = SEVERANCE_REASONS.contains(in.reason()) ? money(daily.multiply(perYear).multiply(years)) : BigDecimal.ZERO.setScale(2);
            if (SEVERANCE_REASONS.contains(in.reason())) {
                why.add(perYear.signum() == 0 ? Texts.t("Severance: no rate is set on the Rates page (law or collective agreement).")
                        : Texts.t("Severance: %s days per year × %s years.", perYear.stripTrailingZeros().toPlainString(), years.toPlainString()));
            }
        }

        // End-of-career payment of a collective agreement, on retirement: days of pay per year of service, up to a ceiling.
        BigDecimal endOfCareer = BigDecimal.ZERO.setScale(2);
        if ("RETIREMENT".equals(in.reason())) {
            BigDecimal careerDays = payrollSetting("END_OF_CAREER_DAYS_PER_YEAR");
            if (careerDays.signum() > 0) {
                endOfCareer = money(daily.multiply(careerDays).multiply(years));
                BigDecimal maxMonths = payrollSetting("END_OF_CAREER_MAX_MONTHS");
                if (maxMonths.signum() > 0) {
                    endOfCareer = endOfCareer.min(money(salary.grossMonthly().multiply(maxMonths)));
                }
                why.add(Texts.t("End-of-career payment (agreement): %s days per year × %s years%s.",
                        careerDays.stripTrailingZeros().toPlainString(), years.toPlainString(),
                        maxMonths.signum() > 0 ? Texts.t(", at most %s months", maxMonths.stripTrailingZeros().toPlainString()) : ""));
            }
        }

        BigDecimal thirteenth = in.thirteenthAmount();
        if (thirteenth == null) {
            thirteenth = BigDecimal.ZERO.setScale(2);
            var t13 = thirteenthMonth.settings();
            if (t13.enabled() && on.getMonthValue() < t13.payMonth()) {
                var row = thirteenthMonth.forEmployee(userId, u.fullName(), on.getYear()).orElse(null);
                if (row != null && row.currency().equals(salary.currency())) {
                    thirteenth = money(row.amount());
                    why.add(Texts.t("13th month for the months worked this year: %s.", thirteenth.toPlainString()));
                }
            }
        }

        BigDecimal loanBalance = BigDecimal.ZERO;
        for (var l : loans.dueFor(userId, YearMonth.from(on))) {
            if (l.currency().equals(salary.currency())) {
                loanBalance = loanBalance.add(l.balance());
            } else {
                why.add(Texts.t("Loan #%s: %s %s still due.", l.loanId(), money(l.balance()).toPlainString(), l.currency()));
            }
        }
        if (loanBalance.signum() > 0) {
            why.add(Texts.t("Loans and advances still due (%s %s) are taken from the last payslip, within the allowed share of net pay.",
                    money(loanBalance).toPlainString(), salary.currency()));
        }
        BigDecimal total = noticeAmount.add(leaveAmount).add(severance).add(endOfCareer).add(thirteenth).subtract(noticeOwed);
        if ("CD".equals(company.country())) {
            why.add(Texts.t("Pay the settlement within 2 working days of the last day (art. 100), and give the certificate of employment within 2 working days (art. 79)."));
        }
        Departure saved = find(userId).orElse(null);
        return new Departure(saved == null ? null : saved.id(), userId, u.fullName(),
                u.getRegistrationNo(), hiredOn, on, in.reason(), in.noticeDate(), n.givenBy(), n.end(), notice,
                salary.currency(), salary.grossMonthly(), daily, years, noticeDays, noticeAmount, noticeOwed, leaveDays,
                leaveAmount, severance, endOfCareer, thirteenth, total, money(loanBalance),
                in.note(), saved == null ? "DRAFT" : saved.status(), saved == null ? null : saved.createdBy(),
                saved == null ? null : saved.confirmedBy(), saved == null ? null : saved.confirmedAt(),
                saved == null ? null : saved.certificateNo(), monthStatus(YearMonth.from(on)), why);
    }

    /** Who gives notice for a reason: the employer when it ends the contract, the employee when they resign. */
    static String defaultGivenBy(String reason) {
        return switch (reason) {
            case "DISMISSAL", "REDUNDANCY", "RETIREMENT" -> "EMPLOYER";
            case "RESIGNATION" -> "EMPLOYEE";
            default -> null;
        };
    }

    /** The notice worked out: who gave it, when it ends, how it was settled, and the days not observed. */
    record Notice(String givenBy, LocalDate end, String mode, BigDecimal unobserved) {}

    public NoticeRule rule(AppUser u) {
        if (u.getGradeCategoryId() != null) {
            var r = jdbc.sql("""
                    select name as category, notice_base as base, notice_base_unit as base_unit, notice_days_per_year as days_per_year,
                           notice_day_kind as day_kind, notice_resignation_share as resignation_share
                    from grade_category where id = ?""").param(u.getGradeCategoryId()).query(NoticeRule.class).optional();
            if (r.isPresent()) {
                return r.get();
            }
        }
        boolean cg = !"CD".equals(company.country());   // elsewhere a month, as in the packs
        return new NoticeRule(null, cg ? BigDecimal.ONE : BigDecimal.valueOf(14), cg ? "MONTHS" : "DAYS",
                cg ? BigDecimal.ZERO : BigDecimal.valueOf(7), cg ? "CALENDAR" : "WORKING", cg ? BigDecimal.ONE : new BigDecimal("0.5"));
    }

    private Notice notice(AppUser u, Input in, LocalDate hiredOn, List<String> why) {
        String givenBy = in.noticeGivenBy() != null ? in.noticeGivenBy() : defaultGivenBy(in.reason());
        if ("END_OF_TRIAL".equals(in.reason())) {
            return trialNotice(u, in, givenBy == null ? "EMPLOYER" : givenBy, why);
        }
        if (givenBy == null || "GROSS_MISCONDUCT".equals(in.reason())) {
            String mode = in.notice() != null && !"OWED".equals(in.notice()) && !"PAID".equals(in.notice()) ? in.notice() : "NONE";
            why.add(Texts.t("No notice is due for this reason."));
            return new Notice(null, null, mode, BigDecimal.ZERO);
        }
        if (in.noticeDate() != null && in.noticeDate().isAfter(in.lastDay())) {
            throw new BusinessRuleException("Notice is given on or before the last day.");
        }
        NoticeRule r = rule(u);
        boolean working = "WORKING".equals(r.dayKind());
        LocalDate from = in.noticeDate() != null ? in.noticeDate() : in.lastDay();
        int years = hiredOn == null ? 0 : Period.between(hiredOn, from).getYears();
        LocalDate start = from.plusDays(1);
        Set<LocalDate> holidays = working ? holidays(start, start.plusYears(2)) : Set.of();
        // The employer's notice: the base, then the days per full year of service.
        LocalDate end = "MONTHS".equals(r.baseUnit())
                ? start.plusMonths(r.base().intValue()).minusDays(1)
                : addDays(start.minusDays(1), r.base().intValue(), working, holidays);
        end = addDays(end, r.daysPerYear().multiply(BigDecimal.valueOf(years)).setScale(0, RoundingMode.CEILING).intValue(), working, holidays);
        int length = countDays(start, end, working, holidays);
        String unit = working ? Texts.t("working days") : Texts.t("calendar days");
        String base = "MONTHS".equals(r.baseUnit()) ? Texts.t("%s month(s)", r.base().stripTrailingZeros().toPlainString())
                : r.base().stripTrailingZeros().toPlainString() + " " + unit;
        why.add(Texts.t("Notice for %s: %s plus %s %s per full year of service × %s years = %s %s.",
                r.category() == null ? Texts.t("the legal minimum (no grade category)") : r.category(), base,
                r.daysPerYear().stripTrailingZeros().toPlainString(), unit, years, length, unit));
        if ("EMPLOYEE".equals(givenBy) && r.resignationShare().compareTo(BigDecimal.ONE) < 0) {
            length = BigDecimal.valueOf(length).multiply(r.resignationShare()).setScale(0, RoundingMode.CEILING).intValue();
            end = addDays(start.minusDays(1), length, working, holidays);
            why.add(Texts.t("The employee resigns: they owe %s of it, %s %s.",
                    r.resignationShare().multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + " %", length, unit));
        }
        int unobserved;
        if (in.noticeDate() == null) {
            unobserved = length;
            end = addDays(in.lastDay(), length, working, holidays);
        } else {
            why.add(Texts.t("Notice given on %s: it runs from %s to %s.", Texts.day(in.noticeDate()), Texts.day(start), Texts.day(end)));
            unobserved = in.lastDay().isBefore(end) ? countDays(in.lastDay().plusDays(1), end, working, holidays) : 0;
            if (onLeave(u.getId(), in.noticeDate())) {
                why.add(Texts.t("Warning: the person was on approved leave on the day notice was given. Notice cannot be given during leave or a suspension: give it again after the leave."));
            }
        }
        String mode = in.notice() != null ? in.notice()
                : unobserved == 0 ? "WORKED" : "EMPLOYEE".equals(givenBy) ? "OWED" : "PAID";
        BigDecimal days = in.noticeDays() != null && ("PAID".equals(mode) || "OWED".equals(mode)) ? in.noticeDays()
                : "PAID".equals(mode) || "OWED".equals(mode) ? BigDecimal.valueOf(unobserved) : BigDecimal.ZERO;
        why.add(switch (mode) {
            case "PAID" -> Texts.t("Notice not worked, paid by the employer: %s %s.", days.stripTrailingZeros().toPlainString(), unit);
            case "OWED" -> Texts.t("Notice not given or not worked by the employee: %s %s, taken from the settlement.",
                    days.stripTrailingZeros().toPlainString(), unit);
            case "WORKED" -> Texts.t("Notice worked: paid as normal working days, nothing more.");
            default -> Texts.t("The notice is waived: nothing is paid or owed for it.");
        });
        BigDecimal search = statutory.rateOrZero("JOB_SEARCH_DAYS_PER_WEEK", in.lastDay());
        if (search.signum() > 0 && !"NONE".equals(mode)) {
            why.add(Texts.t("During the notice, the person may take %s paid day(s) a week to look for a job.",
                    search.stripTrailingZeros().toPlainString()));
        }
        return new Notice(givenBy, end, mode, days);
    }

    /**
     * Ending a trial: in DRC either party gives 3 working days' notice, none in the first 3 days of the trial (Labour
     * Code art. 71); elsewhere the company's setting (none by default, Congo art. 38).
     */
    private Notice trialNotice(AppUser u, Input in, String givenBy, List<String> why) {
        int length = payrollSetting("TRIAL_END_NOTICE_DAYS").intValue();
        LocalDate given = in.noticeDate() != null ? in.noticeDate() : in.lastDay();
        if (given.isAfter(in.lastDay())) {
            throw new BusinessRuleException("Notice is given on or before the last day.");
        }
        LocalDate start = files.contractOn(u.getId(), in.lastDay()).map(EmployeeFile.Contract::startDate).orElse(null);
        if (length <= 0 || (start != null && given.isBefore(start.plusDays(3)))) {
            why.add(length <= 0 ? Texts.t("Ending a trial: no notice is due (company setting).")
                    : Texts.t("Ending a trial in its first 3 days: no notice is due (Labour Code art. 71)."));
            return new Notice(givenBy, null, "NONE", BigDecimal.ZERO);
        }
        Set<LocalDate> holidays = holidays(given, given.plusMonths(2));
        LocalDate end = addDays(given, length, true, holidays);
        int unobserved = in.lastDay().isBefore(end) ? countDays(in.lastDay().plusDays(1), end, true, holidays) : 0;
        why.add(Texts.t("Ending a trial: %d working days' notice from %s, until %s (Labour Code art. 71).", length,
                Texts.day(given), Texts.day(end)));
        String mode = in.notice() != null ? in.notice()
                : unobserved == 0 ? "WORKED" : "EMPLOYEE".equals(givenBy) ? "OWED" : "PAID";
        BigDecimal days = "PAID".equals(mode) || "OWED".equals(mode) ? BigDecimal.valueOf(unobserved) : BigDecimal.ZERO;
        if ("PAID".equals(mode)) {
            why.add(Texts.t("Notice not worked, paid by the employer: %s %s.", days.toPlainString(), Texts.t("working days")));
        } else if ("OWED".equals(mode)) {
            why.add(Texts.t("Notice not given or not worked by the employee: %s %s, taken from the settlement.",
                    days.toPlainString(), Texts.t("working days")));
        }
        return new Notice(givenBy, end, mode, days);
    }

    private Set<LocalDate> holidays(LocalDate from, LocalDate to) {
        return new java.util.HashSet<>(jdbc.sql("select observed_date from holiday_occurrence where observed_date between ? and ?")
                .params(from, to).query(LocalDate.class).list());
    }

    private static boolean counts(LocalDate d, boolean working, Set<LocalDate> holidays) {
        return !working || (d.getDayOfWeek() != java.time.DayOfWeek.SUNDAY && !holidays.contains(d));
    }

    /** The day on which n more counted days have passed after {@code after}. */
    static LocalDate addDays(LocalDate after, int n, boolean working, Set<LocalDate> holidays) {
        LocalDate d = after;
        for (int counted = 0; counted < n; ) {
            d = d.plusDays(1);
            if (counts(d, working, holidays)) {
                counted++;
            }
        }
        return d;
    }

    static int countDays(LocalDate from, LocalDate to, boolean working, Set<LocalDate> holidays) {
        int n = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (counts(d, working, holidays)) {
                n++;
            }
        }
        return n;
    }

    private boolean onLeave(long userId, LocalDate day) {
        return jdbc.sql("select count(*) from leave_request where user_id = ? and status = 'APPROVED' and start_date <= ? and end_date >= ?")
                .params(userId, day, day).query(Integer.class).single() > 0;
    }

    /**
     * Annual leave left on the last day: the balance, less the part of this year's grant not yet earned (the grant
     * is given for the whole year at its start).
     */
    private BigDecimal leaveLeft(long userId, LocalDate on, List<String> why) {
        BigDecimal balance = jdbc.sql("""
                select coalesce(sum(l.days), 0) from leave_ledger l join leave_type t on t.id = l.leave_type_id
                where t.code = 'ANNUAL' and l.user_id = ?""").param(userId).query(BigDecimal.class).single();
        BigDecimal grant = jdbc.sql("""
                select coalesce(sum(l.days), 0) from leave_ledger l join leave_type t on t.id = l.leave_type_id
                where t.code = 'ANNUAL' and l.user_id = ? and l.kind = 'GRANT' and l.year = ?""")
                .params(userId, on.getYear()).query(BigDecimal.class).single();
        BigDecimal unearned = grant.multiply(BigDecimal.valueOf(12 - on.getMonthValue()))
                .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
        BigDecimal left = balance.subtract(unearned).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        why.add(unearned.signum() > 0
                ? Texts.t("Annual leave left: balance %s days, less %s days of this year's grant not earned after the last day = %s days.",
                        balance.stripTrailingZeros().toPlainString(), unearned.stripTrailingZeros().toPlainString(), left.stripTrailingZeros().toPlainString())
                : Texts.t("Annual leave left: %s days.", left.stripTrailingZeros().toPlainString()));
        return left;
    }

    private String monthStatus(YearMonth month) {
        return jdbc.sql("select status from payroll_run where month = ?").param(month.atDay(1)).query(String.class)
                .optional().orElse("OPEN");
    }

    private record Row(long id, long userId, LocalDate lastDay, String reason, LocalDate noticeDate, String noticeGivenBy,
                       LocalDate noticeEnd, String notice, BigDecimal noticeDays,
                       BigDecimal leaveDays, BigDecimal severanceAmount, BigDecimal endOfCareerAmount,
                       BigDecimal thirteenthAmount, String note,
                       String status, String createdBy, String confirmedBy, OffsetDateTime confirmedAt,
                       String certificateNo, LocalDate contractEndBefore) {}

    private Optional<Row> row(long userId) {
        return jdbc.sql("""
                select id, user_id, last_day, reason, notice_date, notice_given_by, notice_end, notice, notice_days, leave_days,
                       severance_amount, end_of_career_amount, thirteenth_amount,
                       note, status, created_by, confirmed_by, confirmed_at, certificate_no, contract_end_before
                from departure where user_id = ?""").param(userId).query(Row.class).optional();
    }

    /** The saved departure, with its amounts as saved. */
    public Optional<Departure> find(long userId) {
        return row(userId).map(r -> {
            AppUser u = user(userId);
            var d = jdbc.sql("""
                    select currency, basic_monthly, daily_rate, service_years, notice_amount, leave_amount, notice_owed
                    from departure where id = ?""").param(r.id())
                    .query((rs, i) -> new Object[] {rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                            rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7)}).single();
            BigDecimal total = ((BigDecimal) d[4]).add((BigDecimal) d[5]).add(r.severanceAmount()).add(r.endOfCareerAmount())
                    .add(r.thirteenthAmount())
                    .subtract((BigDecimal) d[6]);
            return new Departure(r.id(), userId, u.fullName(), u.getRegistrationNo(), hired(u),
                    r.lastDay(), r.reason(), r.noticeDate(), r.noticeGivenBy(), r.noticeEnd(), r.notice(), (String) d[0],
                    (BigDecimal) d[1], (BigDecimal) d[2], (BigDecimal) d[3], r.noticeDays(), (BigDecimal) d[4],
                    (BigDecimal) d[6], r.leaveDays(), (BigDecimal) d[5],
                    r.severanceAmount(), r.endOfCareerAmount(), r.thirteenthAmount(), total, null, r.note(), r.status(), r.createdBy(),
                    r.confirmedBy(), r.confirmedAt(), r.certificateNo(), monthStatus(YearMonth.from(r.lastDay())), List.of());
        });
    }

    public List<Summary> list() {
        return jdbc.sql("""
                select d.id, d.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no, d.last_day, d.reason,
                       d.status, d.currency, d.notice_amount + d.leave_amount + d.severance_amount + d.end_of_career_amount + d.thirteenth_amount - d.notice_owed as total
                from departure d join app_user u on u.id = d.user_id order by d.last_day desc limit 200""")
                .query(Summary.class).list();
    }

    /** Saves the settlement as a draft: nothing reaches the payroll before it is confirmed. */
    @Transactional
    public Departure save(long userId, Input in) {
        Row existing = row(userId).orElse(null);
        if (existing != null && existing.status().equals("CONFIRMED")) {
            throw new ConflictException("This departure is confirmed: cancel it first to change it.");
        }
        Departure d = propose(userId, in);
        if (existing == null) {
            jdbc.sql("""
                    insert into departure (user_id, last_day, reason, notice_date, notice_given_by, notice_end, notice, currency,
                        basic_monthly, daily_rate, service_years, notice_days, notice_amount, notice_owed, leave_days,
                        leave_amount, severance_amount, end_of_career_amount, thirteenth_amount, note, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(userId, d.lastDay(), d.reason(), d.noticeDate(), d.noticeGivenBy(), d.noticeEnd(), d.notice(),
                            d.currency(), d.basicMonthly(), d.dailyRate(), d.serviceYears(), d.noticeDays(), d.noticeAmount(),
                            d.noticeOwed(), d.leaveDays(), d.leaveAmount(), d.severanceAmount(), d.endOfCareerAmount(),
                            d.thirteenthAmount(),
                            blank(in.note()), CurrentUser.email())
                    .update();
        } else {
            jdbc.sql("""
                    update departure set last_day = ?, reason = ?, notice_date = ?, notice_given_by = ?, notice_end = ?,
                        notice = ?, currency = ?, basic_monthly = ?, daily_rate = ?, service_years = ?, notice_days = ?,
                        notice_amount = ?, notice_owed = ?, leave_days = ?, leave_amount = ?, severance_amount = ?,
                        end_of_career_amount = ?, thirteenth_amount = ?, note = ? where id = ?""")
                    .params(d.lastDay(), d.reason(), d.noticeDate(), d.noticeGivenBy(), d.noticeEnd(), d.notice(),
                            d.currency(), d.basicMonthly(), d.dailyRate(), d.serviceYears(), d.noticeDays(), d.noticeAmount(),
                            d.noticeOwed(), d.leaveDays(), d.leaveAmount(), d.severanceAmount(), d.endOfCareerAmount(),
                            d.thirteenthAmount(),
                            blank(in.note()), existing.id())
                    .update();
        }
        audit.record("departure.saved", "user", userId, Map.of("lastDay", d.lastDay().toString(), "reason", d.reason(),
                "total", d.total().toPlainString(), "currency", d.currency()));
        return find(userId).orElseThrow();
    }

    private BigDecimal payrollSetting(String key) {
        return jdbc.sql("select value from payroll_setting where key = ?").param(key).query(String.class).optional()
                .map(BigDecimal::new).orElse(BigDecimal.ZERO);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private long elementId(String code) {
        return jdbc.sql("select id from pay_element where code = ?").param(code).query(Long.class).optional()
                .orElseThrow(() -> new IllegalStateException("The pay element %s is missing".formatted(code)));
    }

    /**
     * Confirms the departure: the settlement's pay entries go into the month of the last day, the leave paid out
     * leaves the balance, and the contract ends on the last day.
     */
    @Transactional
    public Departure confirm(long userId) {
        Row r = row(userId).orElseThrow(() -> new NotFoundException("Save the departure first."));
        if (r.status().equals("CONFIRMED")) {
            throw new ConflictException("This departure is already confirmed.");
        }
        if (Long.valueOf(userId).equals(CurrentUser.id())) {
            throw new org.springframework.security.access.AccessDeniedException("You cannot confirm your own departure.");
        }
        YearMonth month = YearMonth.from(r.lastDay());
        PayrollRuns.requireOpen(jdbc, month);
        Departure d = find(userId).orElseThrow();
        if (representation != null && EMPLOYER_ENDS.contains(d.reason())) {
            representation.requireAuthorisation(userId, "DISMISSAL", d.noticeDate() != null ? d.noticeDate() : d.lastDay());
        }
        String note = Texts.t("final settlement, last day %s", Texts.day(d.lastDay()));
        writeEntry(userId, "NOTICE_PAY", month, d.noticeAmount(), d.currency(),
                Texts.t("%s days", d.noticeDays().stripTrailingZeros().toPlainString()));
        writeEntry(userId, "NOTICE_OWED", month, d.noticeOwed(), d.currency(),
                Texts.t("%s days", d.noticeDays().stripTrailingZeros().toPlainString()));
        writeEntry(userId, "LEAVE_PAYOUT", month, d.leaveAmount(), d.currency(),
                Texts.t("%s days", d.leaveDays().stripTrailingZeros().toPlainString()));
        writeEntry(userId, "SEVERANCE", month, d.severanceAmount(), d.currency(), note);
        if (d.endOfCareerAmount().signum() > 0) {
            writeEntry(userId, "END_OF_CAREER", month, d.endOfCareerAmount(), d.currency(), note);
        }
        writeEntry(userId, "THIRTEENTH_MONTH", month, d.thirteenthAmount(), d.currency(), note);
        if (d.leaveDays().signum() > 0) {
            jdbc.sql("""
                    insert into leave_ledger (user_id, leave_type_id, entry_date, days, kind, note, created_by)
                    select ?, t.id, ?, ?, 'PAYOUT', ?, ? from leave_type t where t.code = 'ANNUAL'""")
                    .params(userId, d.lastDay(), d.leaveDays().negate(), "departure", CurrentUser.email()).update();
        }
        // The contract in force ends on the last day; later contracts are not expected.
        var contract = files.contractOn(userId, d.lastDay()).orElse(null);
        if (contract != null) {
            jdbc.sql("update employee_contract set end_date = ? where id = ?").params(d.lastDay(), contract.id()).update();
        }
        jdbc.sql("""
                update departure set status = 'CONFIRMED', confirmed_by = ?, confirmed_at = now(), contract_end_before = ?
                where id = ?""")
                .params(CurrentUser.email(), contract == null ? null : contract.endDate(), r.id()).update();
        audit.record("departure.confirmed", "user", userId, Map.of("lastDay", d.lastDay().toString(), "reason", d.reason(),
                "notice", d.noticeAmount().toPlainString(), "noticeOwed", d.noticeOwed().toPlainString(), "leave", d.leaveAmount().toPlainString(),
                "severance", d.severanceAmount().toPlainString(), "endOfCareer", d.endOfCareerAmount().toPlainString(), "thirteenth", d.thirteenthAmount().toPlainString(),
                "currency", d.currency()));
        return find(userId).orElseThrow();
    }

    private void writeEntry(long userId, String code, YearMonth month, BigDecimal amount, String currency, String note) {
        long element = elementId(code);
        jdbc.sql("delete from pay_entry where user_id = ? and element_id = ? and month = ?")
                .params(userId, element, month.atDay(1)).update();
        if (amount.signum() > 0) {
            jdbc.sql("""
                    insert into pay_entry (user_id, element_id, month, amount, currency, note, created_by)
                    values (?, ?, ?, ?, ?, ?, ?)""")
                    .params(userId, element, month.atDay(1), amount, currency,
                            note.length() > 300 ? note.substring(0, 300) : note, CurrentUser.email())
                    .update();
        }
    }

    /** Takes the departure back: while its month is open, a confirmed one is undone too. */
    @Transactional
    public void cancel(long userId) {
        Row r = row(userId).orElseThrow(() -> new NotFoundException("There is no departure for this person."));
        if (r.status().equals("CONFIRMED")) {
            YearMonth month = YearMonth.from(r.lastDay());
            PayrollRuns.requireOpen(jdbc, month);
            for (String code : List.of("NOTICE_PAY", "NOTICE_OWED", "LEAVE_PAYOUT", "SEVERANCE", "END_OF_CAREER", "THIRTEENTH_MONTH")) {
                jdbc.sql("delete from pay_entry where user_id = ? and month = ? and element_id = (select id from pay_element where code = ?)")
                        .params(userId, month.atDay(1), code).update();
            }
            jdbc.sql("delete from leave_ledger where user_id = ? and kind = 'PAYOUT' and entry_date = ? and note = 'departure'")
                    .params(userId, r.lastDay()).update();
            jdbc.sql("update employee_contract set end_date = ? where user_id = ? and end_date = ?")
                    .params(r.contractEndBefore(), userId, r.lastDay()).update();
        }
        jdbc.sql("delete from departure where id = ?").param(r.id()).update();
        audit.record("departure.cancelled", "user", userId, Map.of("lastDay", r.lastDay().toString(), "status", r.status()));
    }

    // ---- certificate of employment ------------------------------------------------------------------------------

    private static final Font TITLE = new Font(Font.HELVETICA, 16, Font.BOLD);
    private static final Font BOLD = new Font(Font.HELVETICA, 10, Font.BOLD);
    private static final Font NORMAL = new Font(Font.HELVETICA, 11, Font.NORMAL);
    private static final Font SMALL = new Font(Font.HELVETICA, 8, Font.NORMAL);

    /** The certificate of employment (certificat de travail), in the company's language. Numbered when first made. */
    @Transactional
    public byte[] certificate(long userId) {
        Row r = row(userId).filter(x -> x.status().equals("CONFIRMED"))
                .orElseThrow(() -> new BusinessRuleException("Confirm the departure before making the certificate."));
        String number = r.certificateNo();
        if (number == null) {
            int n = jdbc.sql("select count(*) from departure where certificate_no is not null").query(Integer.class).single() + 1;
            number = "CT-%d-%04d".formatted(LocalDate.now(clock).getYear(), n);
            jdbc.sql("update departure set certificate_no = ? where id = ?").params(number, r.id()).update();
            audit.record("departure.certificate", "user", userId, Map.of("number", number));
        }
        String no = number;
        return Texts.in(company.language(), () -> drawCertificate(userId, r, no));
    }

    private byte[] drawCertificate(long userId, Row r, String number) {
        AppUser u = user(userId);
        Company.Details employer = company.get();
        var contracts = files.contracts(userId);
        var lastContract = contracts.stream().filter(c -> !c.startDate().isAfter(r.lastDay()))
                .max(java.util.Comparator.comparing(EmployeeFile.Contract::startDate)).orElse(null);
        LocalDate from = hired(u);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        cd.hrpay.payment.Letterhead.Head head = letterhead.read();
        Document doc = new Document(PageSize.A4, 60, 60, 40, cd.hrpay.payment.Letterhead.Head.bottomMargin());
        head.footer(PdfWriter.getInstance(doc, bytes));
        doc.addTitle(Texts.t("Certificate of employment"));
        doc.addCreator("HR Pay");
        doc.open();
        head.draw(doc);
        Paragraph title = new Paragraph(Texts.t("CERTIFICATE OF EMPLOYMENT"), TITLE);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingBefore(40);
        title.setSpacingAfter(6);
        doc.add(title);
        Paragraph num = new Paragraph(Texts.t("No. %s", number), SMALL);
        num.setAlignment(Element.ALIGN_CENTER);
        num.setSpacingAfter(30);
        doc.add(num);
        String occupation = lastContract == null || lastContract.occupationName() == null ? null : lastContract.occupationName();
        String grade = lastContract == null ? null : lastContract.gradeCode() + " · " + lastContract.gradeName();
        Paragraph body = new Paragraph(Texts.t("We, %s, certify that %s, registration number %s, worked for our company from %s to %s",
                employer.name(), u.fullName(), u.getRegistrationNo(),
                from == null ? "?" : Texts.day(from), Texts.day(r.lastDay()))
                + (occupation == null ? "" : Texts.t(", last as %s", occupation))
                + (grade == null ? "" : Texts.t(" (grade %s)", grade)) + ".", NORMAL);
        body.setLeading(18);
        body.setSpacingAfter(14);
        doc.add(body);
        Paragraph free = new Paragraph(Texts.t("The person leaves our company free of any commitment towards it."), NORMAL);
        free.setLeading(18);
        free.setSpacingAfter(14);
        doc.add(free);
        Paragraph purpose = new Paragraph(Texts.t("This certificate is issued to serve where needed."), NORMAL);
        purpose.setSpacingAfter(40);
        doc.add(purpose);
        Paragraph place = new Paragraph(Texts.t("Done on %s.", Texts.day(LocalDate.now(clock))), NORMAL);
        place.setAlignment(Element.ALIGN_RIGHT);
        doc.add(place);
        Paragraph sign = new Paragraph(Texts.t("For the employer, name and signature"), NORMAL);
        sign.setAlignment(Element.ALIGN_RIGHT);
        sign.setSpacingBefore(50);
        doc.add(sign);
        doc.close();
        return bytes.toByteArray();
    }
}
