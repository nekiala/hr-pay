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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The catalogue of pay elements, standing items per employee and one-off monthly entries. The payroll engine
 * reads {@link #standingFor} and {@link #entriesFor} to build a payslip.
 */
@Service
public class PayElements {

    public record Element(long id, String code, String name, String kind, String method, BigDecimal defaultAmount,
                          String defaultCurrency, BigDecimal percent, boolean subjectToCnss, boolean taxableIpr,
                          BigDecimal iprExemptCapPercent, boolean prorated, boolean defaultForAll, boolean system,
                          int displayOrder, boolean active) {}

    public record Assignment(long id, long userId, long elementId, String elementCode, String elementName,
                             BigDecimal amount, String currency, BigDecimal percent, boolean excluded,
                             LocalDate startMonth, LocalDate endMonth, String note, String createdBy,
                             OffsetDateTime createdAt) {}

    public record Entry(long id, long userId, String userName, long elementId, String elementCode, String elementName,
                        String kind, LocalDate month, BigDecimal amount, String currency, String note,
                        String createdBy, OffsetDateTime createdAt) {}

    private static final String ELEMENT_SELECT = """
            select id, code, name, kind, method, default_amount, trim(default_currency) as default_currency, percent,
                   subject_to_cnss, taxable_ipr, ipr_exempt_cap_percent, prorated, default_for_all, system,
                   display_order, active
            from pay_element""";

    private static final String ASSIGNMENT_SELECT = """
            select a.id, a.user_id, a.element_id, e.code as element_code, e.name as element_name, a.amount,
                   trim(a.currency) as currency, a.percent, a.excluded, a.start_month, a.end_month, a.note,
                   a.created_by, a.created_at
            from employee_pay_element a join pay_element e on e.id = a.element_id""";

    private static final String ENTRY_SELECT = """
            select p.id, p.user_id, person_name(u.first_name, u.middle_name, u.last_name) as user_name, p.element_id,
                   e.code as element_code, e.name as element_name, e.kind, p.month, p.amount,
                   trim(p.currency) as currency, p.note, p.created_by, p.created_at
            from pay_entry p join pay_element e on e.id = p.element_id join app_user u on u.id = p.user_id""";

    private final JdbcClient jdbc;
    private final UserService users;
    private final AuditService audit;

    public PayElements(JdbcClient jdbc, UserService users, AuditService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
    }

    // ---- catalogue -------------------------------------------------------------------------------------------

    public List<Element> elements() {
        return jdbc.sql(ELEMENT_SELECT + " order by display_order, code").query(Element.class).list();
    }

    public Element element(long id) {
        return jdbc.sql(ELEMENT_SELECT + " where id = ?").param(id).query(Element.class).optional()
                .orElseThrow(() -> new NotFoundException("Pay element not found."));
    }

    public Element element(String code) {
        return jdbc.sql(ELEMENT_SELECT + " where code = ?").param(code).query(Element.class).optional()
                .orElseThrow(() -> new NotFoundException("Pay element " + code + " not found."));
    }

    /** Creates or updates an element. A system element (calculated by the engine) only takes a new name and order. */
    @Transactional
    public Element save(Long id, Element e) {
        if (id != null && element(id).system()) {
            Element current = element(id);
            boolean earning = current.kind().equals("EARNING");
            // Built-in earnings keep their CNSS and IPR treatment configurable (for example the 13th month).
            jdbc.sql("update pay_element set name = ?, display_order = ?, subject_to_cnss = ?, taxable_ipr = ? where id = ?")
                    .params(e.name(), e.displayOrder(), earning ? e.subjectToCnss() : current.subjectToCnss(),
                            earning ? e.taxableIpr() : current.taxableIpr(), id).update();
            audit.record("pay_element.saved", "pay_element", id, Map.of("name", e.name()));
            return element(id);
        }
        if (e.method().equals("COMPUTED")) {
            throw new BusinessRuleException("Only the built-in elements are calculated by the payroll engine.");
        }
        if (e.method().equals("PERCENT_OF_BASIC") && e.percent() == null) {
            throw new BusinessRuleException("Give the percentage of basic salary.");
        }
        if (List.of("FIXED", "PER_DAY_WORKED", "PER_CHILD").contains(e.method())
                && (e.defaultAmount() == null || e.defaultCurrency() == null)) {
            throw new BusinessRuleException("Give the default amount and its currency.");
        }
        if (e.kind().equals("EMPLOYER_CHARGE")) {
            throw new BusinessRuleException("Employer charges are calculated from the statutory rates.");
        }
        boolean earning = e.kind().equals("EARNING");
        Object[] values = {e.name(), e.kind(), e.method(), e.defaultAmount(), e.defaultCurrency(), e.percent(),
                earning && e.subjectToCnss(), earning && e.taxableIpr(), earning ? e.iprExemptCapPercent() : null,
                e.prorated(), e.defaultForAll(), e.displayOrder(), e.active()};
        if (id == null) {
            if (jdbc.sql("select count(*) from pay_element where upper(code) = upper(?)").param(e.code())
                    .query(Integer.class).single() > 0) {
                throw new ConflictException("The pay element code " + e.code() + " is already used.");
            }
            id = jdbc.sql("""
                    insert into pay_element (name, kind, method, default_amount, default_currency, percent,
                        subject_to_cnss, taxable_ipr, ipr_exempt_cap_percent, prorated, default_for_all,
                        display_order, active, code)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(append(values, e.code().toUpperCase())).query(Long.class).single();
        } else {
            jdbc.sql("""
                    update pay_element set name = ?, kind = ?, method = ?, default_amount = ?, default_currency = ?,
                        percent = ?, subject_to_cnss = ?, taxable_ipr = ?, ipr_exempt_cap_percent = ?, prorated = ?,
                        default_for_all = ?, display_order = ?, active = ?
                    where id = ?""").params(append(values, id)).update();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", e.code());
        details.put("method", e.method());
        details.put("amount", e.defaultAmount() == null ? null : e.defaultAmount().toPlainString());
        details.put("percent", e.percent() == null ? null : e.percent().toPlainString());
        audit.record("pay_element.saved", "pay_element", id, details);
        return element(id);
    }

    private static Object[] append(Object[] values, Object last) {
        Object[] all = java.util.Arrays.copyOf(values, values.length + 1);
        all[values.length] = last;
        return all;
    }

    // ---- standing items per employee ---------------------------------------------------------------------------

    public List<Assignment> assignments(long userId) {
        return jdbc.sql(ASSIGNMENT_SELECT + " where a.user_id = ? order by e.display_order, a.start_month desc")
                .param(userId).query(Assignment.class).list();
    }

    /** The standing items in force for a month (their periods overlap it). */
    public List<Assignment> standingFor(long userId, YearMonth month) {
        LocalDate first = month.atDay(1);
        return jdbc.sql(ASSIGNMENT_SELECT + "\n" + """
                 where a.user_id = ? and a.start_month <= ? and (a.end_month is null or a.end_month >= ?)
                 order by e.display_order, a.start_month desc""").params(userId, first, first)
                .query(Assignment.class).list();
    }

    @Transactional
    public Assignment assign(long userId, long elementId, BigDecimal amount, String currency, BigDecimal percent,
                             boolean excluded, YearMonth start, YearMonth end, String note) {
        users.find(userId);
        notSelf(userId);
        Element e = element(elementId);
        if (e.method().equals("COMPUTED") || e.method().equals("MANUAL")) {
            throw new BusinessRuleException(e.name() + " cannot be a standing item: "
                    + (e.method().equals("MANUAL") ? "enter it for the month instead." : "the payroll engine calculates it."));
        }
        if (end != null && end.isBefore(start)) {
            throw new BusinessRuleException("The last month must not be before the first.");
        }
        if (!excluded) {
            if (e.method().equals("PERCENT_OF_BASIC") ? percent == null && amount == null : amount == null && percent == null) {
                throw new BusinessRuleException("Give the employee's amount or percentage, or exclude the element.");
            }
            if (amount != null && currency == null) {
                throw new BusinessRuleException("Give the amount's currency.");
            }
        }
        LocalDate from = start.atDay(1);
        LocalDate to = end == null ? null : end.atDay(1);
        int overlapping = jdbc.sql("""
                select count(*) from employee_pay_element where user_id = ? and element_id = ?
                  and start_month <= coalesce(?, date '9999-12-01') and (end_month is null or end_month >= ?)""")
                .params(userId, elementId, to, from).query(Integer.class).single();
        if (overlapping > 0) {
            throw new ConflictException("This employee already has " + e.name()
                    + " for some of these months. End the existing item first.");
        }
        long id = jdbc.sql("""
                insert into employee_pay_element (user_id, element_id, amount, currency, percent, excluded,
                                                  start_month, end_month, note, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(userId, elementId, excluded ? null : amount, excluded || amount == null ? null : currency,
                        excluded ? null : percent, excluded, from, to, note, CurrentUser.email())
                .query(Long.class).single();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("element", e.code());
        details.put("amount", amount == null ? null : amount.toPlainString());
        details.put("currency", currency);
        details.put("percent", percent == null ? null : percent.toPlainString());
        details.put("excluded", excluded);
        details.put("start", start.toString());
        audit.record("pay_element.assigned", "user", userId, details);
        return assignments(userId).stream().filter(a -> a.id() == id).findFirst().orElseThrow();
    }

    /** Ends a standing item after the given month (or removes it if it would end before it starts). */
    @Transactional
    public void endAssignment(long userId, long id, YearMonth lastMonth) {
        notSelf(userId);
        Assignment a = assignments(userId).stream().filter(x -> x.id() == id).findFirst()
                .orElseThrow(() -> new NotFoundException("Standing item not found."));
        if (lastMonth == null || lastMonth.atDay(1).isBefore(a.startMonth())) {
            jdbc.sql("delete from employee_pay_element where id = ?").param(id).update();
            audit.record("pay_element.removed", "user", userId, Map.of("element", a.elementCode()));
        } else {
            jdbc.sql("update employee_pay_element set end_month = ? where id = ?").params(lastMonth.atDay(1), id).update();
            audit.record("pay_element.ended", "user", userId,
                    Map.of("element", a.elementCode(), "lastMonth", lastMonth.toString()));
        }
    }

    // ---- one-off entries -----------------------------------------------------------------------------------------

    public List<Entry> entries(YearMonth month) {
        return jdbc.sql(ENTRY_SELECT + " where p.month = ? order by u.last_name, u.first_name, e.display_order")
                .param(month.atDay(1)).query(Entry.class).list();
    }

    public List<Entry> entriesFor(long userId, YearMonth month) {
        return jdbc.sql(ENTRY_SELECT + " where p.user_id = ? and p.month = ? order by e.display_order")
                .params(userId, month.atDay(1)).query(Entry.class).list();
    }

    @Transactional
    public Entry addEntry(long userId, long elementId, YearMonth month, BigDecimal amount, String currency, String note) {
        users.find(userId);
        notSelf(userId);
        Element e = element(elementId);
        if (e.method().equals("COMPUTED") || e.kind().equals("EMPLOYER_CHARGE")) {
            throw new BusinessRuleException(e.name() + " is calculated by the payroll engine.");
        }
        if (!e.active()) {
            throw new BusinessRuleException(e.name() + " is no longer in use.");
        }
        PayrollRuns.requireOpen(jdbc, month);
        if (jdbc.sql("select count(*) from pay_entry where user_id = ? and element_id = ? and month = ?")
                .params(userId, elementId, month.atDay(1)).query(Integer.class).single() > 0) {
            throw new ConflictException("This employee already has " + e.name() + " for " + month
                    + ". Change that entry instead.");
        }
        long id = jdbc.sql("""
                insert into pay_entry (user_id, element_id, month, amount, currency, note, created_by)
                values (?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(userId, elementId, month.atDay(1), amount, currency, note, CurrentUser.email())
                .query(Long.class).single();
        audit.record("pay_entry.added", "user", userId, Map.of("element", e.code(), "month", month.toString(),
                "amount", amount.toPlainString(), "currency", currency));
        return entriesFor(userId, month).stream().filter(x -> x.id() == id).findFirst().orElseThrow();
    }

    @Transactional
    public Entry updateEntry(long id, BigDecimal amount, String currency, String note) {
        Entry before = entry(id);
        notSelf(before.userId());
        PayrollRuns.requireOpen(jdbc, YearMonth.from(before.month()));
        jdbc.sql("update pay_entry set amount = ?, currency = ?, note = ? where id = ?")
                .params(amount, currency, note, id).update();
        audit.record("pay_entry.changed", "user", before.userId(), Map.of("element", before.elementCode(),
                "month", before.month().toString(), "from", before.amount().toPlainString(),
                "to", amount.toPlainString(), "currency", currency));
        return entry(id);
    }

    @Transactional
    public void removeEntry(long id) {
        Entry before = entry(id);
        notSelf(before.userId());
        PayrollRuns.requireOpen(jdbc, YearMonth.from(before.month()));
        jdbc.sql("delete from pay_entry where id = ?").param(id).update();
        audit.record("pay_entry.removed", "user", before.userId(), Map.of("element", before.elementCode(),
                "month", before.month().toString(), "amount", before.amount().toPlainString()));
    }

    private Entry entry(long id) {
        return jdbc.sql(ENTRY_SELECT + " where p.id = ?").param(id).query(Entry.class).optional()
                .orElseThrow(() -> new NotFoundException("Entry not found."));
    }

    private static void notSelf(long userId) {
        if (Objects.equals(userId, CurrentUser.id())) {
            throw new AccessDeniedException("You cannot change your own pay.");
        }
    }
}
