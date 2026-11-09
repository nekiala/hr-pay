package cd.hrpay.payroll;

import cd.hrpay.attendance.AttendanceDays;
import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.Notifications;
import cd.hrpay.notify.OtpService;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Salary advance on demand: an employee takes part of the net pay already earned this month, before pay day. The
 * month's payslip takes it back in full, before loans (like the quinzaine); what it cannot take back becomes an advance
 * repaid from the next months when the payroll closes.
 *
 * <p>DRC Code du travail art. 112 c) allows deductions for advances and no article allows interest or fees on them, so
 * the advance is free: the employer pays it from its own funds. The employee confirms a receipt with a one-time code.
 *
 * <p>What a person may take today: the category's share (10% to 50%) of the net already earned (a normal month's net
 * times the paid time from the 1st to today over the month's scheduled time), less the quinzaine of the month and the
 * advances already asked for, rounded down to a whole unit. Nobody may ask while the company has it off, in the last
 * days of the month, before ADVANCE_MIN_SERVICE_DAYS under contract, once a departure is recorded, on unpaid leave, after
 * ADVANCE_MAX_PER_MONTH requests, or when loans and advances owed are above ADVANCE_MAX_DEBT_MONTHS of net pay.
 */
@Service
public class SalaryAdvances {

    public static final String ELEMENT = "EARNED_ADVANCE";
    public static final String OTP_PURPOSE = "ADVANCE_SIGN";
    static final BigDecimal MIN_PERCENT = BigDecimal.TEN;
    static final BigDecimal MAX_PERCENT = BigDecimal.valueOf(50);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public record Settings(boolean enabled, String approval, int maxPerMonth, int closingDays, int minServiceDays,
                           BigDecimal maxDebtMonths) {}

    public record Category(long id, String code, String name, BigDecimal advancePercent) {}

    /** What a person may take today, or why not ({@code reason}). */
    public record Availability(boolean open, String reason, LocalDate month, String currency, BigDecimal normalNet,
                               BigDecimal earnedNet, BigDecimal percent, BigDecimal quinzaine, BigDecimal taken,
                               BigDecimal available, int requestsLeft, List<String> explanation) {}

    public record Advance(long id, long userId, String employeeName, String registrationNo, LocalDate month, String number,
                          BigDecimal amount, String currency, BigDecimal available, BigDecimal earnedNet, BigDecimal percent,
                          String status, String signedName, OffsetDateTime signedAt, String signedChannels, String sha256,
                          String decidedBy, OffsetDateTime decidedAt, String decisionNote, OffsetDateTime createdAt,
                          boolean paid) {}

    public record CodeSent(UUID challengeId, List<String> channels, OffsetDateTime expiresAt, String receipt) {}

    public record Mine(Settings settings, Availability availability, List<Advance> advances) {}

    private static final String SELECT = """
            select a.id, a.user_id, person_name(u.first_name, u.middle_name, u.last_name) as employee_name, u.registration_no, a.month, a.number,
                   a.amount, trim(a.currency) as currency, a.available, a.earned_net, a.percent, a.status, a.signed_name,
                   a.signed_at, a.signed_channels, a.sha256, a.decided_by, a.decided_at, a.decision_note, a.created_at,
                   a.status = 'PAID' as paid
            from salary_advance a join app_user u on u.id = a.user_id""";

    private final JdbcClient jdbc;
    private final NegotiatedPay negotiated;
    private final AttendanceDays attendance;
    private final UserService users;
    private final ExchangeRates exchange;
    private final PayrollRuns runs;
    private final OtpService otp;
    private final Notifications notifications;
    private final OrgStructure org;
    private final AuditService audit;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public SalaryAdvances(JdbcClient jdbc, NegotiatedPay negotiated, AttendanceDays attendance, UserService users,
                          ExchangeRates exchange, PayrollRuns runs, OtpService otp, Notifications notifications,
                          OrgStructure org, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.negotiated = negotiated;
        this.attendance = attendance;
        this.users = users;
        this.exchange = exchange;
        this.runs = runs;
        this.otp = otp;
        this.notifications = notifications;
        this.org = org;
        this.audit = audit;
        this.clock = clock;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal v) {
        return money(v).toPlainString();
    }

