package cd.hrpay.salary;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.ConflictException;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Gross monthly salary history per employee. Entries are never edited; a new entry takes effect on its date. */
@Service
public class SalaryService {

    public record Salary(long id, long userId, LocalDate effectiveFrom, BigDecimal grossMonthly, String currency,
                         String note, String createdBy, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public SalaryService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public List<Salary> history(long userId) {
        return jdbc.sql("""
                select id, user_id, effective_from, gross_monthly, trim(currency) as currency, note, created_by, created_at
                from employee_salary where user_id = ? order by effective_from desc""")
                .param(userId).query(Salary.class).list();
    }

    public Optional<Salary> on(long userId, LocalDate date) {
        return jdbc.sql("""
                select id, user_id, effective_from, gross_monthly, trim(currency) as currency, note, created_by, created_at
                from employee_salary where user_id = ? and effective_from <= ?
                order by effective_from desc limit 1""")
                .param(userId).param(date).query(Salary.class).optional();
    }

    /**
     * The agreed monthly salary on a date: the basic plus the negotiated salary complement in force that month (pay
     * element COMPLEMENT, in the basic's currency). Payslips add the complement themselves; this is for what states
     * a person's salary (certificates, letters, estimates of pay earned, the final settlement).
     */
    public Optional<Salary> agreed(long userId, LocalDate date) {
        return on(userId, date).map(s -> {
            BigDecimal complement = jdbc.sql("""
                    select coalesce(sum(a.amount), 0) from employee_pay_element a join pay_element e on e.id = a.element_id
                    where a.user_id = ? and e.code = 'COMPLEMENT' and e.active and not a.excluded
                      and trim(a.currency) = ? and a.start_month <= ? and (a.end_month is null or a.end_month >= ?)""")
                    .params(userId, s.currency(), date.withDayOfMonth(1), date.withDayOfMonth(1))
                    .query(BigDecimal.class).single();
            return complement.signum() == 0 ? s : new Salary(s.id(), s.userId(), s.effectiveFrom(),
                    s.grossMonthly().add(complement), s.currency(), s.note(), s.createdBy(), s.createdAt());
        });
    }

    @Transactional
    public Salary add(long userId, LocalDate effectiveFrom, BigDecimal gross, String currency, String note) {
        return add(userId, effectiveFrom, gross, currency, note, CurrentUser.email());
    }

    @Transactional
    public Salary add(long userId, LocalDate effectiveFrom, BigDecimal gross, String currency, String note, String by) {
        Integer existing = jdbc.sql("select count(*) from employee_salary where user_id = ? and effective_from = ?")
                .param(userId).param(effectiveFrom).query(Integer.class).single();
        if (existing > 0) {
            throw new ConflictException("A salary already starts on " + effectiveFrom + " for this employee.");
        }
        long id = jdbc.sql("""
                insert into employee_salary (user_id, effective_from, gross_monthly, currency, note, created_by)
                values (?, ?, ?, ?, ?, ?) returning id""")
                .params(userId, effectiveFrom, gross, currency, note, by).query(Long.class).single();
        audit.recordAs(by, "salary.add", "user", userId, Map.of("effectiveFrom", effectiveFrom.toString(),
                "grossMonthly", gross.toPlainString(), "currency", currency));
        return history(userId).stream().filter(s -> s.id() == id).findFirst().orElseThrow();
    }
}
