package cd.hrpay.firm;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.Notifications;
import cd.hrpay.payroll.PayrollChecks;
import cd.hrpay.tenancy.TenantContext;
import cd.hrpay.tenancy.Tenants;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The accountant portal. An accounting firm is a company of kind FIRM with its own staff. A client company's
 * administrator invites the firm, chooses which of the client's roles it gets and accepts the data processing clause;
 * the firm accepts and chooses who pays HR Pay for that client. The firm's administrators work for all its clients,
 * its other staff for the clients they are assigned to. Either side can end the link at any time.
 */
@Service
public class Firms {

    private static final Logger log = LoggerFactory.getLogger(Firms.class);

    /** The HTTP session attribute that holds a firm member's visit to a client. */
    public static final String SESSION_KEY = "hrpay.firm";
    private static final String REQUEST_KEY = "hrpay.firm.active";

    /** The version of the data processing clause the client's administrator accepts when inviting a firm. */
    public static final String CLAUSE_VERSION = "2026-11";

    /** Permissions a firm never gets in a client, whatever roles it was given. */
    static final Set<String> NEVER = Set.of("platform.manage", "system.monitor", "firm.manage", "subscription.manage");

    public static final Set<String> BILLING = Set.of("COMMISSION", "WHOLESALE");

    public record Visit(long linkId, long firmId, String firmName, long clientId, String clientName) implements Serializable {}

    /** A link as the client's administrator sees it. */
    public record ClientSide(long id, long firmId, String firmName, String firmRegistration, String firmEmail,
                             String status, List<String> roleCodes, String billing, String invitedBy,
                             OffsetDateTime invitedAt, String answeredBy, OffsetDateTime answeredAt, String endedBy,
                             OffsetDateTime endedAt, String endReason, int pagesOpened, OffsetDateTime lastVisitAt) {}

    /** A link as the firm sees it, with how the client's payroll stands. */
    public record FirmSide(long id, long clientId, String clientName, String countryCode, String status,
                           List<String> roleNames, String billing, String invitedBy, OffsetDateTime invitedAt,
                           int employees, Run lastRun, Integer openChecks, int openInvoices, boolean overdue,
                           String companyStatus, List<Long> staff) {}

    public record Run(String month, String status) {}

    public record Role(String code, String name, String description) {}

    public record Staff(long id, String name, String email, boolean admin) {}

    public record Visited(String visitor, String method, String path, OffsetDateTime at) {}

    public record Commission(long id, long firmId, String firmName, long clientId, String clientName, String invoiceNumber,
                             BigDecimal base, BigDecimal percent, BigDecimal amount, String currency,
                             OffsetDateTime createdAt, OffsetDateTime paidOutAt, String paidOutBy, String paidOutRef) {}

    public record FirmInvoice(long id, long clientId, String clientName, String number, LocalDate issuedOn,
                              LocalDate dueOn, String currency, BigDecimal total, BigDecimal discountPercent,
                              String status, boolean overdue) {}

    public record Terms(BigDecimal commissionPercent, BigDecimal discountPercent) {}

    public record Wholesale(long firmId, String firmName, String address, String taxNumber, BigDecimal discountPercent) {}

    private final JdbcClient jdbc;
    private final Tenants tenants;
    private final AuditService audit;
    private final Notifications notifications;
    private final PayrollChecks checks;