    // ---- settings -------------------------------------------------------------------------------------------------

    private String setting(String key, String fallback) {
        return jdbc.sql("select value from payroll_setting where key = ?").param(key).query(String.class).optional()
                .orElse(fallback);
    }

    public Settings settings() {
        return new Settings(Boolean.parseBoolean(setting("ADVANCE_ENABLED", "false")), setting("ADVANCE_APPROVAL", "AUTO"),
                Integer.parseInt(setting("ADVANCE_MAX_PER_MONTH", "2")), Integer.parseInt(setting("ADVANCE_CLOSING_DAYS", "5")),
                Integer.parseInt(setting("ADVANCE_MIN_SERVICE_DAYS", "30")),
                new BigDecimal(setting("ADVANCE_MAX_DEBT_MONTHS", "3")));
    }

    @Transactional
    public Settings saveSettings(Settings s) {
        if (!List.of("AUTO", "HR").contains(s.approval())) {
            throw new BusinessRuleException("Approval is AUTO or HR.");
        }
        if (s.maxPerMonth() < 1 || s.maxPerMonth() > 10) {
            throw new BusinessRuleException("Allow from 1 to 10 requests a month.");
        }
        if (s.closingDays() < 0 || s.closingDays() > 15) {
            throw new BusinessRuleException("The days without requests at the end of the month go from 0 to 15.");
        }
        if (s.minServiceDays() < 0 || s.minServiceDays() > 365) {
            throw new BusinessRuleException("The days under contract before a first request go from 0 to 365.");
        }
        if (s.maxDebtMonths() == null || s.maxDebtMonths().signum() <= 0 || s.maxDebtMonths().compareTo(BigDecimal.valueOf(12)) > 0) {
            throw new BusinessRuleException("The debt limit goes from 0.5 to 12 months of net pay.");
        }
        Map<String, String> values = Map.of("ADVANCE_ENABLED", String.valueOf(s.enabled()), "ADVANCE_APPROVAL", s.approval(),
                "ADVANCE_MAX_PER_MONTH", String.valueOf(s.maxPerMonth()), "ADVANCE_CLOSING_DAYS", String.valueOf(s.closingDays()),
                "ADVANCE_MIN_SERVICE_DAYS", String.valueOf(s.minServiceDays()),
                "ADVANCE_MAX_DEBT_MONTHS", s.maxDebtMonths().stripTrailingZeros().toPlainString());
        values.forEach((k, v) -> jdbc.sql("""
                insert into payroll_setting (key, value) values (?, ?)
                on conflict (company_id, key) do update set value = excluded.value""").params(k, v).update());
        audit.record("advance.settings", "payroll_setting", null, Map.copyOf(values));
        return settings();
    }

    public List<Category> categories() {
        return jdbc.sql("select id, code, name, advance_percent from grade_category where active order by code")
                .query(Category.class).list();
    }

    @Transactional
    public List<Category> setCategoryPercent(long categoryId, BigDecimal percent) {
        if (percent != null && (percent.compareTo(MIN_PERCENT) < 0 || percent.compareTo(MAX_PERCENT) > 0)) {
            throw new BusinessRuleException("The share of the net earned must be between 10% and 50%.");
        }
        if (jdbc.sql("update grade_category set advance_percent = ? where id = ?").params(percent, categoryId).update() == 0) {
            throw new NotFoundException("Grade category not found.");
        }
        audit.record("grade_category.advance_percent", "grade_category", categoryId,
                percent == null ? Map.of() : Map.of("percent", percent.toPlainString()));
        return categories();
    }

    // ---- what a person may take -------------------------------------------------------------------------------------

    private record Placed(String category, BigDecimal percent, LocalDate firstDay) {}

    private record Owed(String currency, BigDecimal balance) {}

    private Availability closed(LocalDate month, String reason) {
        return new Availability(false, reason, month, null, null, null, null, null, null, BigDecimal.ZERO, 0, List.of());
    }

    public Availability availability(long userId) {
        return availability(userId, null);
    }

