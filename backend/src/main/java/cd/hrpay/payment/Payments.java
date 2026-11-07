package cd.hrpay.payment;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
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
                        String paidBy, String cancelReason) {}

    public record Line(long payslipId, long userId, String beneficiary, String registrationNo, String account,
                       BigDecimal amount, String currency, Long unitId, String directionName, String unitName) {

        Line placed(java.util.Map<Long, cd.hrpay.org.OrgStructure.Placement> units) {
            var p = unitId == null ? null : units.get(unitId);
            return p == null ? this : new Line(payslipId, userId, beneficiary, registrationNo, account, amount, currency,
                    unitId, p.directionName(), p.unitName());
        }
    }

    public record Overview(String month, String runStatus, List<Group> toPay, List<Payee> problems,
                           List<Batch> batches, int payslips, int paid) {}

    public record BatchView(Batch batch, String month, List<Line> lines) {}

    private static final String BATCH_SELECT = """
            select id, method, provider, trim(currency) as currency, line_count, total, status, created_by, created_at,
                   paid_on, reference, paid_by, cancel_reason from payment_batch""";

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

    /** Payslips of the month not yet in a live payment file, with how each one would be paid today. */
    private List<Payee> unpaid(long runId) {
        return jdbc.sql("""
                select p.id, p.user_id, u.first_name || ' ' || u.last_name as name, u.registration_no,
                       trim(p.currency) as currency, p.net,
                       coalesce(f.payment_method, 'BANK') as method, f.bank_name, f.bank_account,
                       f.mobile_money_operator, f.mobile_money_number
                from payslip p join app_user u on u.id = p.user_id
                left join employee_profile f on f.user_id = p.user_id
                where p.run_id = ? and p.net > 0 and not exists (select 1 from payment_line l where l.payslip_id = p.id)
                order by u.last_name, u.first_name""").param(runId)
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
        RunRow run = run(month);
        List<Payee> unpaid = unpaid(run.id());
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
        int payslips = jdbc.sql("select count(*) from payslip where run_id = ? and net > 0").param(run.id()).query(Integer.class).single();
        int paid = jdbc.sql("""
                select count(*) from payment_line l join payment_batch b on b.id = l.batch_id
                where b.run_id = ? and b.status = 'PAID'""").param(run.id()).query(Integer.class).single();
        return new Overview(month.toString(), run.status(), toPay, problems,
                jdbc.sql(BATCH_SELECT + " where run_id = ? order by id desc").param(run.id()).query(Batch.class).list(),
                payslips, paid);
    }

    /** Makes the payment file for one group of a closed month. Employees whose details are incomplete are left out. */
    @Transactional
    public BatchView prepare(YearMonth month, String method, String provider, String currency) {
        RunRow run = run(month);
        if (!run.status().equals("CLOSED")) {
            throw new BusinessRuleException("Close the " + month + " payroll before making payment files.");
        }
        jdbc.sql("select id from payroll_run where id = ? for update").param(run.id()).query(Long.class).single();
        List<Payee> payees = unpaid(run.id()).stream()
                .filter(p -> p.problem() == null && p.method().equals(method) && p.provider().equals(provider)
                        && p.currency().equals(currency))
                .toList();
        if (payees.isEmpty()) {
            throw new BusinessRuleException("Nobody left to pay by " + provider + " in " + currency + " for " + month + ".");
        }
        BigDecimal total = payees.stream().map(Payee::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        long id = jdbc.sql("""
                insert into payment_batch (run_id, method, provider, currency, line_count, total, status, created_by)
                values (?, ?, ?, ?, ?, ?, 'PREPARED', ?) returning id""")
                .params(run.id(), method, provider, currency, payees.size(), total, CurrentUser.email())
                .query(Long.class).single();
        for (Payee p : payees) {
            jdbc.sql("""
                    insert into payment_line (batch_id, payslip_id, user_id, beneficiary, registration_no, account, amount, currency)
                    values (?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, p.payslipId(), p.userId(), p.name(), p.registrationNo(), p.account(), p.amount(), currency)
                    .update();
        }
        audit.record("payment.prepared", "payment_batch", id, Map.of("month", month.toString(), "method", method,
                "provider", provider, "currency", currency, "count", payees.size(), "total", total));
        return batch(id);
    }

    public BatchView batch(long id) {
        Batch b = jdbc.sql(BATCH_SELECT + " where id = ?").param(id).query(Batch.class).optional()
                .orElseThrow(() -> new NotFoundException("Payment file not found."));
        String month = jdbc.sql("select to_char(r.month, 'YYYY-MM') from payroll_run r join payment_batch b on b.run_id = r.id where b.id = ?")
                .param(id).query(String.class).single();
        List<Line> lines = jdbc.sql("""
                select l.payslip_id, l.user_id, l.beneficiary, l.registration_no, l.account, l.amount, trim(l.currency) as currency,
                       p.unit_id, null as direction_name, null as unit_name
                from payment_line l join payslip p on p.id = l.payslip_id where l.batch_id = ? order by l.beneficiary""")
                .param(id).query(Line.class).list();
        var units = org.placementsOfUnits();
        lines = lines.stream().map(l -> l.placed(units)).toList();
        return new BatchView(b, month, lines);
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
        jdbc.sql("""
                update payment_batch set status = 'PAID', paid_on = ?, reference = ?, paid_by = ?, paid_at = now()
                where id = ?""").params(paidOn, reference == null || reference.isBlank() ? null : reference.trim(),
                CurrentUser.email(), id).update();
        audit.record("payment.paid", "payment_batch", id, Map.of("paidOn", paidOn.toString(),
                "reference", reference == null ? "" : reference.trim(), "total", b.total()));
        return batch(id);
    }

    /** Withdraws a file that was not paid, for instance after correcting an account number. Its payslips can be paid again. */
    @Transactional
    public BatchView cancel(long id, String reason) {
        Batch b = batch(id).batch();
        if (!b.status().equals("PREPARED")) {
            throw new ConflictException("Only a file not yet paid can be cancelled.");
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
