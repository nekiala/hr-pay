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
            "END_OF_CONTRACT", "MUTUAL_AGREEMENT", "RETIREMENT", "DEATH");

    /** The reasons that give severance pay, when the rate is set. */
    static final Set<String> SEVERANCE_REASONS = Set.of("DISMISSAL", "REDUNDANCY", "RETIREMENT");

    /** HR's choices; the amounts are worked out from them unless HR gives its own. */
    public record Input(LocalDate lastDay, String reason, String notice, BigDecimal noticeDays, BigDecimal leaveDays,
                        BigDecimal severanceAmount, BigDecimal thirteenthAmount, String note) {}

    public record Departure(Long id, long userId, String name, String registrationNo, LocalDate hireDate,
                            LocalDate lastDay, String reason, String notice, String currency, BigDecimal basicMonthly,
                            BigDecimal dailyRate, BigDecimal serviceYears, BigDecimal noticeDays, BigDecimal noticeAmount,
                            BigDecimal leaveDays, BigDecimal leaveAmount, BigDecimal severanceAmount,
                            BigDecimal thirteenthAmount, BigDecimal total, BigDecimal loanBalance, String note,
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
        var salary = salaries.on(userId, in.lastDay()).orElseThrow(
                () -> new BusinessRuleException("There is no basic salary on the last day: record it first."));
        LocalDate on = in.lastDay();
        List<String> why = new ArrayList<>();
        BigDecimal perMonth = statutory.rate("SETTLEMENT_DAYS_PER_MONTH", on);
        BigDecimal daily = salary.grossMonthly().divide(perMonth, 4, RoundingMode.HALF_UP);
        why.add(Texts.t("Daily rate: basic salary %s %s ÷ %s days = %s.", salary.grossMonthly().toPlainString(),
                salary.currency(), perMonth.stripTrailingZeros().toPlainString(), money(daily).toPlainString()));

        Period served = hiredOn == null ? Period.ZERO : Period.between(hiredOn, on.plusDays(1));
        BigDecimal years = BigDecimal.valueOf(served.getYears())
                .add(BigDecimal.valueOf(served.getMonths()).divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP));
        why.add(Texts.t("Service: %s years and %s months since %s.", served.getYears(), served.getMonths(),
                hiredOn == null ? "?" : Texts.day(hiredOn)));

        String notice = in.notice() == null ? defaultNotice(in.reason()) : in.notice();
        BigDecimal noticeDays = in.noticeDays();
        if (noticeDays == null) {
            noticeDays = "PAID".equals(notice)
                    ? statutory.rate("NOTICE_BASE_DAYS", on).add(statutory.rate("NOTICE_DAYS_PER_YEAR", on)
                            .multiply(BigDecimal.valueOf(served.getYears())))
                    : BigDecimal.ZERO;
        }
        BigDecimal noticeAmount = "PAID".equals(notice) ? money(daily.multiply(noticeDays)) : BigDecimal.ZERO.setScale(2);
        why.add(switch (notice) {
            case "PAID" -> Texts.t("Notice not worked, paid: %s days.", noticeDays.stripTrailingZeros().toPlainString());
            case "WORKED" -> Texts.t("Notice worked: paid as normal working days, nothing more.");
            default -> Texts.t("No notice is due for this reason.");
        });

        BigDecimal leaveDays = in.leaveDays();
        if (leaveDays == null) {
            leaveDays = leaveLeft(userId, on, why);
        }
        BigDecimal leaveAmount = money(daily.multiply(leaveDays));

        BigDecimal severance = in.severanceAmount();
        if (severance == null) {
            BigDecimal perYear = statutory.rate("SEVERANCE_DAYS_PER_YEAR", on);
            severance = SEVERANCE_REASONS.contains(in.reason()) ? money(daily.multiply(perYear).multiply(years)) : BigDecimal.ZERO.setScale(2);
            if (SEVERANCE_REASONS.contains(in.reason())) {
                why.add(perYear.signum() == 0 ? Texts.t("Severance: no rate is set on the Rates page (law or collective agreement).")
                        : Texts.t("Severance: %s days per year × %s years.", perYear.stripTrailingZeros().toPlainString(), years.toPlainString()));
            }
        }

        BigDecimal thirteenth = in.thirteenthAmount();
        if (thirteenth == null) {
            thirteenth = BigDecimal.ZERO.setScale(2);
            var t13 = thirteenthMonth.settings();
            if (t13.enabled() && on.getMonthValue() < t13.payMonth()) {
                var row = thirteenthMonth.forEmployee(userId, u.getFirstName() + " " + u.getLastName(), on.getYear()).orElse(null);
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
        BigDecimal total = noticeAmount.add(leaveAmount).add(severance).add(thirteenth);
        Departure saved = find(userId).orElse(null);
        return new Departure(saved == null ? null : saved.id(), userId, u.getFirstName() + " " + u.getLastName(),
                u.getRegistrationNo(), hiredOn, on, in.reason(), notice, salary.currency(), salary.grossMonthly(), daily,
                years, noticeDays, noticeAmount, leaveDays, leaveAmount, severance, thirteenth, total, money(loanBalance),
                in.note(), saved == null ? "DRAFT" : saved.status(), saved == null ? null : saved.createdBy(),
                saved == null ? null : saved.confirmedBy(), saved == null ? null : saved.confirmedAt(),
                saved == null ? null : saved.certificateNo(), monthStatus(YearMonth.from(on)), why);
    }

    static String defaultNotice(String reason) {
        return switch (reason) {
            case "DISMISSAL", "REDUNDANCY" -> "PAID";
            case "RESIGNATION", "RETIREMENT" -> "WORKED";
            default -> "NONE";
        };
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

    private record Row(long id, long userId, LocalDate lastDay, String reason, String notice, BigDecimal noticeDays,
                       BigDecimal leaveDays, BigDecimal severanceAmount, BigDecimal thirteenthAmount, String note,
                       String status, String createdBy, String confirmedBy, OffsetDateTime confirmedAt,
                       String certificateNo, LocalDate contractEndBefore) {}

    private Optional<Row> row(long userId) {
        return jdbc.sql("""
                select id, user_id, last_day, reason, notice, notice_days, leave_days, severance_amount, thirteenth_amount,
                       note, status, created_by, confirmed_by, confirmed_at, certificate_no, contract_end_before
                from departure where user_id = ?""").param(userId).query(Row.class).optional();
    }

    /** The saved departure, with its amounts as saved. */
    public Optional<Departure> find(long userId) {
        return row(userId).map(r -> {
            AppUser u = user(userId);
            var d = jdbc.sql("""
                    select currency, basic_monthly, daily_rate, service_years, notice_amount, leave_amount
                    from departure where id = ?""").param(r.id())
                    .query((rs, i) -> new Object[] {rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                            rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6)}).single();
            BigDecimal total = ((BigDecimal) d[4]).add((BigDecimal) d[5]).add(r.severanceAmount()).add(r.thirteenthAmount());
            return new Departure(r.id(), userId, u.getFirstName() + " " + u.getLastName(), u.getRegistrationNo(), hired(u),
                    r.lastDay(), r.reason(), r.notice(), (String) d[0], (BigDecimal) d[1], (BigDecimal) d[2],
                    (BigDecimal) d[3], r.noticeDays(), (BigDecimal) d[4], r.leaveDays(), (BigDecimal) d[5],
                    r.severanceAmount(), r.thirteenthAmount(), total, null, r.note(), r.status(), r.createdBy(),
                    r.confirmedBy(), r.confirmedAt(), r.certificateNo(), monthStatus(YearMonth.from(r.lastDay())), List.of());
        });
    }

    public List<Summary> list() {
        return jdbc.sql("""
                select d.id, d.user_id, u.first_name || ' ' || u.last_name as name, u.registration_no, d.last_day, d.reason,
                       d.status, d.currency, d.notice_amount + d.leave_amount + d.severance_amount + d.thirteenth_amount as total
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
                    insert into departure (user_id, last_day, reason, notice, currency, basic_monthly, daily_rate,
                        service_years, notice_days, notice_amount, leave_days, leave_amount, severance_amount,
                        thirteenth_amount, note, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(userId, d.lastDay(), d.reason(), d.notice(), d.currency(), d.basicMonthly(), d.dailyRate(),
                            d.serviceYears(), d.noticeDays(), d.noticeAmount(), d.leaveDays(), d.leaveAmount(),
                            d.severanceAmount(), d.thirteenthAmount(), blank(in.note()), CurrentUser.email())
                    .update();
        } else {
            jdbc.sql("""
                    update departure set last_day = ?, reason = ?, notice = ?, currency = ?, basic_monthly = ?, daily_rate = ?,
                        service_years = ?, notice_days = ?, notice_amount = ?, leave_days = ?, leave_amount = ?,
                        severance_amount = ?, thirteenth_amount = ?, note = ? where id = ?""")
                    .params(d.lastDay(), d.reason(), d.notice(), d.currency(), d.basicMonthly(), d.dailyRate(),
                            d.serviceYears(), d.noticeDays(), d.noticeAmount(), d.leaveDays(), d.leaveAmount(),
                            d.severanceAmount(), d.thirteenthAmount(), blank(in.note()), existing.id())
                    .update();
        }
        audit.record("departure.saved", "user", userId, Map.of("lastDay", d.lastDay().toString(), "reason", d.reason(),
                "total", d.total().toPlainString(), "currency", d.currency()));
        return find(userId).orElseThrow();
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
        String note = Texts.t("final settlement, last day %s", Texts.day(d.lastDay()));
        writeEntry(userId, "NOTICE_PAY", month, d.noticeAmount(), d.currency(),
                Texts.t("%s days", d.noticeDays().stripTrailingZeros().toPlainString()));
        writeEntry(userId, "LEAVE_PAYOUT", month, d.leaveAmount(), d.currency(),
                Texts.t("%s days", d.leaveDays().stripTrailingZeros().toPlainString()));
        writeEntry(userId, "SEVERANCE", month, d.severanceAmount(), d.currency(), note);
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
                "notice", d.noticeAmount().toPlainString(), "leave", d.leaveAmount().toPlainString(),
                "severance", d.severanceAmount().toPlainString(), "thirteenth", d.thirteenthAmount().toPlainString(),
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
            for (String code : List.of("NOTICE_PAY", "LEAVE_PAYOUT", "SEVERANCE", "THIRTEENTH_MONTH")) {
                jdbc.sql("delete from pay_entry where user_id = ? and element_id = ? and month = ?")
                        .params(userId, elementId(code), month.atDay(1)).update();
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
                employer.name(), u.getFirstName() + " " + u.getLastName(), u.getRegistrationNo(),
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