    public Firms(JdbcClient jdbc, Tenants tenants, AuditService audit, Notifications notifications, PayrollChecks checks) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.audit = audit;
        this.notifications = notifications;
        this.checks = checks;
    }

    // ---- the visit in a request ----

    /** The client visit this request runs in, if any. */
    public static Visit current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : (Visit) attributes.getAttribute(REQUEST_KEY, RequestAttributes.SCOPE_REQUEST);
    }

    public static void markCurrent(jakarta.servlet.http.HttpServletRequest request, Visit visit) {
        request.setAttribute(REQUEST_KEY, visit);
    }

    /** How a firm member appears in the client's audit log. */
    public static String actor(String email) {
        Visit v = current();
        if (v == null) {
            return email;
        }
        String s = email + " (" + v.firmName() + ")";
        return s.length() > 254 ? s.substring(0, 254) : s;
    }

    // ---- companies ----

    public boolean isFirm(Long companyId) {
        return companyId != null && tenants.callAsPlatform(() -> jdbc.sql("select count(*) from company where id = ? and kind = 'FIRM'")
                .param(companyId).query(Integer.class).single()) > 0;
    }

    private String name(long companyId) {
        return tenants.callAsPlatform(() -> jdbc.sql("select name from company where id = ?").param(companyId)
                .query(String.class).single());
    }

    // ---- the client's side ----

    /** The current company's roles a firm can be given. */
    public List<Role> clientRoles() {
        return jdbc.sql("select code, name, description from role order by system_role desc, name").query(Role.class).list();
    }

    public List<ClientSide> forClient() {
        long client = TenantContext.companyId();
        return tenants.callAsPlatform(() -> jdbc.sql("""
                select l.id, l.firm_id, c.name as firm_name, c.firm_registration,
                       (select u.email from app_user u join user_role ur on ur.user_id = u.id join role r on r.id = ur.role_id
                        where u.company_id = l.firm_id and r.code = 'ADMIN' and u.status = 'ACTIVE' order by u.id limit 1) as firm_email,
                       l.status, l.role_codes, l.billing, l.invited_by, l.invited_at, l.answered_by, l.answered_at,
                       l.ended_by, l.ended_at, l.end_reason,
                       (select count(*) from firm_visit v where v.link_id = l.id) as pages_opened,
                       (select max(v.at) from firm_visit v where v.link_id = l.id) as last_visit_at
                from firm_client l join company c on c.id = l.firm_id where l.client_id = ?
                order by case l.status when 'ACTIVE' then 0 when 'INVITED' then 1 else 2 end, l.invited_at desc""")
                .param(client).query((rs, i) -> new ClientSide(rs.getLong("id"), rs.getLong("firm_id"), rs.getString("firm_name"),
                        rs.getString("firm_registration"), rs.getString("firm_email"), rs.getString("status"),
                        List.of((String[]) rs.getArray("role_codes").getArray()), rs.getString("billing"),
                        rs.getString("invited_by"), rs.getObject("invited_at", OffsetDateTime.class), rs.getString("answered_by"),
                        rs.getObject("answered_at", OffsetDateTime.class), rs.getString("ended_by"),
                        rs.getObject("ended_at", OffsetDateTime.class), rs.getString("end_reason"), rs.getInt("pages_opened"),
                        rs.getObject("last_visit_at", OffsetDateTime.class))).list());
    }

    /** Invites the firm whose staff member has this email address. */
    @Transactional
    public ClientSide invite(String firmEmail, List<String> roleCodes, boolean clauseAccepted, String by) {
        long client = TenantContext.companyId();
        if (isFirm(client)) {
            throw new BusinessRuleException("An accounting firm cannot invite another firm.");
        }
        if (!clauseAccepted) {
            throw new BusinessRuleException("Accept the data processing clause to give a firm access to your company.");
        }
        Set<String> roles = new LinkedHashSet<>(roleCodes == null ? List.of() : roleCodes);
        if (roles.isEmpty()) {
            throw new BusinessRuleException("Choose at least one role for the firm.");
        }
        Set<String> known = new java.util.HashSet<>(clientRoles().stream().map(Role::code).toList());
        if (!known.containsAll(roles)) {
            throw new BusinessRuleException("Unknown role.");
        }
        Long firm = firmEmail == null ? null : tenants.companyOfEmail(firmEmail.trim().toLowerCase());
        if (firm == null || !isFirm(firm)) {
            throw new BusinessRuleException("No accounting firm on HR Pay uses this email address. Ask the firm for the email address of its administrator.");
        }
        boolean open = tenants.callAsPlatform(() -> jdbc.sql("""
                select count(*) from firm_client where firm_id = ? and client_id = ? and status in ('INVITED', 'ACTIVE')""")
                .params(firm, client).query(Integer.class).single()) > 0;
        if (open) {
            throw new BusinessRuleException("This firm is already invited or working for your company.");
        }
        long id = tenants.callAsPlatform(() -> jdbc.sql("""
                insert into firm_client (firm_id, client_id, role_codes, clause_version, invited_by)
                values (?, ?, ?, ?, ?) returning id""")
                .params(firm, client, roles.toArray(String[]::new), CLAUSE_VERSION, by).query(Long.class).single());
        String clientName = name(client);
        audit.record("firm.invited", "firm_client", id, Map.of("firm", name(firm), "roles", List.copyOf(roles),
                "clause", CLAUSE_VERSION));
        tenants.runAs(firm, () -> {
            audit.recordAs(by, "firm.invited", "firm_client", id, Map.of("client", clientName));
            tellFirmAdmins(() -> Texts.t("%s invites your firm", clientName),
                    () -> Texts.t("%s asks your firm to work on its payroll in HR Pay. Accept it on the Clients page.", clientName));
        });
        return forClient().stream().filter(l -> l.id() == id).findFirst().orElseThrow();
    }

    /** Changes the roles of an open link; the firm's staff get the new rights on their next page. */
    public ClientSide changeRoles(long linkId, List<String> roleCodes, String by) {
        ClientSide l = clientLink(linkId);
        if (!Set.of("INVITED", "ACTIVE").contains(l.status())) {
            throw new BusinessRuleException("This link has ended.");
        }
        Set<String> roles = new LinkedHashSet<>(roleCodes == null ? List.of() : roleCodes);
        Set<String> known = new java.util.HashSet<>(clientRoles().stream().map(Role::code).toList());
        if (roles.isEmpty() || !known.containsAll(roles)) {
            throw new BusinessRuleException("Choose at least one role for the firm.");
        }
        tenants.callAsPlatform(() -> jdbc.sql("update firm_client set role_codes = ? where id = ?")
                .params(roles.toArray(String[]::new), linkId).update());
        audit.record("firm.roles_changed", "firm_client", linkId, Map.of("roles", List.copyOf(roles)));
        return clientLink(linkId);
    }

    /** The client ends the link: the firm's staff lose access at once. */
    public ClientSide endByClient(long linkId, String reason, String by) {
        ClientSide l = clientLink(linkId);
        end(linkId, l.firmId(), TenantContext.companyId(), reason, by);
        String clientName = name(TenantContext.companyId());
        tenants.runAs(l.firmId(), () -> tellFirmAdmins(() -> Texts.t("%s ended your access", clientName),
                () -> Texts.t("%s ended your firm's access to its company in HR Pay.", clientName)));
        return clientLink(linkId);
    }

    public List<Visited> visits(long linkId) {
        clientLink(linkId);
        return tenants.callAsPlatform(() -> jdbc.sql("""
                select visitor, method, path, at from firm_visit where link_id = ? order by at desc, id desc limit 100""")
                .param(linkId).query(Visited.class).list());
    }

    private ClientSide clientLink(long linkId) {
        return forClient().stream().filter(l -> l.id() == linkId).findFirst()
                .orElseThrow(() -> new NotFoundException("Firm link not found."));
    }

    // ---- the firm's side ----

    private long requireFirm() {
        Long company = TenantContext.companyId();
        if (current() != null || !isFirm(company)) {
            throw new BusinessRuleException("This page is for accounting firms.");
        }
        return company;
    }

    /** The firm's clients: all of them for its administrators, the assigned ones for its other staff. */
    public List<FirmSide> forFirm(long userId, boolean admin) {
        long firm = requireFirm();
        record Row(long id, long clientId, String clientName, String countryCode, String status, String roleCodes,
                   String billing, String invitedBy, OffsetDateTime invitedAt, String companyStatus) {}
        List<Row> rows = tenants.callAsPlatform(() -> jdbc.sql("""
                select l.id, l.client_id, c.name as client_name, c.country_code, l.status,
                       array_to_string(l.role_codes, ',') as role_codes, l.billing,
                       l.invited_by, l.invited_at, c.status as company_status
                from firm_client l join company c on c.id = l.client_id
                where l.firm_id = ? and (l.status = 'ACTIVE' or (l.status = 'INVITED' and ?))
                  and (? or exists (select 1 from firm_client_staff s where s.link_id = l.id and s.user_id = ?))
                order by case l.status when 'INVITED' then 0 else 1 end, c.name""")
                .params(firm, admin, admin, userId).query(Row.class).list());
        List<FirmSide> out = new ArrayList<>();
        for (Row r : rows) {
            List<Long> staff = tenants.callAsPlatform(() -> jdbc.sql("select user_id from firm_client_staff where link_id = ? order by user_id")
                    .param(r.id()).query(Long.class).list());
            record Summary(int employees, Run run, Integer checks, int openInvoices, boolean overdue, List<String> roles) {}
            Summary s = tenants.callAs(r.clientId(), () -> {
                int employees = jdbc.sql("select count(*) from app_user where status = 'ACTIVE'").query(Integer.class).single();
                List<String> roles = jdbc.sql("select name from role where code = any(?) order by name")
                        .param(r.roleCodes().split(",")).query(String.class).list();
                if (!"ACTIVE".equals(r.status())) {
                    return new Summary(employees, null, null, 0, false, roles);
                }
                Run run = jdbc.sql("select to_char(month, 'YYYY-MM') as month, status from payroll_run order by month desc limit 1")
                        .query(Run.class).optional().orElse(null);
                Integer open = null;
                if (run != null && "CALCULATED".equals(run.status())) {
                    try {
                        PayrollChecks.Report report = checks.report(YearMonth.parse(run.month()));
                        open = report.legal() + report.warnings();
                    } catch (RuntimeException e) {
                        log.warn("Checks of company {} not read: {}", r.clientId(), e.getMessage());
                    }
                }
                int invoices = jdbc.sql("select count(*) from invoice where status = 'OPEN'").query(Integer.class).single();
                boolean overdue = jdbc.sql("select count(*) from invoice where status = 'OPEN' and due_on < current_date")
                        .query(Integer.class).single() > 0;
                return new Summary(employees, run, open, invoices, overdue, roles);
            });
            out.add(new FirmSide(r.id(), r.clientId(), r.clientName(), r.countryCode(), r.status(), s.roles(), r.billing(),
                    r.invitedBy(), r.invitedAt(), s.employees(), s.run(), s.checks(), s.openInvoices(), s.overdue(),
                    r.companyStatus(), staff));
        }
        return out;
    }

    /** The firm accepts an invitation and chooses who pays HR Pay for that client. */
    @Transactional
    public void accept(long linkId, String billing, String by) {
        long firm = requireFirm();
        if (billing == null || !BILLING.contains(billing)) {
            throw new BusinessRuleException("Choose who pays HR Pay for this client: the client, or your firm at the wholesale price.");
        }
        long client = firmLink(firm, linkId, "INVITED");
        tenants.callAsPlatform(() -> jdbc.sql("""
                update firm_client set status = 'ACTIVE', billing = ?, answered_by = ?, answered_at = now() where id = ?""")
                .params(billing, by, linkId).update());
        String firmName = name(firm);
        audit.record("firm.accepted", "firm_client", linkId, Map.of("client", name(client), "billing", billing));
        tenants.runAs(client, () -> {
            audit.recordAs(by, "firm.accepted", "firm_client", linkId, Map.of("firm", firmName, "billing", billing));
            notifications.sendToHolders("firm.manage", "firm.accepted", () -> Texts.t("%s accepted your invitation", firmName),
                    () -> Texts.t("The firm's staff can now work in HR Pay with the rights you chose."), "/company/accountant");
        });
    }

    public void decline(long linkId, String by) {
        long firm = requireFirm();
        long client = firmLink(firm, linkId, "INVITED");
        tenants.callAsPlatform(() -> jdbc.sql("""
                update firm_client set status = 'DECLINED', answered_by = ?, answered_at = now() where id = ?""")
                .params(by, linkId).update());
        audit.record("firm.declined", "firm_client", linkId, Map.of("client", name(client)));
        String firmName = name(firm);
        tenants.runAs(client, () -> audit.recordAs(by, "firm.declined", "firm_client", linkId, Map.of("firm", firmName)));
    }

    /** The firm stops working for a client. */
    public void leave(long linkId, String reason, String by) {
        long firm = requireFirm();
        long client = firmLink(firm, linkId, "ACTIVE");
        end(linkId, firm, client, reason, by);
        String firmName = name(firm);
        tenants.runAs(client, () -> notifications.sendToHolders("firm.manage", "firm.ended",
                () -> Texts.t("%s stopped working for your company", firmName),
                () -> Texts.t("The firm's access to your company in HR Pay has ended."), "/company/accountant"));
    }

    public List<Staff> staffOptions() {
        requireFirm();
        return jdbc.sql("""
                select u.id, u.first_name || ' ' || u.last_name as name, u.email,
                       exists (select 1 from user_role ur join role_permission rp on rp.role_id = ur.role_id
                               where ur.user_id = u.id and rp.permission_code = 'firm.manage') as admin
                from app_user u where u.status = 'ACTIVE' order by u.last_name, u.first_name""").query(Staff.class).list();
    }

    @Transactional
    public void setStaff(long linkId, List<Long> userIds, String by) {
        long firm = requireFirm();
        firmLink(firm, linkId, "ACTIVE");
        Set<Long> ids = new LinkedHashSet<>(userIds == null ? List.of() : userIds);
        Set<Long> known = new java.util.HashSet<>(staffOptions().stream().map(Staff::id).toList());
        if (!known.containsAll(ids)) {
            throw new BusinessRuleException("Choose people of your firm.");
        }
        tenants.callAsPlatform(() -> {
            jdbc.sql("delete from firm_client_staff where link_id = ?").param(linkId).update();
            for (Long id : ids) {
                jdbc.sql("insert into firm_client_staff (link_id, user_id) values (?, ?)").params(linkId, id).update();
            }
            return null;
        });
        audit.record("firm.staff_assigned", "firm_client", linkId, Map.of("staff", List.copyOf(ids)));
    }

    /** Starts working in a client: checked, then kept in both companies' audit logs. */
    public Visit open(long linkId, long userId, boolean admin, String by) {
        long firm = requireFirm();
        long client = firmLink(firm, linkId, "ACTIVE");
        Visit v = new Visit(linkId, firm, name(firm), client, name(client));
        if (!allowed(v, userId, admin)) {
            throw new BusinessRuleException("Your firm's administrator has not given you this client.");
        }
        audit.record("firm.visit_started", "firm_client", linkId, Map.of("client", v.clientName()));
        tenants.runAs(client, () -> audit.recordAs(by + " (" + v.firmName() + ")", "firm.visit_started", "firm_client", linkId, Map.of()));
        return v;
    }

    /** Whether the person may still work in the client: the link is active and they are an administrator or assigned. */
    public boolean allowed(Visit v, long userId, boolean admin) {
        return tenants.callAsPlatform(() -> jdbc.sql("""
                select count(*) from firm_client l join company c on c.id = l.firm_id
                where l.id = ? and l.firm_id = ? and l.client_id = ? and l.status = 'ACTIVE' and c.kind = 'FIRM'
                  and (? or exists (select 1 from firm_client_staff s where s.link_id = l.id and s.user_id = ?))""")
                .params(v.linkId(), v.firmId(), v.clientId(), admin, userId).query(Integer.class).single()) > 0;
    }

    /** The permissions the firm has in the client: those of the roles it was given, never the platform's or billing. */
    public List<String> permissions(Visit v) {
        String[] roles = tenants.callAsPlatform(() -> jdbc.sql("select role_codes from firm_client where id = ?")
                .param(v.linkId()).query((rs, i) -> (String[]) rs.getArray(1).getArray()).single());
        return tenants.callAs(v.clientId(), () -> jdbc.sql("""
                select distinct rp.permission_code from role_permission rp join role r on r.id = rp.role_id
                where r.code = any(?) order by 1""").param(roles).query(String.class).list())
                .stream().filter(p -> !NEVER.contains(p)).toList();
    }

    /** Keeps the pages a firm member opens: one line per page every ten minutes, and every change. */
    public void log(Visit v, String visitor, String method, String path) {
        String p = path.length() > 300 ? path.substring(0, 300) : path;
        tenants.callAsPlatform(() -> jdbc.sql("""
                insert into firm_visit (link_id, visitor, method, path)
                select ?, ?, ?, ? where ? <> 'GET' or not exists (
                    select 1 from firm_visit where link_id = ? and visitor = ? and path = ? and at > now() - interval '10 minutes')""")
                .params(v.linkId(), visitor, method, p, method, v.linkId(), visitor, p).update());
    }

    public void endVisit(Visit v, String visitor) {
        tenants.runAs(v.clientId(), () -> audit.recordAs(visitor + " (" + v.firmName() + ")", "firm.visit_ended", "firm_client",
                v.linkId(), Map.of()));
    }

    private long firmLink(long firm, long linkId, String status) {
        return tenants.callAsPlatform(() -> jdbc.sql("select client_id from firm_client where id = ? and firm_id = ? and status = ?")
                .params(linkId, firm, status).query(Long.class).optional())
                .orElseThrow(() -> new NotFoundException("Client not found."));
    }

    private void end(long linkId, long firm, long client, String reason, String by) {
        int n = tenants.callAsPlatform(() -> jdbc.sql("""
                update firm_client set status = case when status = 'INVITED' then 'DECLINED' else 'ENDED' end,
                       ended_by = ?, ended_at = now(), end_reason = ?
                where id = ? and status in ('INVITED', 'ACTIVE')""")
                .params(by, reason == null || reason.isBlank() ? null : reason.trim(), linkId).update());
        if (n == 0) {
            throw new BusinessRuleException("This link has already ended.");
        }
        Map<String, Object> details = reason == null || reason.isBlank() ? Map.of() : Map.of("reason", reason.trim());
        tenants.runAs(client, () -> audit.recordAs(by, "firm.ended", "firm_client", linkId, details));
        tenants.runAs(firm, () -> audit.recordAs(by, "firm.ended", "firm_client", linkId, details));
    }

    private void tellFirmAdmins(java.util.function.Supplier<String> title, java.util.function.Supplier<String> body) {
        for (Long holder : notifications.holders("firm.manage")) {
            notifications.send(holder, "firm.invited", title, body, "/firm");
        }
    }

    // ---- money ----

    public Terms terms() {
        return tenants.callAsPlatform(() -> jdbc.sql("select firm_commission_percent as commission_percent, firm_discount_percent as discount_percent from billing_setting where id = 1")
                .query(Terms.class).single());
    }

    public Terms saveTerms(Terms t, String by) {
        for (BigDecimal v : Arrays.asList(t.commissionPercent(), t.discountPercent())) {
            if (v == null || v.signum() < 0 || v.compareTo(BigDecimal.valueOf(50)) > 0) {
                throw new BusinessRuleException("The commission and the discount go from 0% to 50%.");
            }
        }
        tenants.callAsPlatform(() -> jdbc.sql("update billing_setting set firm_commission_percent = ?, firm_discount_percent = ? where id = 1")
                .params(t.commissionPercent(), t.discountPercent()).update());
        audit.record("firm.terms_changed", "billing_setting", 1, Map.of("commission", t.commissionPercent(),
                "discount", t.discountPercent()));
        return terms();
    }

    /** The firm that pays a client's invoices at the wholesale price, if any. */
    public Optional<Wholesale> wholesaleFor(long clientId) {
        return tenants.callAsPlatform(() -> jdbc.sql("""
                select l.firm_id, c.name as firm_name, c.address, c.tax_number, s.firm_discount_percent as discount_percent
                from firm_client l join company c on c.id = l.firm_id cross join billing_setting s
                where l.client_id = ? and l.status = 'ACTIVE' and l.billing = 'WHOLESALE' order by l.answered_at limit 1""")
                .param(clientId).query(Wholesale.class).optional());
    }

    /** When a client that pays HR Pay itself pays an invoice in full, its firm earns the commission. */
    public void commissionOnPaid(long invoiceId) {
        tenants.callAsPlatform(() -> jdbc.sql("""
                insert into firm_commission (firm_id, client_id, invoice_id, invoice_number, base, percent, amount, currency)
                select l.firm_id, i.company_id, i.id, i.number, i.subtotal, s.firm_commission_percent,
                       round(i.subtotal * s.firm_commission_percent / 100, 2), i.currency
                from invoice i join firm_client l on l.client_id = i.company_id and l.status = 'ACTIVE' and l.billing = 'COMMISSION'
                cross join billing_setting s
                where i.id = ? and i.paid_by_firm_id is null and s.firm_commission_percent > 0
                order by l.answered_at limit 1
                on conflict (invoice_id) do nothing""").param(invoiceId).update());
    }

    public List<Commission> commissions(Long firmId) {
        return tenants.callAsPlatform(() -> jdbc.sql("""
                select m.id, m.firm_id, f.name as firm_name, m.client_id, c.name as client_name, m.invoice_number, m.base,
                       m.percent, m.amount, m.currency, m.created_at, m.paid_out_at, m.paid_out_by, m.paid_out_ref
                from firm_commission m join company f on f.id = m.firm_id join company c on c.id = m.client_id
                where (cast(? as bigint) is null or m.firm_id = ?) order by m.paid_out_at nulls first, m.created_at desc limit 500""")
                .params(firmId, firmId).query(Commission.class).list());
    }

    public List<Commission> ownCommissions() {
        return commissions(requireFirm());
    }

    public void payOut(long commissionId, String reference, String by) {
        if (reference == null || reference.isBlank()) {
            throw new BusinessRuleException("Give the reference of the transfer to the firm.");
        }
        int n = tenants.callAsPlatform(() -> jdbc.sql("""
                update firm_commission set paid_out_at = now(), paid_out_by = ?, paid_out_ref = ? where id = ? and paid_out_at is null""")
                .params(by, reference.trim(), commissionId).update());
        if (n == 0) {
            throw new NotFoundException("Unpaid commission not found.");
        }
        audit.record("firm.commission_paid", "firm_commission", commissionId, Map.of("reference", reference.trim()));
    }

    /** The invoices the firm pays for its clients. */
    public List<FirmInvoice> ownInvoices() {
        long firm = requireFirm();
        return tenants.callAsPlatform(() -> jdbc.sql("""
                select i.id, i.company_id as client_id, coalesce(i.client_name, c.name) as client_name, i.number, i.issued_on,
                       i.due_on, i.currency, i.total, i.firm_discount_percent as discount_percent, i.status,
                       (i.status = 'OPEN' and i.due_on < current_date) as overdue
                from invoice i join company c on c.id = i.company_id where i.paid_by_firm_id = ?
                order by i.issued_on desc, i.id desc limit 300""").param(firm).query(FirmInvoice.class).list());
    }

    public static BigDecimal discounted(BigDecimal price, BigDecimal percent) {
        return price.multiply(BigDecimal.valueOf(100).subtract(percent)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
