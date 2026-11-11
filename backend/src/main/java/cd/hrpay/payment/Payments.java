package cd.hrpay.payment;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Paying a closed payroll. Finance groups the net pays by how each employee is paid (one bank, one mobile money
 * operator, or cash) and currency, makes one file per group, then records it as paid with the bank or operator
 * reference. Payment details are read from the employee file when the file is made, and copied into it.
 */
@Service
public class Payments {

    public record Payee(long payslipId, long userId, String name, String registrationNo, String method, String provider,
                        String account, String currency, BigDecimal amount, String problem) {}

    public record Group(String method, String provider, String currency, int count, BigDecimal total) {}

    public record Batch(long id, String method, String provider, String currency, int lineCount, BigDecimal total,
                        String status, String createdBy, OffsetDateTime createdAt, LocalDate paidOn, String reference,
                        String paidBy, String cancelReason, String ntpayBatchCode, String ntpayStatus,
                        boolean ntpayFinished, Boolean ntpayTest, OffsetDateTime ntpayScheduledFor, OffsetDateTime ntpaySentAt,
                        String ntpayError) {

        /** NTPay may still pay this file: the batch is waiting for approval, scheduled, running, or its answer was lost. */
        public boolean ntpayLive() {
            return ntpayStatus != null && !ntpayFinished
                    && java.util.Set.of("pending_approval", "scheduled", "approved", "UNKNOWN").contains(ntpayStatus);
        }
    }

    public record Line(long payslipId, long userId, String beneficiary, String registrationNo, String account,
                       BigDecimal amount, String currency, Long unitId, String directionName, String unitName,
                       String ntpayStatus, String ntpayPayoutReference, String ntpayError) {

        Line placed(java.util.Map<Long, cd.hrpay.org.OrgStructure.Placement> units) {
            var p = unitId == null ? null : units.get(unitId);
            return p == null ? this : new Line(payslipId, userId, beneficiary, registrationNo, account, amount, currency,
                    unitId, p.directionName(), p.unitName(), ntpayStatus, ntpayPayoutReference, ntpayError);
        }
    }

    public record Overview(String month, String runStatus, List<Group> toPay, List<Payee> problems,
                           List<Batch> batches, int payslips, int paid) {}

    /** kind: PAYROLL (the month-end net pay), QUINZAINE (the mid-month advance) or ADVANCE (salary advances on demand). */
    public record BatchView(Batch batch, String month, String kind, List<Line> lines, String warning) {}

    /** DRC Code du travail art. 99: a monthly salary is paid at the latest 6 days after the end of the month. */
    static final int PAY_DELAY_DAYS = 6;

    static String lateWarning(String country, String kind, String month, LocalDate paidOn) {
        if (!"CD".equals(country) || !PAYROLL.equals(kind) || paidOn == null || month == null) {
            return null;
        }
        LocalDate due = java.time.YearMonth.parse(month).atEndOfMonth().plusDays(PAY_DELAY_DAYS);
        return paidOn.isAfter(due)
                ? Texts.t("Paid on %s, more than %s days after the end of the month (Code du travail art. 99: at the latest %s).",
                        paidOn, PAY_DELAY_DAYS, due)
                : null;
    }

    public static final String PAYROLL = "PAYROLL";
    public static final String QUINZAINE = "QUINZAINE";
    public static final String ADVANCE = "ADVANCE";
    /** Mission money: advances before departure and balances after the return (TravelMissions). */
    public static final String MISSION = "MISSION";

    private static final String BATCH_SELECT = """
            select id, method, provider, trim(currency) as currency, line_count, total, status, created_by, created_at,
                   paid_on, reference, paid_by, cancel_reason, ntpay_batch_code, ntpay_status, ntpay_finished, ntpay_test,
                   ntpay_scheduled_for, ntpay_sent_at, ntpay_error from payment_batch""";

    private final JdbcClient jdbc;
    private final cd.hrpay.security.DataCrypto crypto;
    private final AuditService audit;
    private final Clock clock;
    private cd.hrpay.org.OrgStructure org;

    @org.springframework.beans.factory.annotation.Autowired
    void setOrg(cd.hrpay.org.OrgStructure org) {
        this.org = org;
    }

    public Payments(JdbcClient jdbc, AuditService audit, Clock clock, cd.hrpay.security.DataCrypto crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.audit = audit;
        this.clock = clock;
    }

