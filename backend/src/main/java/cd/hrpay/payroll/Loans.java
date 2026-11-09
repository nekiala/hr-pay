package cd.hrpay.payroll;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.UserService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loans and salary advances. Payroll takes the installment each month until the balance is repaid. */
@Service
public class Loans {

    public record Loan(long id, long userId, String userName, String kind, BigDecimal principal, String currency,
                       BigDecimal installment, LocalDate startMonth, LocalDate grantedOn, String status,
                       BigDecimal repaid, BigDecimal balance, String note, String createdBy, OffsetDateTime createdAt) {}

    public record Pause(long id, long loanId, LocalDate month, String reason, String createdBy, OffsetDateTime createdAt) {}

    private static final String SELECT = """
            select l.id, l.user_id, person_name(u.first_name, u.middle_name, u.last_name) as user_name, l.kind, l.principal,
                   trim(l.currency) as currency, l.installment, l.start_month, l.granted_on, l.status,
                   coalesce(r.repaid, 0) as repaid, l.principal - coalesce(r.repaid, 0) as balance, l.note,
                   l.created_by, l.created_at
            from loan l join app_user u on u.id = l.user_id
            left join (select loan_id, sum(amount) as repaid from loan_repayment group by loan_id) r on r.loan_id = l.id""";

    private final JdbcClient jdbc;
    private final UserService users;
    private final AuditService audit;

    public Loans(JdbcClient jdbc, UserService users, AuditService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
    }

    public List<Loan> forUser(long userId) {
        return jdbc.sql(SELECT + " where l.user_id = ? order by l.granted_on desc, l.id desc").param(userId)
                .query(Loan.class).list();
    }

    public List<Loan> list(String status) {
        return status == null
                ? jdbc.sql(SELECT + " order by l.status, u.last_name, l.id").query(Loan.class).list()
                : jdbc.sql(SELECT + " where l.status = ? order by u.last_name, l.id").param(status).query(Loan.class).list();
    }

    public Loan loan(long id) {
        return jdbc.sql(SELECT + " where l.id = ?").param(id).query(Loan.class).optional()
                .orElseThrow(() -> new NotFoundException("Loan not found."));
    }

    public List<Pause> pauses(long loanId) {
        return jdbc.sql("select id, loan_id, month, reason, created_by, created_at from loan_pause where loan_id = ? order by month")
                .param(loanId).query(Pause.class).list();
    }

    @Transactional
    public Loan grant(long userId, String kind, BigDecimal principal, String currency, BigDecimal installment,
                      YearMonth start, LocalDate grantedOn, String note) {
        users.find(userId);
        notSelf(userId);
        BigDecimal perMonth = kind.equals("ADVANCE") ? principal : installment;
        if (perMonth == null) {
            throw new BusinessRuleException("Give the monthly installment.");
        }
        if (perMonth.compareTo(principal) > 0) {
            throw new BusinessRuleException("The installment cannot be more than the amount lent.");
        }
        PayrollRuns.requireOpen(jdbc, start);
        long id = jdbc.sql("""
                insert into loan (user_id, kind, principal, currency, installment, start_month, granted_on, note, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(userId, kind, principal, currency, perMonth, start.atDay(1), grantedOn, note, CurrentUser.email())
                .query(Long.class).single();
        audit.record("loan.granted", "user", userId, Map.of("loan", id, "kind", kind,
                "principal", principal.toPlainString(), "currency", currency, "installment", perMonth.toPlainString(),
                "start", start.toString()));
        return loan(id);
    }

    @Transactional
    public Loan cancel(long id, String reason) {
        Loan l = loan(id);
        notSelf(l.userId());
        if (!l.status().equals("ACTIVE")) {
            throw new BusinessRuleException("Only an active loan can be cancelled.");
        }
        jdbc.sql("update loan set status = 'CANCELLED', note = coalesce(note || ' · ', '') || ? where id = ?")
                .params("Cancelled: " + reason, id).update();
        audit.record("loan.cancelled", "user", l.userId(), Map.of("loan", id, "balance", l.balance().toPlainString(),
                "reason", reason));
        return loan(id);
    }

    @Transactional
    public Pause pause(long id, YearMonth month, String reason) {
        Loan l = loan(id);
        notSelf(l.userId());
        if (!l.status().equals("ACTIVE")) {
            throw new BusinessRuleException("Only an active loan can be paused.");
        }
        PayrollRuns.requireOpen(jdbc, month);
        if (pauses(id).stream().anyMatch(p -> p.month().equals(month.atDay(1)))) {
            throw new ConflictException("This loan is already paused for " + month + ".");
        }
        long pid = jdbc.sql("insert into loan_pause (loan_id, month, reason, created_by) values (?, ?, ?, ?) returning id")
                .params(id, month.atDay(1), reason, CurrentUser.email()).query(Long.class).single();
        audit.record("loan.paused", "user", l.userId(), Map.of("loan", id, "month", month.toString(), "reason", reason));
        return pauses(id).stream().filter(p -> p.id() == pid).findFirst().orElseThrow();
    }

    /** Installments due from an employee for a month: active, started, not paused, with a balance left. */
    public List<PayslipCalculator.LoanDue> dueFor(long userId, YearMonth month) {
        return jdbc.sql(SELECT + "\n" + """
                 where l.user_id = ? and l.status = 'ACTIVE' and l.start_month <= ?
                   and not exists (select 1 from loan_pause p where p.loan_id = l.id and p.month = ?)
                 order by l.id""").params(userId, month.atDay(1), month.atDay(1)).query(Loan.class).list().stream()
                .filter(l -> l.balance().signum() > 0)
                .map(l -> new PayslipCalculator.LoanDue(l.id(), l.kind(), l.installment(), l.balance(), l.currency()))
                .toList();
    }

    /** Called when a month closes: records what each payslip took and marks repaid loans. */
    void recordRepayment(long loanId, long runId, BigDecimal amountInLoanCurrency) {
        jdbc.sql("insert into loan_repayment (loan_id, run_id, amount) values (?, ?, ?)")
                .params(loanId, runId, amountInLoanCurrency).update();
        if (loan(loanId).balance().signum() <= 0) {
            jdbc.sql("update loan set status = 'REPAID' where id = ?").param(loanId).update();
        }
    }

    private static void notSelf(long userId) {
        if (Objects.equals(userId, CurrentUser.id())) {
            throw new AccessDeniedException("You cannot change your own loans.");
        }
    }
}
