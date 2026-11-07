package cd.hrpay.billing;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.platform.Entitlements;
import cd.hrpay.platform.Licences;
import cd.hrpay.platform.SupportAccess;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.tenancy.TenantContext;
import cd.hrpay.tenancy.Tenants;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The provider's side of billing: prices and rules, every company's subscription and invoices, payments recorded
 * by hand, and the start of a support visit in a company that opened its access.
 */
@RestController
@RequestMapping("/api/platform")
@PreAuthorize("hasAuthority('platform.manage')")
public class PlatformBillingController {

    public record SettingsView(String mode, Billing.Settings settings, List<Billing.Price> prices, Licences.State licence) {}

    public record PriceInput(@NotNull BigDecimal monthlyPrice, @NotNull BigDecimal yearlyPrice) {}

    public record VoidInput(String reason) {}

    public record Usage(int activeEmployees, int signedIn30Days, OffsetDateTime lastSignIn, int checkIns30Days,
                        LocalDate lastPayrollMonth, String lastPayrollStatus) {}

    public record CompanyBilling(long id, String name, String status, String suspendedReason, String planName,
                                 Usage usage, Billing.Subscription subscription, List<Billing.Invoice> invoices,
                                 List<Billing.Payment> payments, List<SupportAccess.Grant> support,
                                 List<SupportAccess.Request> supportRequests) {}

    private final Billing billing;
    private final InvoicePdf pdf;
    private final SupportAccess support;
    private final Entitlements entitlements;
    private final Licences licences;
    private final Tenants tenants;
    private final JdbcClient jdbc;
    private final Clock clock;

    public PlatformBillingController(Billing billing, InvoicePdf pdf, SupportAccess support, Entitlements entitlements,
                                     Licences licences, Tenants tenants, JdbcClient jdbc, Clock clock) {
        this.billing = billing;
        this.pdf = pdf;
        this.support = support;
        this.entitlements = entitlements;
        this.licences = licences;
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/billing")
    public SettingsView settings() {
        requireProvider();
        Licences.State licence = entitlements.mode() == Entitlements.Mode.ON_PREMISE
                ? tenants.callAsPlatform(licences::status).state() : null;
        return new SettingsView(entitlements.mode().name(), billing.settings(), billing.prices(), licence);
    }

    @PutMapping("/billing")
    public SettingsView saveSettings(@RequestBody Billing.Settings body) {
        requireProvider();
        billing.saveSettings(body, CurrentUser.email());
        return settings();
    }

    @PutMapping("/plans/{code}/price")
    public Billing.Price savePrice(@PathVariable String code, @Valid @RequestBody PriceInput body) {
        requireProvider();
        return billing.savePrice(code, body.monthlyPrice(), body.yearlyPrice());
    }

    @GetMapping("/invoices")
    public List<Billing.Invoice> invoices(@RequestParam(required = false) String status) {
        requireProvider();
        if (status != null && !List.of("OPEN", "PAID", "VOID", "OVERDUE").contains(status)) {
            throw new BusinessRuleException("Unknown status.");
        }
        return billing.invoices(null, status);
    }

    @GetMapping("/invoices/{id}.pdf")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable long id) {
        requireProvider();
        return BillingController.pdf(pdf, billing.invoice(id));
    }

    @PostMapping("/invoices/{id}/payments")
    public Billing.Invoice recordPayment(@PathVariable long id, @RequestBody Billing.PaymentInput body) {
        requireProvider();
        return billing.recordPayment(id, body, CurrentUser.email(), LocalDate.now(clock));
    }

    @PostMapping("/invoices/{id}/void")
    public Billing.Invoice voidInvoice(@PathVariable long id, @RequestBody VoidInput body) {
        requireProvider();
        return billing.voidInvoice(id, body.reason(), CurrentUser.email(), LocalDate.now(clock));
    }

    @GetMapping("/companies/{id}/billing")
    public CompanyBilling company(@PathVariable long id) {
        requireProvider();
        record Row(String name, String status, String suspendedReason, String planName) {}
        Row c = tenants.callAsPlatform(() -> jdbc.sql("""
                select c.name, c.status, c.suspended_reason, p.name as plan_name from company c
                join plan p on p.code = c.plan_code where c.id = ?""").param(id).query(Row.class).optional())
                .orElseThrow(() -> new NotFoundException("Unknown company."));
        Usage usage = tenants.callAsPlatform(() -> jdbc.sql("""
                select (select count(*) from app_user where company_id = :id and status = 'ACTIVE') as active_employees,
                       (select count(*) from app_user where company_id = :id and last_login_at > now() - interval '30 days') as signed_in30_days,
                       (select max(last_login_at) from app_user where company_id = :id) as last_sign_in,
                       (select count(*) from clock_event where company_id = :id and occurred_at > now() - interval '30 days') as check_ins30_days,
                       (select max(month) from payroll_run where company_id = :id) as last_payroll_month,
                       (select status from payroll_run where company_id = :id order by month desc limit 1) as last_payroll_status""")
                .param("id", id).query(Usage.class).single());
        return new CompanyBilling(id, c.name(), c.status(), c.suspendedReason(), c.planName(), usage,
                billing.subscription(id).orElse(null), billing.invoices(id, null), billing.payments(id),
                support.grants(id), support.requests(id, 100));
    }

    @PutMapping("/companies/{id}/subscription")
    public Billing.Subscription changeSubscription(@PathVariable long id, @RequestBody Billing.SubscriptionChange body) {
        requireProvider();
        return billing.change(id, body, CurrentUser.email());
    }

    @PostMapping("/companies/{id}/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public Billing.Invoice issue(@PathVariable long id) {
        requireProvider();
        if (entitlements.mode() != Entitlements.Mode.SAAS) {
            throw new BusinessRuleException("On premise, the licence covers the use: there are no invoices.");
        }
        return billing.issue(id, LocalDate.now(clock), CurrentUser.email());
    }

    /** Starts a support visit: until it ends, this session works in the company, read-only. */
    @PostMapping("/companies/{id}/support")
    public SupportAccess.Visit startSupport(@PathVariable long id, HttpSession session) {
        requireProvider();
        SupportAccess.Visit visit = support.start(id, CurrentUser.email());
        session.setAttribute(SupportAccess.SESSION_KEY, visit);
        return visit;
    }

    private static void requireProvider() {
        if (!TenantContext.isProvider(TenantContext.companyId())) {
            throw new AccessDeniedException("Only the provider manages companies.");
        }
    }
}