    /** {@code leaving}: an advance being decided, left out of what is already taken. */
    Availability availability(long userId, Long leaving) {
        Settings s = settings();
        LocalDate today = LocalDate.now(clock);
        YearMonth ym = YearMonth.from(today);
        LocalDate month = ym.atDay(1);
        if (!s.enabled()) {
            return closed(month, Texts.t("Salary advances are not offered by the company."));
        }
        try {
            PayrollRuns.requireOpen(jdbc, ym);
        } catch (BusinessRuleException e) {
            return closed(month, Texts.t("The payroll of this month is already approved: ask again next month."));
        }
        if (today.getDayOfMonth() > ym.lengthOfMonth() - s.closingDays()) {
            return closed(month, Texts.t("No request in the last %s days of the month, while the payroll is worked out.", s.closingDays()));
        }
        Placed placed = jdbc.sql("""
                select k.code as category, k.advance_percent as percent,
                       (select min(c2.start_date) from employee_contract c2 where c2.user_id = u.id) as first_day
                from app_user u
                join lateral (select c.grade_id from employee_contract c where c.user_id = u.id and c.start_date <= ?
                              and (c.end_date is null or c.end_date >= ?) order by c.start_date desc limit 1) c on true
                join grade g on g.id = c.grade_id join grade_category k on k.id = g.category_id
                where u.id = ? and u.status = 'ACTIVE'""").params(today, today, userId).query(Placed.class).optional().orElse(null);
        if (placed == null) {
            return closed(month, Texts.t("No contract with a grade today."));
        }
        if (placed.percent() == null) {
            return closed(month, Texts.t("Salary advances are not offered to the category %s.", placed.category()));
        }
        long service = ChronoUnit.DAYS.between(placed.firstDay(), today);
        if (service < s.minServiceDays()) {
            return closed(month, Texts.t("A first advance is possible after %s days under contract (from %s).",
                    s.minServiceDays(), placed.firstDay().plusDays(s.minServiceDays())));
        }
        if (jdbc.sql("select count(*) from departure where user_id = ? and last_day >= ?").params(userId, month)
                .query(Integer.class).single() > 0) {
            return closed(month, Texts.t("A departure is recorded: the final settlement pays what is due."));
        }
        if (jdbc.sql("""
                select count(*) from leave_request r join leave_type t on t.id = r.leave_type_id
                where r.user_id = ? and r.status = 'APPROVED' and not t.paid and r.start_date <= ? and r.end_date >= ?""")
                .params(userId, today, today).query(Integer.class).single() > 0) {
            return closed(month, Texts.t("Not during unpaid leave."));
        }
        int asked = jdbc.sql("""
                select count(*) from salary_advance where user_id = ? and month = ? and status in ('REQUESTED', 'APPROVED', 'PAID')
                and id <> ?""").params(userId, month, leaving == null ? -1 : leaving).query(Integer.class).single();
        if (asked >= s.maxPerMonth()) {
            return closed(month, Texts.t("%s requests this month already, the most allowed.", asked));
        }

        PayslipCalculator.Result normal;
        try {
            normal = negotiated.normalMonth(userId, today);
        } catch (BusinessRuleException e) {
            return closed(month, Texts.translate(e.getMessage()));
        }
        String cur = normal.currency();
        BigDecimal net = money(normal.net());
        if (net.signum() <= 0) {
            return closed(month, Texts.t("No net pay in a normal month."));
        }
        List<String> why = new ArrayList<>();
        why.add(Texts.t("Net pay of a normal month: %s %s.", plain(net), cur));

        // Loans and advances owed.
        BigDecimal usdLocal = null;
        BigDecimal debt = BigDecimal.ZERO;
        for (Owed o : jdbc.sql("""
                select trim(l.currency) as currency, sum(l.principal - coalesce((select sum(r.amount) from loan_repayment r
                       where r.loan_id = l.id), 0)) as balance
                from loan l where l.user_id = ? and l.status = 'ACTIVE' group by l.currency""").param(userId).query(Owed.class).list()) {
            if (!o.currency().equals(cur) && usdLocal == null) {
                usdLocal = exchange.local(runs.localCurrency(), today).cdfPerUnit();
            }
            debt = debt.add(ExchangeRates.convert(o.balance(), o.currency(), cur, usdLocal));
        }
        debt = money(debt.max(BigDecimal.ZERO));
        BigDecimal debtCap = money(net.multiply(s.maxDebtMonths()));
        if (debt.compareTo(debtCap) > 0) {
            return closed(month, Texts.t("Loans and advances owed (%s %s) are above %s months of net pay.", plain(debt), cur,
                    s.maxDebtMonths().stripTrailingZeros().toPlainString()));
        }

        // Paid time from the 1st to today (days not yet reached do not count).
        AppUser user = users.find(userId);
        AttendanceDays.MonthSummary summary = attendance.month(user, ym, false);
        int paid = 0;
        for (var d : summary.days()) {
            if (d.date().isAfter(today) || d.date().isAfter(summary.asOf()) || d.date().isBefore(placed.firstDay())) {
                continue;
            }
            paid += d.payableMinutes();
        }
        int scheduledMonth = summary.scheduledMinutesMonth();
        if (scheduledMonth <= 0) {
            return closed(month, Texts.t("No scheduled time this month."));
        }
        BigDecimal earned = money(net.multiply(BigDecimal.valueOf(paid)).divide(BigDecimal.valueOf(scheduledMonth), 6, RoundingMode.HALF_UP));
        why.add(Texts.t("Earned so far this month (paid time to %s): %s %s.", Texts.day(summary.asOf().isAfter(today) ? today : summary.asOf()),
                plain(earned), cur));
        BigDecimal share = money(earned.multiply(placed.percent()).divide(HUNDRED, 6, RoundingMode.HALF_UP));
        why.add(Texts.t("Share of the category %s: %s%%, that is %s %s.", placed.category(),
                placed.percent().stripTrailingZeros().toPlainString(), plain(share), cur));

        BigDecimal quinzaine = sumEntries(userId, month, Quinzaines.ELEMENT, cur, usdLocal, today);
        if (quinzaine.signum() > 0) {
            why.add(Texts.t("Less the quinzaine of the month: %s %s.", plain(quinzaine), cur));
        }
        BigDecimal taken = BigDecimal.ZERO;
        for (Owed o : jdbc.sql("""
                select trim(currency) as currency, sum(amount) as balance from salary_advance
                where user_id = ? and month = ? and status in ('REQUESTED', 'APPROVED', 'PAID') and id <> ? group by currency""")
                .params(userId, month, leaving == null ? -1 : leaving).query(Owed.class).list()) {
            if (!o.currency().equals(cur) && usdLocal == null) {
                usdLocal = exchange.local(runs.localCurrency(), today).cdfPerUnit();
            }
            taken = taken.add(ExchangeRates.convert(o.balance(), o.currency(), cur, usdLocal));
        }
        if (taken.signum() > 0) {
            why.add(Texts.t("Less the advances already asked for this month: %s %s.", plain(taken), cur));
        }
        BigDecimal available = share.subtract(quinzaine).subtract(taken).max(BigDecimal.ZERO).setScale(0, RoundingMode.DOWN);
        why.add(Texts.t("Available today, rounded down to a whole unit: %s %s. Free of interest and fees, taken back on the payslip of the month.",
                plain(available), cur));
        return new Availability(available.signum() > 0, available.signum() > 0 ? null : Texts.t("Nothing available yet this month."),
                month, cur, net, earned, placed.percent(), quinzaine, money(taken), money(available),
                s.maxPerMonth() - asked, List.copyOf(why));
    }