    /** Mobile numbers as operators expect them: 243 followed by 9 digits. Null when it cannot be read. */
    static String msisdn(String phone) {
        if (phone == null) {
            return null;
        }
        String d = phone.replaceAll("\\D", "");
        if (d.startsWith("00243")) {
            d = d.substring(2);
        } else if (d.length() == 10 && d.startsWith("0")) {
            d = "243" + d.substring(1);
        } else if (d.length() == 9) {
            d = "243" + d;
        }
        return d.matches("243[0-9]{9}") ? d : null;
    }

    private static String norm(String s) {
        return s == null || s.isBlank() ? null : s.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private record RunRow(long id, String status) {}

    private RunRow run(YearMonth month) {
        return jdbc.sql("select id, status from payroll_run where month = ?").param(month.atDay(1))
                .query(RunRow.class).optional()
                .orElseThrow(() -> new NotFoundException("No payroll for " + month + " yet."));
    }

    private RunRow run(YearMonth month, String kind) {
        if (MISSION.equals(kind)) {
            return jdbc.sql("select id, 'CLOSED' as status from mission_pay_run where month = ?").param(month.atDay(1))
                    .query(RunRow.class).optional()
                    .orElseThrow(() -> new NotFoundException("No mission payment for " + month + " yet."));
        }
        if (ADVANCE.equals(kind)) {
            // Approved advances are paid as they come: the month's advances are always ready to pay.
            return jdbc.sql("select id, 'CLOSED' as status from advance_run where month = ?").param(month.atDay(1))
                    .query(RunRow.class).optional()
                    .orElseThrow(() -> new NotFoundException("No salary advance for " + month + " yet."));
        }
        if (!QUINZAINE.equals(kind)) {
            return run(month);
        }
        return jdbc.sql("select id, status from quinzaine_run where month = ? and status <> 'CANCELLED'").param(month.atDay(1))
                .query(RunRow.class).optional()
                .orElseThrow(() -> new NotFoundException("No quinzaine for " + month + " yet."));
    }

    private static String runColumn(String kind) {
        return QUINZAINE.equals(kind) ? "quinzaine_run_id" : ADVANCE.equals(kind) ? "advance_run_id"
                : MISSION.equals(kind) ? "mission_run_id" : "run_id";
    }

    private static String lineColumn(String kind) {
        return QUINZAINE.equals(kind) ? "quinzaine_line_id" : ADVANCE.equals(kind) ? "advance_id"
                : MISSION.equals(kind) ? "mission_payment_id" : "payslip_id";
    }

    /**
     * Payslips of the month (or quinzaine receipts) not yet in a live payment file, with how each one would be paid
     * today.
     */
    private List<Payee> unpaid(long runId, String kind) {
        String sql = MISSION.equals(kind) ? """
                select p.id, p.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no,
                       trim(p.currency) as currency, p.amount as net,
                       coalesce(f.payment_method, 'BANK') as method, f.bank_name, f.bank_account,
                       f.mobile_money_operator, f.mobile_money_number
                from mission_payment p join app_user u on u.id = p.user_id
                left join employee_profile f on f.user_id = p.user_id
                where p.run_id = ? and p.status = 'APPROVED' and p.kind in ('ADVANCE', 'BALANCE')
                  and not exists (select 1 from payment_line l where l.mission_payment_id = p.id)
                order by u.last_name, u.first_name""" : ADVANCE.equals(kind) ? """
                select p.id, p.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no,
                       trim(p.currency) as currency, p.amount as net,
                       coalesce(f.payment_method, 'BANK') as method, f.bank_name, f.bank_account,
                       f.mobile_money_operator, f.mobile_money_number
                from salary_advance p join app_user u on u.id = p.user_id
                left join employee_profile f on f.user_id = p.user_id
                where p.run_id = ? and p.status = 'APPROVED' and not exists (select 1 from payment_line l where l.advance_id = p.id)
                order by u.last_name, u.first_name""" : QUINZAINE.equals(kind) ? """
                select p.id, p.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no,
                       trim(p.currency) as currency, p.amount as net,
                       coalesce(f.payment_method, 'BANK') as method, f.bank_name, f.bank_account,
                       f.mobile_money_operator, f.mobile_money_number
                from quinzaine_line p join app_user u on u.id = p.user_id
                left join employee_profile f on f.user_id = p.user_id
                where p.run_id = ? and p.number is not null and not exists (select 1 from payment_line l where l.quinzaine_line_id = p.id)
                order by u.last_name, u.first_name""" : """
                select p.id, p.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no,
                       trim(p.currency) as currency, p.net,
                       coalesce(f.payment_method, 'BANK') as method, f.bank_name, f.bank_account,
                       f.mobile_money_operator, f.mobile_money_number
                from payslip p join app_user u on u.id = p.user_id
                left join employee_profile f on f.user_id = p.user_id
                where p.run_id = ? and p.net > 0 and p.number is not null and not exists (select 1 from payment_line l where l.payslip_id = p.id)
                order by u.last_name, u.first_name""";
        return jdbc.sql(sql).param(runId)
                .query((rs, i) -> {
                    String method = rs.getString("method");
                    String provider;
                    String account;
                    String problem = null;
                    switch (method) {
                        case "MOBILE_MONEY" -> {
                            provider = norm(rs.getString("mobile_money_operator"));
                            account = msisdn(crypto.decrypt(rs.getString("mobile_money_number")));
                            if (provider == null) {
                                problem = "No mobile money operator in the employee file.";
                            } else if (account == null) {
                                problem = "The mobile money number is missing or not a DR Congo number.";
                            }
                        }
                        case "CASH" -> {
                            provider = "Cash";
                            account = null;
                        }
                        default -> {
                            provider = norm(rs.getString("bank_name"));
                            account = rs.getString("bank_account") == null ? null : crypto.decrypt(rs.getString("bank_account")).trim();
                            if (provider == null || account == null || account.isEmpty()) {
                                problem = "No bank or account number in the employee file.";
                            }
                        }
                    }
                    return new Payee(rs.getLong("id"), rs.getLong("user_id"), rs.getString("name"),
                            rs.getString("registration_no"), method, provider, account, rs.getString("currency"),
                            rs.getBigDecimal("net"), problem);
                }).list();
    }

    public Overview overview(YearMonth month) {
        return overview(month, PAYROLL);
    }

    public Overview overview(YearMonth month, String kind) {
        RunRow run = run(month, kind);
        List<Payee> unpaid = unpaid(run.id(), kind);
        Map<String, Group> groups = new LinkedHashMap<>();
        List<Payee> problems = new ArrayList<>();
        for (Payee p : unpaid) {
            if (p.problem() != null) {
                problems.add(p);
                continue;
            }
            String key = p.method() + "|" + p.provider() + "|" + p.currency();
            Group g = groups.get(key);
            groups.put(key, g == null ? new Group(p.method(), p.provider(), p.currency(), 1, p.amount())
                    : new Group(g.method(), g.provider(), g.currency(), g.count() + 1, g.total().add(p.amount())));
        }
        List<Group> toPay = groups.values().stream()
                .sorted(java.util.Comparator.comparing(Group::method).thenComparing(Group::provider).thenComparing(Group::currency))
                .toList();
        int payslips = MISSION.equals(kind)
                ? jdbc.sql("select count(*) from mission_payment where run_id = ? and status in ('APPROVED', 'PAID')").param(run.id())
                        .query(Integer.class).single()
                : ADVANCE.equals(kind)
                ? jdbc.sql("select count(*) from salary_advance where run_id = ? and status in ('APPROVED', 'PAID')").param(run.id())
                        .query(Integer.class).single()
                : QUINZAINE.equals(kind)
                ? jdbc.sql("select count(*) from quinzaine_line where run_id = ?").param(run.id()).query(Integer.class).single()
                : jdbc.sql("select count(*) from payslip where run_id = ? and net > 0 and (kind = 'REGULAR' or number is not null)").param(run.id()).query(Integer.class).single();
        int paid = jdbc.sql("""
                select count(*) from payment_line l join payment_batch b on b.id = l.batch_id
                where b.%s = ? and b.status = 'PAID'""".formatted(runColumn(kind))).param(run.id()).query(Integer.class).single();
        return new Overview(month.toString(), run.status(), toPay, problems,
                jdbc.sql(BATCH_SELECT + " where " + runColumn(kind) + " = ? order by id desc").param(run.id()).query(Batch.class).list(),
                payslips, paid);
    }

    /** Makes the payment file for one group of a closed month. Employees whose details are incomplete are left out. */
    @Transactional
    public BatchView prepare(YearMonth month, String method, String provider, String currency) {
        return prepare(month, PAYROLL, method, provider, currency);
    }

    @Transactional
    public BatchView prepare(YearMonth month, String kind, String method, String provider, String currency) {
        boolean quinzaine = QUINZAINE.equals(kind);
        boolean advance = ADVANCE.equals(kind);
        boolean mission = MISSION.equals(kind);
        RunRow run = run(month, kind);
        if (!run.status().equals("CLOSED")) {
            throw new BusinessRuleException(quinzaine ? "Close the quinzaine of " + month + " before making payment files."
                    : "Close the " + month + " payroll before making payment files.");
        }
        jdbc.sql("select id from " + (quinzaine ? "quinzaine_run" : advance ? "advance_run" : mission ? "mission_pay_run" : "payroll_run") + " where id = ? for update")
                .param(run.id()).query(Long.class).single();
        List<Payee> payees = unpaid(run.id(), kind).stream()
                .filter(p -> p.problem() == null && p.method().equals(method) && p.provider().equals(provider)
                        && p.currency().equals(currency))
                .toList();
        if (payees.isEmpty()) {
            throw new BusinessRuleException("Nobody left to pay by " + provider + " in " + currency + " for " + month + ".");
        }
        BigDecimal total = payees.stream().map(Payee::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        long id = jdbc.sql("""
                insert into payment_batch (%s, method, provider, currency, line_count, total, status, created_by)
                values (?, ?, ?, ?, ?, ?, 'PREPARED', ?) returning id""".formatted(runColumn(kind)))
                .params(run.id(), method, provider, currency, payees.size(), total, CurrentUser.email())
                .query(Long.class).single();
        for (Payee p : payees) {
            jdbc.sql("""
                    insert into payment_line (batch_id, %s, user_id, beneficiary, registration_no, account, amount, currency)
                    values (?, ?, ?, ?, ?, ?, ?, ?)""".formatted(lineColumn(kind)))
                    .params(id, p.payslipId(), p.userId(), p.name(), p.registrationNo(), p.account(), p.amount(), currency)
                    .update();
        }
        audit.record("payment.prepared", "payment_batch", id, Map.of("month", month.toString(), "method", method,
                "provider", provider, "currency", currency, "count", payees.size(), "total", total, "kind", quinzaine ? QUINZAINE : advance ? ADVANCE : mission ? MISSION : PAYROLL));
        return batch(id);
    }

    public BatchView batch(long id) {
        Batch b = jdbc.sql(BATCH_SELECT + " where id = ?").param(id).query(Batch.class).optional()
                .orElseThrow(() -> new NotFoundException("Payment file not found."));
        record Source(String month, String kind) {}
        Source src = jdbc.sql("""
                select to_char(coalesce(r.month, q.month, a.month, m.month), 'YYYY-MM') as month,
                       case when b.quinzaine_run_id is not null then 'QUINZAINE' when b.advance_run_id is not null then 'ADVANCE'
                            when b.mission_run_id is not null then 'MISSION' else 'PAYROLL' end as kind
                from payment_batch b left join payroll_run r on r.id = b.run_id left join quinzaine_run q on q.id = b.quinzaine_run_id
                left join advance_run a on a.id = b.advance_run_id left join mission_pay_run m on m.id = b.mission_run_id
                where b.id = ?""").param(id).query(Source.class).single();
        List<Line> lines = jdbc.sql("""
                select coalesce(l.payslip_id, l.quinzaine_line_id, l.advance_id, l.mission_payment_id) as payslip_id, l.user_id, l.beneficiary, l.registration_no,
                       l.account, l.amount, trim(l.currency) as currency,
                       coalesce(p.unit_id, q.unit_id, a.unit_id, mp.unit_id) as unit_id, null as direction_name, null as unit_name,
                       l.ntpay_status, l.ntpay_payout_reference, l.ntpay_error
                from payment_line l left join payslip p on p.id = l.payslip_id left join quinzaine_line q on q.id = l.quinzaine_line_id
                left join salary_advance a on a.id = l.advance_id left join mission_payment mp on mp.id = l.mission_payment_id
                where l.batch_id = ? order by l.beneficiary""")
                .param(id).query(Line.class).list();
        var units = org.placementsOfUnits();
        lines = lines.stream().map(l -> l.placed(units)).toList();
        String kind = src.kind();
        String country = jdbc.sql("select country_code from company where id = current_company_id()").query(String.class)
                .optional().orElse("CD");
        return new BatchView(b, src.month(), kind, lines, lateWarning(country, kind, src.month(), b.paidOn()));
    }

    @Transactional
    public BatchView markPaid(long id, LocalDate paidOn, String reference) {
        Batch b = batch(id).batch();
        if (!b.status().equals("PREPARED")) {
            throw new ConflictException("This payment file is " + b.status().toLowerCase(Locale.ROOT) + ".");
        }
        if (paidOn.isAfter(LocalDate.now(clock))) {
            throw new BusinessRuleException("The payment date cannot be in the future.");
        }
        if (!b.method().equals("CASH") && (reference == null || reference.isBlank())) {
            throw new BusinessRuleException("Enter the bank or operator reference of the payment.");
        }
        if (b.ntpayLive()) {
            throw new ConflictException("This file was sent with NTPay: it is marked paid when NTPay finishes it.");
        }
        settle(id, paidOn, reference == null || reference.isBlank() ? null : reference.trim(), CurrentUser.email());
        audit.record("payment.paid", "payment_batch", id, Map.of("paidOn", paidOn.toString(),
                "reference", reference == null ? "" : reference.trim(), "total", b.total()));
        return batch(id);
    }

    /** Records a file as paid, and what follows: a quinzaine paid in full, salary advances paid. */
    void settle(long id, LocalDate paidOn, String reference, String by) {
        jdbc.sql("""
                update payment_batch set status = 'PAID', paid_on = ?, reference = ?, paid_by = ?, paid_at = now()
                where id = ?""").params(paidOn, reference, by, id).update();
        // A quinzaine is paid once every receipt is in a paid file.
        jdbc.sql("""
                update quinzaine_run q set status = 'PAID'
                from payment_batch b where b.id = ? and q.id = b.quinzaine_run_id and q.status = 'CLOSED'
                  and not exists (select 1 from quinzaine_line l where l.run_id = q.id and not exists (
                      select 1 from payment_line p join payment_batch pb on pb.id = p.batch_id
                      where p.quinzaine_line_id = l.id and pb.status = 'PAID'))""").param(id).update();
        // Salary advances in the file are paid.
        jdbc.sql("""
                update salary_advance a set status = 'PAID'
                from payment_line l where l.batch_id = ? and l.advance_id = a.id and a.status = 'APPROVED'""").param(id).update();
        // Mission advances and balances in the file are paid.
        jdbc.sql("""
                update mission_payment m set status = 'PAID'
                from payment_line l where l.batch_id = ? and l.mission_payment_id = m.id and m.status = 'APPROVED'""").param(id).update();
    }

    /** Withdraws a file that was not paid, for instance after correcting an account number. Its payslips can be paid again. */
    @Transactional
    public BatchView cancel(long id, String reason) {
        Batch b = batch(id).batch();
        if (!b.status().equals("PREPARED")) {
            throw new ConflictException("Only a file not yet paid can be cancelled.");
        }
        if (b.ntpayLive()) {
            throw new ConflictException("This file was sent with NTPay: cancel the NTPay batch first.");
        }
        jdbc.sql("delete from payment_line where batch_id = ?").param(id).update();
        jdbc.sql("""
                update payment_batch set status = 'CANCELLED', cancelled_by = ?, cancelled_at = now(), cancel_reason = ?
                where id = ?""").params(CurrentUser.email(), reason.trim(), id).update();
        audit.record("payment.cancelled", "payment_batch", id, Map.of("reason", reason.trim()));
        return batch(id);
    }

    /** When and how a payslip was paid, or null. */
    public Map<String, Object> paymentOf(long payslipId) {
        return jdbc.sql("""
                select b.method, b.provider, b.paid_on, b.reference from payment_line l
                join payment_batch b on b.id = l.batch_id where l.payslip_id = ? and b.status = 'PAID'""")
                .param(payslipId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("method", rs.getString("method"));
                    m.put("provider", rs.getString("provider"));
                    m.put("paidOn", rs.getObject("paid_on", LocalDate.class));
                    m.put("reference", rs.getString("reference"));
                    return m;
                }).optional().orElse(null);
    }
}
