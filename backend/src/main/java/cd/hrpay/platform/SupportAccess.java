package cd.hrpay.platform;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.tenancy.TenantContext;
import cd.hrpay.tenancy.Tenants;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The provider's support access to a company. Only the company's administrator opens it, for a few days at most, and
 * can close it at any time. While it is open the provider's administrators can look at the company's data, read-only;
 * every request they make is kept, and the start and the end are in both companies' audit logs.
 */
@Service
public class SupportAccess {

    /** The HTTP session attribute that holds a running support visit. */
    public static final String SESSION_KEY = "hrpay.support";
    private static final String REQUEST_KEY = "hrpay.support.active";

    public static final int MAX_DAYS = 14;

    public record Visit(long companyId, long grantId, String companyName, OffsetDateTime expiresAt) implements Serializable {}

    public record Grant(long id, String grantedBy, OffsetDateTime grantedAt, OffsetDateTime expiresAt, String reason,
                        OffsetDateTime revokedAt, String revokedBy, boolean active, int requests,
                        OffsetDateTime lastRequestAt) {}

    public record Request(String visitor, String method, String path, OffsetDateTime at) {}

    private final JdbcClient jdbc;
    private final Tenants tenants;
    private final AuditService audit;

    public SupportAccess(JdbcClient jdbc, Tenants tenants, AuditService audit) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.audit = audit;
    }

    /** The support visit this request runs in, if any. */
    public static Visit current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : (Visit) attributes.getAttribute(REQUEST_KEY, RequestAttributes.SCOPE_REQUEST);
    }

    public static void markCurrent(jakarta.servlet.http.HttpServletRequest request, Visit visit) {
        request.setAttribute(REQUEST_KEY, visit);
    }

    // ---- the company's side ----

    /** Opens access for the current company, for 1 to 14 days. */
    public Grant grant(int days, String reason, String by) {
        if (days < 1 || days > MAX_DAYS) {
            throw new BusinessRuleException("Support access lasts 1 to " + MAX_DAYS + " days.");
        }
        Long companyId = TenantContext.companyId();
        if (companyId == null || TenantContext.isProvider(companyId)) {
            throw new BusinessRuleException("Support access is for the companies the provider serves.");
        }
        long id = jdbc.sql("""
                insert into support_grant (granted_by, expires_at, reason) values (?, now() + make_interval(days => ?), ?)
                returning id""").params(by, days, reason == null || reason.isBlank() ? null : reason.trim())
                .query(Long.class).single();
        audit.record("support.granted", "support_grant", id, Map.of("days", days));
        tenants.runAs(TenantContext.providerCompany(),
                () -> audit.recordAs(by, "support.granted", "company", companyId, Map.of("days", days, "grant", id)));
        return grants(companyId).stream().filter(g -> g.id() == id).findFirst().orElseThrow();
    }

    public void revoke(long grantId, String by) {
        Long companyId = TenantContext.companyId();
        int n = jdbc.sql("""
                update support_grant set revoked_at = now(), revoked_by = ?
                where id = ? and company_id = current_company_id() and revoked_at is null""")
                .params(by, grantId).update();
        if (n == 0) {
            throw new NotFoundException("No open support access with this number.");
        }
        audit.record("support.revoked", "support_grant", grantId, Map.of());
        tenants.runAs(TenantContext.providerCompany(),
                () -> audit.recordAs(by, "support.revoked", "company", companyId, Map.of("grant", grantId)));
    }

    public List<Grant> grants(long companyId) {
        return tenants.callAsPlatform(() -> jdbc.sql("""
                select g.id, g.granted_by, g.granted_at, g.expires_at, g.reason, g.revoked_at, g.revoked_by,
                       (g.revoked_at is null and g.expires_at > now()) as active,
                       (select count(*) from support_visit v where v.grant_id = g.id) as requests,
                       (select max(v.at) from support_visit v where v.grant_id = g.id) as last_request_at
                from support_grant g where g.company_id = ? order by g.granted_at desc limit 50""")
                .param(companyId).query(Grant.class).list());
    }

    public List<Request> requests(long companyId, int limit) {
        return tenants.callAsPlatform(() -> jdbc.sql("""
                select visitor, method, path, at from support_visit where company_id = ? order by at desc, id desc limit ?""")
                .params(companyId, limit).query(Request.class).list());
    }

    public Optional<Grant> active(long companyId) {
        return grants(companyId).stream().filter(Grant::active).findFirst();
    }

    // ---- the provider's side ----

    public Visit start(long companyId, String visitor) {
        if (TenantContext.isProvider(companyId)) {
            throw new BusinessRuleException("This is your own company.");
        }
        Grant g = active(companyId).orElseThrow(() -> new BusinessRuleException(
                "The company has not opened support access. Ask its administrator to open it from the Billing page."));
        String name = tenants.callAsPlatform(() -> jdbc.sql("select name from company where id = ?").param(companyId)
                .query(String.class).single());
        audit.record("support.started", "company", companyId, Map.of("grant", g.id()));
        tenants.runAs(companyId, () -> audit.recordAs(visitor, "support.started", "support_grant", g.id(), Map.of()));
        return new Visit(companyId, g.id(), name, g.expiresAt());
    }

    /** Whether the visit's grant is still open: the company can close it while the provider is in. */
    public boolean valid(Visit visit) {
        return tenants.callAsPlatform(() -> jdbc.sql("""
                select count(*) from support_grant where id = ? and company_id = ? and revoked_at is null and expires_at > now()""")
                .params(visit.grantId(), visit.companyId()).query(Integer.class).single()) > 0;
    }

    public void log(Visit visit, String visitor, String method, String path) {
        String p = path.length() > 300 ? path.substring(0, 300) : path;
        tenants.callAsPlatform(() -> jdbc.sql("""
                insert into support_visit (company_id, grant_id, visitor, method, path) values (?, ?, ?, ?, ?)""")
                .params(visit.companyId(), visit.grantId(), visitor, method, p).update());
    }

    public void end(Visit visit, String visitor) {
        tenants.runAs(visit.companyId(), () -> audit.recordAs(visitor, "support.ended", "support_grant", visit.grantId(), Map.of()));
        tenants.runAs(TenantContext.providerCompany(),
                () -> audit.recordAs(visitor, "support.ended", "company", visit.companyId(), Map.of("grant", visit.grantId())));
    }
}