    private BigDecimal sumEntries(long userId, LocalDate month, String code, String cur, BigDecimal usdLocal, LocalDate today) {
        BigDecimal total = BigDecimal.ZERO;
        for (Owed o : jdbc.sql("""
                select trim(e.currency) as currency, sum(e.amount) as balance from pay_entry e
                join pay_element x on x.id = e.element_id where e.user_id = ? and e.month = ? and x.code = ? group by e.currency""")
                .params(userId, month, code).query(Owed.class).list()) {
            BigDecimal rate = usdLocal;
            if (!o.currency().equals(cur) && rate == null) {
                rate = exchange.local(runs.localCurrency(), today).cdfPerUnit();
            }
            total = total.add(ExchangeRates.convert(o.balance(), o.currency(), cur, rate));
        }
        return total;
    }

    // ---- asking ---------------------------------------------------------------------------------------------------

    /** The receipt the employee confirms, word for word; its SHA-256 is kept with the advance. */
    String receipt(AppUser user, BigDecimal amount, String currency, LocalDate month) {
        String company = jdbc.sql("select name from company where id = current_company_id()").query(String.class).optional().orElse("");
        String period = Texts.month(YearMonth.from(month));
        String country = jdbc.sql("select country_code from company where id = current_company_id()").query(String.class)
                .optional().orElse("CD");
        return Texts.t("I, %s (registration no. %s), receive from %s an advance of %s %s on my pay of %s. It carries no "
                        + "interest and no fees, and is taken back in full from my payslip of %s.",
                user.fullName(), user.getRegistrationNo() == null ? "—" : user.getRegistrationNo(),
                company, plain(amount), currency, period, period)
                + ("CD".equals(country) ? " " + Texts.t("(Code du travail art. 112 c)") : "");
    }

