package cd.hrpay.firm;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.tenancy.TenantContext;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The accountant portal: the firm's clients, and for a client company, the firms that work for it. */
@RestController
public class FirmController {

    public record Invite(String firmEmail, List<String> roleCodes, boolean clauseAccepted) {}

    public record Roles(List<String> roleCodes) {}

    public record Reason(String reason) {}

    public record Accept(String billing) {}

    public record StaffInput(List<Long> userIds) {}

    public record ClientPage(List<Firms.ClientSide> links, List<Firms.Role> roles, String clauseVersion) {}

    public record FirmPage(List<Firms.FirmSide> clients, List<Firms.Staff> staff, boolean admin,
                           List<Firms.FirmInvoice> invoices, List<Firms.Commission> commissions, Firms.Terms terms) {}

    public record PayOut(String reference) {}

    private final Firms firms;

    public FirmController(Firms firms) {
        this.firms = firms;
    }

    // ---- the client company ----

    @GetMapping("/api/company/firms")
    @PreAuthorize("hasAuthority('firm.manage')")
    public ClientPage clientPage() {
        return new ClientPage(firms.forClient(), firms.clientRoles(), Firms.CLAUSE_VERSION);
    }

    @PostMapping("/api/company/firms")
    @PreAuthorize("hasAuthority('firm.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Firms.ClientSide invite(@RequestBody Invite in) {
        noVisit();
        return firms.invite(in.firmEmail(), in.roleCodes(), in.clauseAccepted(), CurrentUser.email());
    }

    @PutMapping("/api/company/firms/{id}/roles")
    @PreAuthorize("hasAuthority('firm.manage')")
    public Firms.ClientSide roles(@PathVariable long id, @RequestBody Roles in) {
        noVisit();
        return firms.changeRoles(id, in.roleCodes(), CurrentUser.email());
    }

    @PostMapping("/api/company/firms/{id}/end")
    @PreAuthorize("hasAuthority('firm.manage')")
    public Firms.ClientSide end(@PathVariable long id, @RequestBody(required = false) Reason in) {
        noVisit();
        return firms.endByClient(id, in == null ? null : in.reason(), CurrentUser.email());
    }

    @GetMapping("/api/company/firms/{id}/visits")
    @PreAuthorize("hasAuthority('firm.manage')")
    public List<Firms.Visited> visits(@PathVariable long id) {
        return firms.visits(id);
    }

    // ---- the firm ----

    @GetMapping("/api/firm")
    @PreAuthorize("isAuthenticated()")
    public FirmPage firmPage() {
        boolean admin = CurrentUser.has("firm.manage");
        List<Firms.FirmSide> clients = firms.forFirm(CurrentUser.id(), admin);
        return new FirmPage(clients, admin ? firms.staffOptions() : List.of(), admin,
                admin ? firms.ownInvoices() : List.of(), admin ? firms.ownCommissions() : List.of(), firms.terms());
    }

    @PostMapping("/api/firm/clients/{id}/accept")
    @PreAuthorize("hasAuthority('firm.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(@PathVariable long id, @RequestBody Accept in) {
        firms.accept(id, in.billing(), CurrentUser.email());
    }

    @PostMapping("/api/firm/clients/{id}/decline")
    @PreAuthorize("hasAuthority('firm.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@PathVariable long id) {
        firms.decline(id, CurrentUser.email());
    }

    @PostMapping("/api/firm/clients/{id}/leave")
    @PreAuthorize("hasAuthority('firm.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable long id, @RequestBody(required = false) Reason in) {
        firms.leave(id, in == null ? null : in.reason(), CurrentUser.email());
    }

    @PutMapping("/api/firm/clients/{id}/staff")
    @PreAuthorize("hasAuthority('firm.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void staff(@PathVariable long id, @RequestBody StaffInput in) {
        firms.setStaff(id, in.userIds(), CurrentUser.email());
    }

    /** Starts working in a client: until it ends, this session works there with the rights the client gave. */
    @PostMapping("/api/firm/clients/{id}/open")
    @PreAuthorize("isAuthenticated()")
    public Firms.Visit open(@PathVariable long id, HttpSession session) {
        Firms.Visit visit = firms.open(id, CurrentUser.id(), CurrentUser.has("firm.manage"), CurrentUser.email());
        session.setAttribute(Firms.SESSION_KEY, visit);
        return visit;
    }

    /** Brings the firm member back to their own firm. */
    @PostMapping("/api/firm/visit/end")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endVisit(HttpSession session) {
        if (session.getAttribute(Firms.SESSION_KEY) instanceof Firms.Visit visit) {
            session.removeAttribute(Firms.SESSION_KEY);
            firms.endVisit(visit, CurrentUser.email());
        }
    }

    // ---- the provider ----

    @GetMapping("/api/platform/firms/terms")
    @PreAuthorize("hasAuthority('platform.manage')")
    public Firms.Terms terms() {
        requireProvider();
        return firms.terms();
    }

    @PutMapping("/api/platform/firms/terms")
    @PreAuthorize("hasAuthority('platform.manage')")
    public Firms.Terms saveTerms(@RequestBody Firms.Terms in) {
        requireProvider();
        return firms.saveTerms(in, CurrentUser.email());
    }

    @GetMapping("/api/platform/firms/commissions")
    @PreAuthorize("hasAuthority('platform.manage')")
    public List<Firms.Commission> commissions() {
        requireProvider();
        return firms.commissions(null);
    }

    @PostMapping("/api/platform/firms/commissions/{id}/paid")
    @PreAuthorize("hasAuthority('platform.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void paid(@PathVariable long id, @RequestBody PayOut in) {
        requireProvider();
        firms.payOut(id, in.reference(), CurrentUser.email());
    }

    private static void noVisit() {
        if (Firms.current() != null) {
            throw new BusinessRuleException("Only the company's own administrators manage its accounting firms.");
        }
    }

    private static void requireProvider() {
        if (!TenantContext.isProvider(TenantContext.companyId())) {
            throw new AccessDeniedException("Only the provider manages firms.");
        }
    }
}
