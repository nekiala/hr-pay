package cd.hrpay.billing;

import cd.hrpay.common.NotFoundException;
import cd.hrpay.platform.Entitlements;
import cd.hrpay.platform.SupportAccess;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.tenancy.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A company's own billing page: its subscription, invoices and payments, and the provider's support access. */
@RestController
@RequestMapping("/api/billing")
@PreAuthorize("hasAuthority('subscription.manage')")
public class BillingController {

    public record PlanPrice(String code, String name, java.math.BigDecimal monthlyPrice, java.math.BigDecimal yearlyPrice) {}

    public record HowToPay(String bankDetails, String mobileMoney, String taxLabel, java.math.BigDecimal taxRate,
                           int paymentDays, int suspendAfterDays) {}

    public record View(boolean billed, String mode, String companyName, String planCode, String planName,
                       Billing.Subscription subscription, PlanPrice price, HowToPay howToPay,
                       List<Billing.Invoice> invoices, List<Billing.Payment> payments, List<SupportAccess.Grant> support,
                       List<SupportAccess.Request> supportRequests) {}

    public record Choice(@NotBlank String cycle, @NotBlank String currency) {}

    public record GrantInput(int days, String reason) {}

    private final Billing billing;
    private final InvoicePdf pdf;
    private final SupportAccess support;
    private final Entitlements entitlements;

    public BillingController(Billing billing, InvoicePdf pdf, SupportAccess support, Entitlements entitlements) {
        this.billing = billing;
        this.pdf = pdf;
        this.support = support;
        this.entitlements = entitlements;
    }

    @GetMapping
    public View view() {
        long companyId = company();
        Entitlements.Current c = entitlements.current();
        Billing.Subscription s = entitlements.mode() == Entitlements.Mode.SAAS ? billing.subscription(companyId).orElse(null) : null;
        Billing.Settings settings = billing.settings();
        PlanPrice price = billing.prices().stream().filter(p -> p.code().equals(c.planCode())).findFirst()
                .map(p -> new PlanPrice(p.code(), p.name(), p.monthlyPrice(), p.yearlyPrice())).orElse(null);
        return new View(s != null, entitlements.mode().name(), c.companyName(), c.planCode(), c.planName(), s, price,
                new HowToPay(settings.bankDetails(), settings.mobileMoney(), settings.taxLabel(), settings.taxRate(),
                        settings.paymentDays(), settings.suspendAfterDays()),
                s == null ? List.of() : billing.invoices(companyId, null), s == null ? List.of() : billing.payments(companyId),
                support.grants(companyId), support.requests(companyId, 100));
    }

    @PutMapping("/subscription")
    public Billing.Subscription choose(@Valid @RequestBody Choice body) {
        return billing.choose(company(), body.cycle(), body.currency(), CurrentUser.email());
    }

    @GetMapping("/invoices/{id}.pdf")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable long id) {
        return pdf(pdf, billing.ownInvoice(id));
    }

    @PostMapping("/support")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportAccess.Grant grant(@RequestBody GrantInput body) {
        return support.grant(body.days(), body.reason(), CurrentUser.email());
    }

    @DeleteMapping("/support/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable long id) {
        support.revoke(id, CurrentUser.email());
    }

    private static long company() {
        Long id = TenantContext.companyId();
        if (id == null) {
            throw new NotFoundException("No company.");
        }
        return id;
    }

    static ResponseEntity<byte[]> pdf(InvoicePdf pdf, Billing.Invoice i) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"" + i.number().replaceAll("[^A-Za-z0-9_-]", "") + ".pdf\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(pdf.render(i));
    }
}