    private Availability check(long userId, BigDecimal amount, Long leaving) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2 && amount.stripTrailingZeros().scale() > 2) {
            throw new BusinessRuleException("Enter an amount above zero.");
        }
        Availability a = availability(userId, leaving);
        if (a.currency() == null) {
            throw new BusinessRuleException(a.reason());
        }
        if (amount.compareTo(a.available()) > 0) {
            throw new BusinessRuleException(Texts.t("At most %s %s today.", plain(a.available()), a.currency()));
        }
        return a;
    }

    /** Sends the one-time code that confirms the receipt of this amount. */
    @Transactional
    public CodeSent sendCode(AppUser user, BigDecimal amount) {
        Availability a = check(user.getId(), amount, null);
        String text = receipt(user, amount, a.currency(), a.month());
        OtpService.Challenge c = otp.issue(user, OTP_PURPOSE, json.writeValueAsString(Map.of(
                "amount", plain(amount), "currency", a.currency(), "month", a.month().toString(), "sha256", sha256(text))));
        List<String> channels = c.deliveries().stream().filter(d -> !d.status().equals("FAILED")).map(d -> d.channel().name()).toList();
        // The channels the code went by are kept with the challenge, then with the signed advance.
        jdbc.sql("update otp_challenge set context = context || jsonb_build_object('channels', ?) where id = ?")
                .params(String.join(",", channels), c.id()).update();
        return new CodeSent(c.id(), channels, c.expiresAt(), text);
    }

    /** The employee confirms with the code and their name; the advance is approved at once when the company says so. */
    @Transactional
    public Advance request(AppUser user, UUID challengeId, String code, BigDecimal amount, String typedName, String ip) {
        String expected = user.fullName();
        if (typedName == null || !norm(typedName).equals(norm(expected))) {
            throw new BusinessRuleException(Texts.t("Type your name as HR Pay knows it: %s.", expected));
        }
        OtpService.Verified ok = otp.verify(challengeId, user, OTP_PURPOSE, code);
        Map<?, ?> context = json.readValue(ok.contextJson(), Map.class);
        Availability a = check(user.getId(), amount, null);
        String text = receipt(user, amount, a.currency(), a.month());
        if (!plain(amount).equals(context.get("amount")) || !sha256(text).equals(context.get("sha256"))) {
            throw new BusinessRuleException("This code was asked for another amount. Ask for a new code.");
        }
        long run = runFor(a.month());
        OrgStructure.Placement place = org.placementsOn(a.month()).get(user.getId());
        long id = jdbc.sql("""
                insert into salary_advance (run_id, user_id, month, amount, currency, available, earned_net, percent, explanation,
                                            signed_name, signed_at, signed_ip, signed_channels, sha256, unit_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, now(), ?, ?, ?, ?) returning id""")
                .params(run, user.getId(), a.month(), money(amount), a.currency(), a.available(), a.earnedNet(), a.percent(),
                        json.writeValueAsString(a.explanation()), typedName.trim(), ip, String.join(",", channels(context, ok)),
                        sha256(text), place == null ? null : place.unitId())
                .query(Long.class).single();
        audit.recordAs(user.getEmail(), "advance.requested", "salary_advance", id, Map.of("amount", plain(amount),
                "currency", a.currency(), "month", a.month().toString()));
        if (settings().approval().equals("AUTO")) {
            approveNow(id, "automatic", null);
        } else {
            notifications.sendToHolders("advance.manage", "advance.requested",
                    () -> Texts.t("Salary advance requested"),
                    () -> Texts.t("%s asks for an advance of %s %s.", expected, plain(amount), a.currency()), "/payroll/advances");
        }
        return find(id);
    }

    private static List<String> channels(Map<?, ?> context, OtpService.Verified ok) {
        Object c = context.get("channels");
        return c == null || c.toString().isBlank() ? List.of() : List.of(c.toString().split(","));
    }

    @Transactional
    public Advance cancelMine(long id, AppUser user) {
        Advance a = find(id);
        if (a.userId() != user.getId()) {
            throw new NotFoundException("Advance not found.");
        }
        if (!a.status().equals("REQUESTED")) {
            throw new BusinessRuleException("Only a request not yet decided can be withdrawn.");
        }
        jdbc.sql("update salary_advance set status = 'CANCELLED', decided_by = ?, decided_at = now() where id = ?")
                .params(user.getEmail(), id).update();
        audit.recordAs(user.getEmail(), "advance.cancelled", "salary_advance", id, Map.of());
        return find(id);
    }

    // ---- HR decides -------------------------------------------------------------------------------------------------

    @Transactional
    public Advance approve(long id) {
        Advance a = find(id);
        if (!a.status().equals("REQUESTED")) {
            throw new BusinessRuleException("Only a request waiting for a decision can be approved.");
        }
        if (a.userId() == CurrentUser.id()) {
            throw new BusinessRuleException("Someone else must decide on your own request.");
        }
        // The rules still hold today (time worked, other advances, the payroll not yet approved).
        check(a.userId(), a.amount(), a.id());
        approveNow(id, CurrentUser.email(), null);
        return find(id);
    }

    @Transactional
    public Advance reject(long id, String note) {
        Advance a = find(id);
        if (!a.status().equals("REQUESTED")) {
            throw new BusinessRuleException("Only a request waiting for a decision can be refused.");
        }
        if (note == null || note.isBlank()) {
            throw new BusinessRuleException("Give the reason, the employee reads it.");
        }
        jdbc.sql("update salary_advance set status = 'REJECTED', decided_by = ?, decided_at = now(), decision_note = ? where id = ?")
                .params(CurrentUser.email(), note.trim(), id).update();
        audit.record("advance.rejected", "salary_advance", id, Map.of("note", note.trim()));
        notifications.send(a.userId(), "advance.rejected", () -> Texts.t("Salary advance refused"),
                () -> Texts.t("Your request of %s %s was refused: %s", plain(a.amount()), a.currency(), note.trim()), "/my/advance");
        return find(id);
    }

    /** Numbers the advance, puts it on the month's payslip and tells the employee. */
    private void approveNow(long id, String by, String note) {
        Advance a = find(id);
        PayrollRuns.requireOpen(jdbc, YearMonth.from(a.month()));
        String ym = YearMonth.from(a.month()).toString();
        int n = jdbc.sql("select count(*) from salary_advance where month = ? and number is not null").param(a.month())
                .query(Integer.class).single();
        String number = "AV-%s-%04d".formatted(ym, n + 1);
        jdbc.sql("""
                update salary_advance set status = 'APPROVED', number = ?, decided_by = ?, decided_at = now(), decision_note = ?
                where id = ?""").params(number, by, note, id).update();
        sync(a.userId(), a.month());
        audit.record("advance.approved", "salary_advance", id, Map.of("number", number, "by", by));
        notifications.send(a.userId(), "advance.approved", () -> Texts.t("Salary advance approved"),
                () -> Texts.t("Your advance of %s %s (no. %s) is approved; it is paid by the usual payment method.",
                        plain(a.amount()), a.currency(), number), "/my/advance");
    }

    /** One deduction per person and month on the payslip: the total of the approved and paid advances. */
    private void sync(long userId, LocalDate month) {
        long element = jdbc.sql("select id from pay_element where code = ?").param(ELEMENT).query(Long.class).optional()
                .orElseThrow(() -> new BusinessRuleException("The pay element EARNED_ADVANCE is missing."));
        jdbc.sql("delete from pay_entry where user_id = ? and element_id = ? and month = ?").params(userId, element, month).update();
        for (Owed o : jdbc.sql("""
                select trim(currency) as currency, sum(amount) as balance from salary_advance
                where user_id = ? and month = ? and status in ('APPROVED', 'PAID') group by currency""")
                .params(userId, month).query(Owed.class).list()) {
            String numbers = String.join(", ", jdbc.sql("""
                    select number from salary_advance where user_id = ? and month = ? and status in ('APPROVED', 'PAID')
                    and trim(currency) = ? order by number""").params(userId, month, o.currency()).query(String.class).list());
            jdbc.sql("""
                    insert into pay_entry (user_id, element_id, month, amount, currency, note, created_by)
                    values (?, ?, ?, ?, ?, ?, ?)""")
                    .params(userId, element, month, o.balance(), o.currency(), numbers, "system").update();
        }
    }

    private long runFor(LocalDate month) {
        jdbc.sql("insert into advance_run (month) values (?) on conflict (company_id, month) do nothing").param(month).update();
        return jdbc.sql("select id from advance_run where month = ?").param(month).query(Long.class).single();
    }

    // ---- Finance's daily list -------------------------------------------------------------------------------------

    private cd.hrpay.ops.JobLocks locks;

    @org.springframework.beans.factory.annotation.Autowired
    void setLocks(cd.hrpay.ops.JobLocks locks) {
        this.locks = locks;
    }

    /** Every morning: Finance gets the approved advances not yet in a payment file, to pay them the same day. */
    @org.springframework.scheduling.annotation.Scheduled(cron = "${app.advances.cron:0 0 8 * * *}", zone = "Africa/Kinshasa")
    public void daily() {
        locks.runForEachCompany("salary-advances-daily", java.time.Duration.ofMinutes(30), this::sendFinanceList);
    }

    record ToPay(String currency, int count, BigDecimal total) {}

    /** The number of advances to pay, or 0 when nothing is waiting (no message then). */
    int sendFinanceList() {
        List<ToPay> rows = jdbc.sql("""
                select trim(a.currency) as currency, count(*)::int as count, sum(a.amount) as total from salary_advance a
                where a.status = 'APPROVED' and not exists (select 1 from payment_line l join payment_batch b on b.id = l.batch_id
                    where l.advance_id = a.id and b.status <> 'CANCELLED')
                group by a.currency order by a.currency""").query(ToPay.class).list();
        int count = rows.stream().mapToInt(ToPay::count).sum();
        if (count == 0) {
            return 0;
        }
        notifications.sendToHolders("payroll.pay", "advance.to-pay", () -> Texts.t("Salary advances to pay"),
                () -> Texts.t("%s approved salary advances wait for payment: %s. Make the payment files under Payments, Salary advances.",
                        count, String.join(", ", rows.stream().map(r -> plain(r.total()) + " " + r.currency()).toList())),
                "/payroll/payments?kind=ADVANCE");
        return count;
    }

    // ---- reading ----------------------------------------------------------------------------------------------------

    public Advance find(long id) {
        return jdbc.sql(SELECT + " where a.id = ?").param(id).query(Advance.class).optional()
                .orElseThrow(() -> new NotFoundException("Advance not found."));
    }

    public List<Advance> list(YearMonth month, String status) {
        return status == null || status.isBlank()
                ? jdbc.sql(SELECT + " where a.month = ? order by a.created_at desc").param(month.atDay(1)).query(Advance.class).list()
                : jdbc.sql(SELECT + " where a.month = ? and a.status = ? order by a.created_at desc").params(month.atDay(1), status)
                        .query(Advance.class).list();
    }

    public List<Advance> waiting() {
        return jdbc.sql(SELECT + " where a.status = 'REQUESTED' order by a.created_at").query(Advance.class).list();
    }

    public Mine mine(long userId) {
        return new Mine(settings(), availability(userId),
                jdbc.sql(SELECT + " where a.user_id = ? order by a.created_at desc limit 24").param(userId).query(Advance.class).list());
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The name's words without accents or case, in any order (Nom Postnom Prénom or Prénom Nom both match). */
    private static String norm(String s) {
        return java.util.Arrays.stream(java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFD)
                        .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT).split("\\s+"))
                .sorted().collect(java.util.stream.Collectors.joining(" "));
    }
}
