package cd.hrpay.user;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.i18n.Texts;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Whether someone is under contract on a day. Nobody works, takes leave, goes on mission or is paid without a contract
 * (DRC Code du travail art. 36 and 44: the written contract sets the start date and the pay). Accounts marked "not an
 * employee" never have one.
 */
@Service
public class Contracts {

    private final JdbcClient jdbc;

    public Contracts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean on(long userId, LocalDate day) {
        return jdbc.sql("""
                select exists (select 1 from employee_contract where user_id = ? and start_date <= ?
                               and (end_date is null or end_date >= ?))""")
                .params(userId, day, day).query(Boolean.class).single();
    }

    /** Whether the person has a contract at some time from one day to another. */
    public boolean during(long userId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select exists (select 1 from employee_contract where user_id = ? and start_date <= ?
                               and (end_date is null or end_date >= ?))""")
                .params(userId, to, from).query(Boolean.class).single();
    }

    public boolean any(long userId) {
        return jdbc.sql("select exists (select 1 from employee_contract where user_id = ?)").param(userId)
                .query(Boolean.class).single();
    }

    /** The people under contract on a day. */
    public Set<Long> underContract(LocalDate day) {
        return underContract(day, day);
    }

    /** The people under contract at some time from one day to another. */
    public Set<Long> underContract(LocalDate from, LocalDate to) {
        return new HashSet<>(jdbc.sql("""
                select distinct user_id from employee_contract where start_date <= ? and (end_date is null or end_date >= ?)""")
                .params(to, from).query(Long.class).list());
    }

    /** The people who have had a contract at any time. */
    public Set<Long> everUnderContract() {
        return new HashSet<>(jdbc.sql("select distinct user_id from employee_contract").query(Long.class).list());
    }

    /** Active employees (not marked "not an employee") with no contract on a day. */
    public List<Long> missing(LocalDate day) {
        return jdbc.sql("""
                select u.id from app_user u
                where u.status = 'ACTIVE' and not u.not_employee
                  and not exists (select 1 from employee_contract c where c.user_id = u.id and c.start_date <= ?
                                  and (c.end_date is null or c.end_date >= ?))
                order by u.last_name, u.first_name""").params(day, day).query(Long.class).list();
    }

    /** Refuses the person's own action (check-in, leave, mission) on a day without a contract. */
    public void requireOwn(long userId, LocalDate day) {
        if (!on(userId, day)) {
            throw new BusinessRuleException(Texts.t("You have no contract on %s. Contact HR.", Texts.day(day)));
        }
    }

    /** Refuses what HR records for someone (attendance, salary, leave) on a day without a contract. */
    public void require(long userId, LocalDate day) {
        if (java.util.Objects.equals(cd.hrpay.security.CurrentUser.id(), userId)) {
            requireOwn(userId, day);
        } else if (!on(userId, day)) {
            throw new BusinessRuleException(Texts.t("There is no contract on %s for this person: record the contract first.",
                    Texts.day(day)));
        }
    }
}
