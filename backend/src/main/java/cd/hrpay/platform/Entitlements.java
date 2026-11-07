package cd.hrpay.platform;

import cd.hrpay.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * What the current company may use: its modules and its size limit. In SaaS they come from the company's plan; on
 * premise from the licence. Going over the size limit only warns: payroll and everything else keep working.
 */
@Service
public class Entitlements {

    public enum Mode { SAAS, ON_PREMISE }

    public record Notice(String level, String text) {}

    public record Current(long companyId, String companyName, Mode mode, String planCode, String planName,
                          Set<String> modules, Integer maxEmployees, int employees, boolean locked,
                          List<Notice> notices) {
        public boolean has(String module) {
            return modules.contains(module);
        }
    }

    private final JdbcClient jdbc;
    private final Licences licences;
    private final Mode mode;

    public Entitlements(JdbcClient jdbc, Licences licences, @Value("${app.licensing.mode:saas}") String mode) {
        this.jdbc = jdbc;
        this.licences = licences;
        this.mode = "on-premise".equalsIgnoreCase(mode) || "on_premise".equalsIgnoreCase(mode) ? Mode.ON_PREMISE : Mode.SAAS;
    }

    public Mode mode() {
        return mode;
    }

    /** The signed-in user's company; null outside a company. */
    public Current current() {
        Long companyId = TenantContext.companyId();
        if (companyId == null) {
            return null;
        }
        record Row(String name, String planCode, String planName, Integer maxEmployees, String modules) {}
        Row c = jdbc.sql("""
                select c.name, c.plan_code, p.name as plan_name, p.max_employees, array_to_string(c.modules, ',') as modules
                from company c join plan p on p.code = c.plan_code where c.id = current_company_id()""")
                .query(Row.class).optional().orElse(null);
        if (c == null) {
            return null;
        }
        int employees = jdbc.sql("select count(*) from app_user where status = 'ACTIVE'").query(Integer.class).single();
        Set<String> modules = new LinkedHashSet<>();
        for (String m : c.modules().split(",")) {
            if (!m.isBlank()) {
                modules.add(m);
            }
        }
        Integer max = c.maxEmployees();
        boolean locked = false;
        List<Notice> notices = new ArrayList<>();
        if (mode == Mode.ON_PREMISE) {
            Licences.Status status = licences.status();
            if (status.licence() != null) {
                modules.retainAll(status.licence().modules());
                max = status.licence().maxEmployees();
            } else {
                max = Licences.EVALUATION_EMPLOYEES;
            }
            locked = status.locked();
            if (status.message() != null) {
                notices.add(new Notice(locked ? "danger" : "warn", status.message()));
            }
        }
        if (max != null && employees > max) {
            notices.add(new Notice("warn", mode == Mode.SAAS
                    ? "%d active employees: your plan (%s) covers up to %d. Move to the next plan; nothing is blocked meanwhile."
                            .formatted(employees, c.planName(), max)
                    : "%d active employees: the licence covers up to %d. Ask your provider for a larger licence; nothing is blocked meanwhile."
                            .formatted(employees, max)));
        }
        return new Current(companyId, c.name(), mode, c.planCode(), c.planName(), modules, max, employees, locked, notices);
    }
}
