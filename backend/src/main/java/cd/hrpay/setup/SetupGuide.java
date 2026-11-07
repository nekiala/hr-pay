package cd.hrpay.setup;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.tenancy.TenantContext;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The set-up guide of a new company: the steps from an empty company to its first payroll, in order. Most are read
 * from the company's data; the ones that are a review (grades, rates, schedules) the company marks done itself.
 */
@RestController
@RequestMapping("/api/setup")
@PreAuthorize("hasAuthority('company.manage')")
public class SetupGuide {

    /** done: finished; manual: the company marks it done; link: the page where it is done; count: what was found. */
    public record Step(String key, boolean done, boolean manual, String link, Integer count, OffsetDateTime markedAt,
                       String markedBy) {}

    public record Guide(List<Step> steps, int done, int total, boolean hidden) {}

    /** Steps the company marks done itself, because only a person can say the review is finished. */
    static final Set<String> MANUAL = Set.of("schedules", "grades", "rates", "payroll_settings");

    private final JdbcClient jdbc;
    private final AuditService audit;

    public SetupGuide(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Guide guide() {
        Map<String, Map<String, Object>> marked = new java.util.HashMap<>();
        jdbc.sql("select step, done_by, done_at from setup_step").query(rs -> {
            marked.put(rs.getString(1), Map.of("by", rs.getString(2), "at", rs.getObject(3, OffsetDateTime.class)));
        });
        int year = LocalDate.now().getYear();
        List<Step> steps = new ArrayList<>();
        boolean company = Boolean.TRUE.equals(jdbc.sql("""
                select address is not null and address <> '' and (tax_number is not null or cnss_number is not null)
                from company where id = current_company_id()""").query(Boolean.class).optional().orElse(false));
        steps.add(auto("company", company, "/company", null));
        int sites = count("select count(*) from site");
        steps.add(auto("sites", sites > 0, "/sites", sites));
        int units = count("select count(*) from org_unit where valid_to is null");
        steps.add(auto("structure", units > 1, "/org/manage", units));
        steps.add(manual("schedules", "/schedules", marked));
        steps.add(manual("grades", "/payroll/settings", marked));
        steps.add(manual("rates", "/payroll/rates", marked));
        steps.add(manual("payroll_settings", "/payroll/elements", marked));
        int holidays = count("select count(*) from holiday_occurrence where year = " + year);
        steps.add(auto("holidays", holidays > 0, "/holidays", holidays));
        int people = count("select count(*) from app_user where status = 'ACTIVE'");
        steps.add(auto("employees", people > 1, "/imports", people));
        int runs = count("select count(*) from payroll_run");
        steps.add(auto("first_payroll", runs > 0, "/payroll", runs));
        OffsetDateTime hidden = jdbc.sql("select setup_hidden_at from company where id = current_company_id()")
                .query(OffsetDateTime.class).optional().orElse(null);
        int done = (int) steps.stream().filter(Step::done).count();
        return new Guide(steps, done, steps.size(), hidden != null);
    }

    @PostMapping("/steps/{step}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void markDone(@PathVariable String step) {
        requireManual(step);
        jdbc.sql("insert into setup_step (step, done_by) values (?, ?) on conflict (company_id, step) do nothing")
                .params(step, CurrentUser.email()).update();
        audit.record("setup.step_done", "setup", step, null);
    }

    @DeleteMapping("/steps/{step}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void markNotDone(@PathVariable String step) {
        requireManual(step);
        jdbc.sql("delete from setup_step where step = ?").param(step).update();
        audit.record("setup.step_reopened", "setup", step, null);
    }

    /** Puts the guide away from the home page; it stays on its own page. */
    @PostMapping("/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void hide() {
        jdbc.sql("update company set setup_hidden_at = now() where id = current_company_id()").update();
    }

    @PostMapping("/show")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void show() {
        jdbc.sql("update company set setup_hidden_at = null where id = current_company_id()").update();
    }

    private static void requireManual(String step) {
        if (!MANUAL.contains(step)) {
            throw new BusinessRuleException("This step is done from its own page.");
        }
        if (TenantContext.companyId() == null) {
            throw new BusinessRuleException("No company.");
        }
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private static Step auto(String key, boolean done, String link, Integer count) {
        return new Step(key, done, false, link, count, null, null);
    }

    private static Step manual(String key, String link, Map<String, Map<String, Object>> marked) {
        Map<String, Object> m = marked.get(key);
        return new Step(key, m != null, true, link, null, m == null ? null : (OffsetDateTime) m.get("at"),
                m == null ? null : (String) m.get("by"));
    }
}
