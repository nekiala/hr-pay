package cd.hrpay.platform;

import cd.hrpay.i18n.Texts;

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
                          String readOnly, List<Notice> notices, String country, String language, String localCurrency) {
        public boolean has(String module) {
            return modules.contains(module);
        }

        /** Suspended: everyone reads and exports, nobody changes anything. */
        public boolean isReadOnly() {
            return readOnly != null;
        }
    }

    private final JdbcClient jdbc;
    private final Licences licences;
    private final Mode mode;
    private final cd.hrpay.billing.Billing billing;
    private final java.time.Clock clock;

    public Entitlements(JdbcClient jdbc, Licences licences, @Value("${app.licensing.mode:saas}") String mode,
                        @org.springframework.context.annotation.Lazy cd.hrpay.billing.Billing billing, java.time.Clock clock) {
        this.jdbc = jdbc;
        this.licences = licences;
        this.billing = billing;
        this.clock = clock;
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
        record Row(String name, String planCode, String planName, Integer maxEmployees, String modules, String status,
                   String suspendedReason, String countryCode, String language, String localCurrency) {}
        Row c = jdbc.sql("""
                select c.name, c.plan_code, p.name as plan_name, p.max_employees, array_to_string(c.modules, ',') as modules,
                       c.status, c.suspended_reason, c.country_code, c.language, c.local_currency
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
                notices.add(new Notice(locked ? "danger" : "warn", Texts.translate(status.message())));
            }
        }
        String readOnly = !"SUSPENDED".equals(c.status()) ? null : "UNPAID".equals(c.suspendedReason())
                ? Texts.t("HR Pay is read-only: an invoice is unpaid past its grace period. Everything can still be read and exported; changes come back as soon as the payment is recorded.")
                : Texts.t("HR Pay is read-only: the provider has suspended this company. Everything can still be read and exported.");
        if (mode == Mode.SAAS && !TenantContext.isProvider(companyId)) {
            billingNotices(companyId, readOnly != null, notices);
        }
        if (max != null && employees > max) {
            notices.add(new Notice("warn", mode == Mode.SAAS
                    ? Texts.t("%d active employees: your plan (%s) covers up to %d. Move to the next plan; nothing is blocked meanwhile.", employees, c.planName(), max)
                    : Texts.t("%d active employees: the licence covers up to %d. Ask your provider for a larger licence; nothing is blocked meanwhile.", employees, max)));
        }
        return new Current(companyId, c.name(), mode, c.planCode(), c.planName(), modules, max, employees, locked,
                readOnly, notices, c.countryCode(), c.language(), c.localCurrency());
    }

    private void billingNotices(long companyId, boolean readOnly, List<Notice> notices) {
        java.time.LocalDate today = java.time.LocalDate.now(clock);
        cd.hrpay.billing.Billing.Standing s = billing.standing(companyId, today);
        if (s == null) {
            return;
        }
        var sub = s.subscription();
        if ("TRIAL".equals(sub.status()) && sub.trialEnds() != null && !sub.trialEnds().isAfter(today.plusDays(7))) {
            notices.add(new Notice("warn", Texts.t("The free trial ends on %s. The first invoice follows; see Billing.", cd.hrpay.billing.Billing.day(sub.trialEnds()))));
        }
        if (readOnly) {
            return;
        }
        for (var i : s.open()) {
            if (i.dueOn().isBefore(today)) {
                String until = s.readOnlyFrom() == null ? "" : Texts.t(" HR Pay becomes read-only on %s if it is still unpaid.", cd.hrpay.billing.Billing.day(s.readOnlyFrom()));
                notices.add(new Notice("danger", Texts.t("Invoice %s (%s %s) was due on %s.%s", i.number(),
                        cd.hrpay.billing.Billing.amount(i.balance()), i.currency(), cd.hrpay.billing.Billing.day(i.dueOn()), until)));
            } else if (!i.dueOn().isAfter(today.plusDays(7))) {
                notices.add(new Notice("warn", Texts.t("Invoice %s (%s %s) is due on %s.", i.number(),
                        cd.hrpay.billing.Billing.amount(i.balance()), i.currency(), cd.hrpay.billing.Billing.day(i.dueOn()))));
            }
        }
    }
}
