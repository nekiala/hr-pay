package cd.hrpay.billing;

import cd.hrpay.i18n.Texts;
import java.util.function.Supplier;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.notify.NotificationService;
import cd.hrpay.payroll.ExchangeRates;
import cd.hrpay.tenancy.TenantContext;
import cd.hrpay.tenancy.Tenants;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Subscriptions, invoices and payments of the companies on a SaaS installation. A company starts with a free trial;
 * from its end an invoice is issued in advance for each month or year, in USD or in CDF at the BCC rate of the day.
 * An invoice left unpaid past its due date and the grace days makes the company read-only until it is paid.
 * Payments are recorded by the provider for now; a payment gateway would call {@link #recordPayment} the same way.
 */
@Service
public class Billing {

    private static final Logger log = LoggerFactory.getLogger(Billing.class);

    public static final Set<String> METHODS = Set.of("BANK_TRANSFER", "MPESA", "ORANGE_MONEY", "AIRTEL_MONEY", "CASH", "OTHER");

    public record Settings(int trialDays, int paymentDays, int suspendAfterDays, String taxLabel, BigDecimal taxRate,
                           String invoicePrefix, String bankDetails, String mobileMoney, String invoiceNote) {}

    public record Price(String code, String name, Integer maxEmployees, BigDecimal monthlyPrice, BigDecimal yearlyPrice) {}

    public record Subscription(long companyId, String status, String cycle, String currency, LocalDate trialEnds,
                               LocalDate nextInvoiceOn, boolean autoSuspend, OffsetDateTime startedAt,
                               OffsetDateTime cancelledAt) {}

    public record Invoice(long id, long companyId, String number, LocalDate issuedOn, LocalDate dueOn,
                          LocalDate periodFrom, LocalDate periodTo, String planCode, String planName, String cycle,
                          String currency, BigDecimal usdCdf, BigDecimal priceUsd, BigDecimal subtotal, String taxLabel,
                          BigDecimal taxRate, BigDecimal tax, BigDecimal total, String status, BigDecimal paid,
                          boolean overdue, OffsetDateTime paidAt, OffsetDateTime voidedAt, String voidReason,
                          String customerName, String customerAddress, String customerTaxNumber,
                          OffsetDateTime createdAt) {
        public BigDecimal balance() {
            return "OPEN".equals(status) ? total.subtract(paid).max(BigDecimal.ZERO) : BigDecimal.ZERO;
        }
    }

    public record Payment(long id, long invoiceId, String invoiceNumber, String method, String reference,
                          BigDecimal amount, String currency, LocalDate paidOn, String note, OffsetDateTime recordedAt,
                          String recordedBy) {}

    public record PaymentInput(String method, String reference, BigDecimal amount, LocalDate paidOn, String note) {}

    public record SubscriptionChange(String cycle, String currency, LocalDate trialEnds, LocalDate nextInvoiceOn,
                                     Boolean autoSuspend, Boolean cancelled) {}

    private final JdbcClient jdbc;
    private final Tenants tenants;
    private final ExchangeRates rates;
    private final AuditService audit;
    private final UserRepository users;
    private final NotificationService notifications;
    private cd.hrpay.firm.Firms firms;

    @org.springframework.beans.factory.annotation.Autowired
    void setFirms(cd.hrpay.firm.Firms firms) {
        this.firms = firms;
    }

    public Billing(JdbcClient jdbc, Tenants tenants, ExchangeRates rates, AuditService audit, UserRepository users,
                   NotificationService notifications) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.rates = rates;
        this.audit = audit;
        this.users = users;
        this.notifications = notifications;
    }

    // ---- settings and prices (shared by the installation) ----

    public Settings settings() {
        return jdbc.sql("""
                select trial_days, payment_days, suspend_after_days, tax_label, tax_rate, invoice_prefix, bank_details,
                       mobile_money, invoice_note from billing_setting where id = 1""").query(Settings.class).single();
    }

    @Transactional
    public Settings saveSettings(Settings s, String by) {
        if (s.taxRate() == null || s.taxRate().signum() < 0 || s.taxRate().compareTo(BigDecimal.valueOf(100)) >= 0) {
            throw new BusinessRuleException("The tax rate must be between 0 and 100%.");
        }
        if (s.trialDays() < 0 || s.trialDays() > 365 || s.paymentDays() < 0 || s.paymentDays() > 120
                || s.suspendAfterDays() < 0 || s.suspendAfterDays() > 365) {
            throw new BusinessRuleException("Trial: 0 to 365 days; payment: 0 to 120 days; grace: 0 to 365 days.");
        }
        String prefix = s.invoicePrefix() == null ? "" : s.invoicePrefix().trim().toUpperCase(Locale.ROOT);
        if (!prefix.matches("[A-Z0-9]{1,10}")) {
            throw new BusinessRuleException("The invoice prefix takes 1 to 10 letters or digits.");
        }
        jdbc.sql("""
                update billing_setting set trial_days = ?, payment_days = ?, suspend_after_days = ?, tax_label = ?,
                       tax_rate = ?, invoice_prefix = ?, bank_details = ?, mobile_money = ?, invoice_note = ?,
                       updated_at = now(), updated_by = ? where id = 1""")
                .params(s.trialDays(), s.paymentDays(), s.suspendAfterDays(), text(s.taxLabel(), "Tax"), s.taxRate(),
                        prefix, blank(s.bankDetails()), blank(s.mobileMoney()), blank(s.invoiceNote()), by)
                .update();
        audit.record("billing.settings_updated", "billing_setting", 1, Map.of("taxRate", s.taxRate(),
                "trialDays", s.trialDays(), "suspendAfterDays", s.suspendAfterDays()));
        return settings();
    }

    public List<Price> prices() {
        return jdbc.sql("select code, name, max_employees, monthly_price, yearly_price from plan order by display_order")
                .query(Price.class).list();
    }

    @Transactional
    public Price savePrice(String code, BigDecimal monthly, BigDecimal yearly) {
        if (monthly == null || yearly == null || monthly.signum() < 0 || yearly.signum() < 0) {
            throw new BusinessRuleException("Prices cannot be negative.");
        }
        int n = jdbc.sql("update plan set monthly_price = ?, yearly_price = ? where code = ?")
                .params(money(monthly), money(yearly), code).update();
        if (n == 0) {
            throw new NotFoundException("Unknown plan.");
        }
        audit.record("billing.price_changed", "plan", code, Map.of("monthly", monthly, "yearly", yearly));
        return prices().stream().filter(p -> p.code().equals(code)).findFirst().orElseThrow();
    }

    // ---- subscriptions ----

    /** A new company's free trial; the first invoice is issued when it ends. */
    public void startTrial(long companyId, LocalDate today) {
        if (TenantContext.isProvider(companyId)) {
            return;
        }
        LocalDate ends = today.plusDays(settings().trialDays());
        tenants.callAsPlatform(() -> jdbc.sql("""
                insert into subscription (company_id, trial_ends, next_invoice_on) values (?, ?, ?)
                on conflict (company_id) do nothing""").params(companyId, ends, ends).update());
    }

    public Optional<Subscription> subscription(long companyId) {
        return tenants.callAsPlatform(() -> jdbc.sql("""
                select company_id, status, cycle, currency, trial_ends, next_invoice_on, auto_suspend, started_at,
                       cancelled_at from subscription where company_id = ?""").param(companyId)
                .query(Subscription.class).optional());
    }

    /** The provider's change: cycle, currency, trial end, next invoice date, automatic suspension, cancellation. */
    @Transactional
    public Subscription change(long companyId, SubscriptionChange c, String by) {
        Subscription s = subscription(companyId).orElseThrow(() -> new NotFoundException("This company has no subscription."));
        String cycle = c.cycle() == null ? s.cycle() : checkCycle(c.cycle());
        String currency = c.currency() == null ? s.currency() : checkCurrency(c.currency());
        boolean cancel = Boolean.TRUE.equals(c.cancelled());
        boolean resume = Boolean.FALSE.equals(c.cancelled()) && "CANCELLED".equals(s.status());
        LocalDate trialEnds = c.trialEnds() == null ? s.trialEnds() : c.trialEnds();
        LocalDate next = c.nextInvoiceOn() == null ? s.nextInvoiceOn() : c.nextInvoiceOn();
        if ("TRIAL".equals(s.status()) && c.trialEnds() != null && c.nextInvoiceOn() == null) {
            next = trialEnds; // extending the trial moves the first invoice with it
        }
        String status = cancel ? "CANCELLED" : resume ? (hasInvoices(companyId) ? "ACTIVE" : "TRIAL") : s.status();
        if (!cancel && next == null) {
            throw new BusinessRuleException("Give the date of the next invoice.");
        }
        LocalDate nextInvoice = cancel ? null : next;
        tenants.callAsPlatform(() -> jdbc.sql("""
                update subscription set cycle = ?, currency = ?, trial_ends = ?, next_invoice_on = ?, status = ?,
                       auto_suspend = ?, cancelled_at = case when ? then now() when ? then null else cancelled_at end,
                       updated_at = now(), updated_by = ? where company_id = ?""")
                .params(cycle, currency, trialEnds, nextInvoice, status,
                        c.autoSuspend() == null ? s.autoSuspend() : c.autoSuspend(), cancel, resume, by, companyId)
                .update());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("cycle", cycle);
        details.put("currency", currency);
        details.put("status", status);
        details.put("nextInvoiceOn", String.valueOf(nextInvoice));
        audit.record("subscription.changed", "company", companyId, details);
        tenants.runAs(companyId, () -> audit.recordAs(by, "subscription.changed", "company", companyId, details));
        return subscription(companyId).orElseThrow();
    }

    /** The company's own choice, from its next invoice: monthly or yearly, in USD or CDF. */
    @Transactional
    public Subscription choose(long companyId, String cycle, String currency, String by) {
        Subscription s = subscription(companyId).orElseThrow(() -> new NotFoundException("This company has no subscription."));
        if ("CANCELLED".equals(s.status())) {
            throw new BusinessRuleException("The subscription is cancelled. Ask your provider to resume it.");
        }
        String newCycle = checkCycle(cycle);
        String newCurrency = checkCurrency(currency);
        tenants.callAsPlatform(() -> jdbc.sql("""
                update subscription set cycle = ?, currency = ?, updated_at = now(), updated_by = ? where company_id = ?""")
                .params(newCycle, newCurrency, by, companyId).update());
        audit.record("subscription.chosen", "company", companyId, Map.of("cycle", newCycle, "currency", newCurrency));
        return subscription(companyId).orElseThrow();
    }

    // ---- invoices ----

    public List<Invoice> invoices(Long companyId, String status) {
        return tenants.callAsPlatform(() -> jdbc.sql(INVOICE_SELECT + " " + """
                 where (cast(:company as bigint) is null or i.company_id = :company)
                   and (cast(:status as varchar) is null
                        or (:status = 'OVERDUE' and i.status = 'OPEN' and i.due_on < current_date)
                        or i.status = :status)
                 order by i.issued_on desc, i.id desc limit 500""")
                .param("company", companyId).param("status", status).query(Invoice.class).list());
    }

    public Invoice invoice(long id) {
        return tenants.callAsPlatform(() -> jdbc.sql(INVOICE_SELECT + " where i.id = ?").param(id).query(Invoice.class)
                .optional()).orElseThrow(() -> new NotFoundException("Unknown invoice."));
    }

    /** The company's own invoice, or not found: one company never reaches another's invoice by its id. */
    public Invoice ownInvoice(long id) {
        Invoice i = invoice(id);
        if (!java.util.Objects.equals(i.companyId(), TenantContext.companyId())) {
            throw new NotFoundException("Unknown invoice.");
        }
        return i;
    }

    public List<Payment> payments(long companyId) {
        return tenants.callAsPlatform(() -> jdbc.sql("""
                select p.id, p.invoice_id, i.number as invoice_number, p.method, p.reference, p.amount, i.currency,
                       p.paid_on, p.note, p.recorded_at, p.recorded_by
                from invoice_payment p join invoice i on i.id = p.invoice_id
                where p.company_id = ? order by p.paid_on desc, p.id desc""").param(companyId).query(Payment.class).list());
    }

    /**
     * Issues the next invoice of the company, for the period that starts at its next invoice date, and moves that
     * date one cycle on. The plan's price at this moment applies; in CDF at the BCC rate of the day.
     */
    @Transactional
    public Invoice issue(long companyId, LocalDate today, String by) {
        if (TenantContext.isProvider(companyId)) {
            throw new BusinessRuleException("The provider's own company is not billed.");
        }
        Subscription s = subscription(companyId).orElseThrow(() -> new NotFoundException("This company has no subscription."));
        if ("CANCELLED".equals(s.status()) || s.nextInvoiceOn() == null) {
            throw new BusinessRuleException("The subscription is cancelled: no more invoices.");
        }
        Settings settings = settings();
        record Customer(String name, String address, String taxNumber, String planCode, String planName,
                        BigDecimal monthlyPrice, BigDecimal yearlyPrice) {}
        Customer c = tenants.callAsPlatform(() -> jdbc.sql("""
                select c.name, c.address, c.tax_number, c.plan_code, p.name as plan_name, p.monthly_price, p.yearly_price
                from company c join plan p on p.code = c.plan_code where c.id = ?""").param(companyId)
                .query(Customer.class).single());
        LocalDate from = s.nextInvoiceOn();
        LocalDate to = ("YEARLY".equals(s.cycle()) ? from.plusYears(1) : from.plusMonths(1)).minusDays(1);
        BigDecimal priceUsd = "YEARLY".equals(s.cycle()) ? c.yearlyPrice() : c.monthlyPrice();
        // A client whose accounting firm pays HR Pay for it: the firm is billed, at the wholesale price.
        java.util.Optional<cd.hrpay.firm.Firms.Wholesale> wholesale = firms == null ? java.util.Optional.empty()
                : firms.wholesaleFor(companyId);
        if (wholesale.isPresent()) {
            priceUsd = cd.hrpay.firm.Firms.discounted(priceUsd, wholesale.get().discountPercent());
        }
        BigDecimal rate = null;
        BigDecimal subtotal = money(priceUsd);
        if ("CDF".equals(s.currency())) {
            rate = rates.on("USD", today).map(ExchangeRates.Rate::cdfPerUnit).orElseThrow(() -> new BusinessRuleException(
                    "There is no BCC rate for USD on " + today + " yet: enter it or invoice in USD."));
            subtotal = money(priceUsd.multiply(rate));
        }
        BigDecimal tax = money(subtotal.multiply(settings.taxRate()).divide(BigDecimal.valueOf(100)));
        BigDecimal total = subtotal.add(tax);
        String number = nextNumber(settings.invoicePrefix(), today.getYear());
        BigDecimal usdCdf = rate;
        BigDecimal sub = subtotal;
        BigDecimal price = priceUsd;
        String customerName = wholesale.map(cd.hrpay.firm.Firms.Wholesale::firmName).orElse(c.name());
        String customerAddress = wholesale.isPresent() ? wholesale.get().address() : c.address();
        String customerTax = wholesale.isPresent() ? wholesale.get().taxNumber() : c.taxNumber();
        boolean issued = tenants.callAsPlatform(() -> jdbc.sql("""
                select count(*) from invoice where company_id = ? and period_from = ? and status <> 'VOID'""")
                .params(companyId, from).query(Integer.class).single()) > 0;
        if (issued) {
            throw new ConflictException("The invoice for the period from " + from + " is already issued.");
        }
        long id;
        try {
            id = tenants.callAsPlatform(() -> jdbc.sql("""
                    insert into invoice (company_id, number, issued_on, due_on, period_from, period_to, plan_code, plan_name,
                        cycle, currency, usd_cdf, price_usd, subtotal, tax_label, tax_rate, tax, total, customer_name,
                        customer_address, customer_tax_number, created_by, paid_by_firm_id, firm_discount_percent, client_name)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(companyId, number, today, today.plusDays(settings.paymentDays()), from, to, c.planCode(),
                            c.planName(), s.cycle(), s.currency(), usdCdf, money(price), sub, settings.taxLabel(),
                            settings.taxRate(), tax, total, customerName, customerAddress, customerTax, by,
                            wholesale.map(cd.hrpay.firm.Firms.Wholesale::firmId).orElse(null),
                            wholesale.map(cd.hrpay.firm.Firms.Wholesale::discountPercent).orElse(null),
                            wholesale.isPresent() ? c.name() : null)
                    .query(Long.class).single());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("The invoice for the period from " + from + " is already issued.");
        }
        tenants.callAsPlatform(() -> jdbc.sql("""
                update subscription set next_invoice_on = ?, status = case when status = 'TRIAL' then 'ACTIVE' else status end
                where company_id = ?""").params(to.plusDays(1), companyId).update());
        Map<String, Object> details = Map.of("number", number, "total", total, "currency", s.currency(),
                "period", from + " to " + to);
        audit.record("invoice.issued", "invoice", id, details);
        tenants.runAs(companyId, () -> audit.recordAs(by, "invoice.issued", "invoice", id, details));
        Invoice invoice = invoice(id);
        wholesale.ifPresent(w -> tenants.runAs(w.firmId(), () -> audit.recordAs(by, "invoice.issued", "invoice", id, details)));
        tellAdmins(wholesale.map(cd.hrpay.firm.Firms.Wholesale::firmId).orElse(companyId), () -> Texts.t("Invoice %s from HR Pay", number), () -> Texts.t("""
                Invoice %s for %s, %s to %s, is issued: %s %s, to pay by %s.

                Pay by bank transfer or mobile money with the reference %s. The invoice and the payment details are \
                on the Billing page of HR Pay.%s""", number, c.planName(), Texts.day(from), Texts.day(to),
                amount(total), s.currency(), Texts.day(invoice.dueOn()), number, instructions(settings)));
        return invoice;
    }

    /**
     * Records a payment against an open invoice, in its currency. The invoice closes once fully paid, and a company
     * suspended for unpaid invoices gets its changes back when none is past its grace days any more.
     */
    @Transactional
    public Invoice recordPayment(long invoiceId, PaymentInput p, String by, LocalDate today) {
        Invoice i = invoice(invoiceId);
        if (!"OPEN".equals(i.status())) {
            throw new BusinessRuleException("Invoice " + i.number() + " is " + i.status().toLowerCase(Locale.ROOT) + ".");
        }
        if (p.method() == null || !METHODS.contains(p.method())) {
            throw new BusinessRuleException("Choose how it was paid: bank transfer, M-Pesa, Orange Money, Airtel Money, cash or other.");
        }
        if (p.reference() == null || p.reference().isBlank()) {
            throw new BusinessRuleException("Give the transaction reference from the bank or the mobile money operator.");
        }
        if (p.amount() == null || p.amount().signum() <= 0) {
            throw new BusinessRuleException("The amount must be more than zero.");
        }
        if (p.amount().compareTo(i.balance()) > 0) {
            throw new BusinessRuleException("The amount is more than the %s %s left to pay on %s."
                    .formatted(amount(i.balance()), i.currency(), i.number()));
        }
        LocalDate paidOn = p.paidOn() == null ? today : p.paidOn();
        if (paidOn.isAfter(today)) {
            throw new BusinessRuleException("The payment date cannot be in the future.");
        }
        boolean known = tenants.callAsPlatform(() -> jdbc.sql("""
                select count(*) from invoice_payment where method = ? and lower(reference) = lower(?)""")
                .params(p.method(), p.reference().trim()).query(Integer.class).single()) > 0;
        if (known) {
            throw new ConflictException("This transaction reference is already recorded.");
        }
        long paymentId;
        try {
            paymentId = tenants.callAsPlatform(() -> jdbc.sql("""
                    insert into invoice_payment (company_id, invoice_id, method, reference, amount, paid_on, note, recorded_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(i.companyId(), i.id(), p.method(), p.reference().trim(), money(p.amount()), paidOn,
                            blank(p.note()), by).query(Long.class).single());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("This transaction reference is already recorded.");
        }
        boolean full = p.amount().compareTo(i.balance()) == 0;
        if (full) {
            tenants.callAsPlatform(() -> jdbc.sql("update invoice set status = 'PAID', paid_at = now() where id = ?")
                    .param(i.id()).update());
        }
        Map<String, Object> details = Map.of("invoice", i.number(), "method", p.method(), "reference", p.reference().trim(),
                "amount", money(p.amount()), "currency", i.currency());
        audit.record("invoice.payment_recorded", "invoice_payment", paymentId, details);
        tenants.runAs(i.companyId(), () -> audit.recordAs(by, "invoice.payment_recorded", "invoice_payment", paymentId, details));
        if (full) {
            if (firms != null) {
                firms.commissionOnPaid(i.id());
            }
            liftIfPaid(i.companyId(), today, by);
            tellAdmins(i.companyId(), () -> Texts.t("Invoice %s is paid", i.number()),
                    () -> Texts.t("Thank you: invoice %s (%s %s) is paid in full.", i.number(), amount(i.total()), i.currency()));
        }
        return invoice(i.id());
    }

    /** Cancels an invoice issued by mistake; its period can then be invoiced again. */
    @Transactional
    public Invoice voidInvoice(long invoiceId, String reason, String by, LocalDate today) {
        Invoice i = invoice(invoiceId);
        if (!"OPEN".equals(i.status())) {
            throw new BusinessRuleException("Only an open invoice can be cancelled.");
        }
        if (i.paid().signum() > 0) {
            throw new BusinessRuleException("A payment is recorded on this invoice: it cannot be cancelled.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Say why the invoice is cancelled.");
        }
        tenants.callAsPlatform(() -> jdbc.sql("""
                update invoice set status = 'VOID', voided_at = now(), void_reason = ? where id = ?""")
                .params(reason.trim(), i.id()).update());
        // The period is billed again with the next invoice, unless a later one exists.
        tenants.callAsPlatform(() -> jdbc.sql("""
                update subscription set next_invoice_on = ? where company_id = ? and next_invoice_on = ?""")
                .params(i.periodFrom(), i.companyId(), i.periodTo().plusDays(1)).update());
        Map<String, Object> details = Map.of("number", i.number(), "reason", reason.trim());
        audit.record("invoice.voided", "invoice", i.id(), details);
        tenants.runAs(i.companyId(), () -> audit.recordAs(by, "invoice.voided", "invoice", i.id(), details));
        liftIfPaid(i.companyId(), today, by);
        return invoice(i.id());
    }

    // ---- the daily run ----

    public record RunResult(int issued, int suspended, List<String> errors) {}

    /** Issues the invoices that are due and suspends the companies with an invoice past its grace days. */
    public RunResult daily(LocalDate today, java.util.function.Function<Long, Invoice> issuer) {
        List<Long> due = tenants.callAsPlatform(() -> jdbc.sql("""
                select s.company_id from subscription s join company c on c.id = s.company_id
                where s.status <> 'CANCELLED' and s.next_invoice_on <= ? order by s.company_id""")
                .param(today).query(Long.class).list());
        int issued = 0;
        List<String> errors = new java.util.ArrayList<>();
        for (Long companyId : due) {
            if (TenantContext.isProvider(companyId)) {
                continue;
            }
            try {
                // Catches up one period per run: a company behind by several periods gets one invoice a day.
                issuer.apply(companyId);
                issued++;
            } catch (RuntimeException e) {
                log.warn("Could not invoice company {}: {}", companyId, e.getMessage());
                errors.add("Company " + companyId + ": " + e.getMessage());
            }
        }
        int suspended = 0;
        for (Long companyId : overdueCompanies(today)) {
            suspendForUnpaid(companyId, today);
            suspended++;
        }
        return new RunResult(issued, suspended, errors);
    }

    private List<Long> overdueCompanies(LocalDate today) {
        int grace = settings().suspendAfterDays();
        return tenants.callAsPlatform(() -> jdbc.sql("""
                select distinct i.company_id from invoice i
                join company c on c.id = i.company_id join subscription s on s.company_id = i.company_id
                where i.status = 'OPEN' and i.due_on + ? < ? and c.status = 'ACTIVE' and s.auto_suspend
                order by i.company_id""").params(grace, today).query(Long.class).list());
    }

    private void suspendForUnpaid(long companyId, LocalDate today) {
        int n = tenants.callAsPlatform(() -> jdbc.sql("""
                update company set status = 'SUSPENDED', suspended_reason = 'UNPAID', suspended_at = now()
                where id = ? and status = 'ACTIVE'""").param(companyId).update());
        if (n == 0) {
            return;
        }
        String numbers = String.join(", ", tenants.callAsPlatform(() -> jdbc.sql("""
                select number from invoice where company_id = ? and status = 'OPEN' and due_on < ? order by number""")
                .params(companyId, today).query(String.class).list()));
        Map<String, Object> details = Map.of("reason", "UNPAID", "invoices", numbers);
        audit.recordAs("system", "company.suspended", "company", companyId, details);
        tenants.runAs(companyId, () -> audit.recordAs("system", "company.suspended", "company", companyId, details));
        Settings current = settings();
        tellAdmins(companyId, () -> Texts.t("HR Pay is read-only: invoice unpaid"), () -> Texts.t("""
                Invoice(s) %s are unpaid past the grace period, so HR Pay is now read-only for your company. Everyone \
                can still sign in, read and export everything, including payslips; changes come back as soon as the \
                payment is recorded.%s""", numbers, instructions(current)));
    }

    /** Gives a company suspended for unpaid invoices its changes back once nothing is past the grace days. */
    private void liftIfPaid(long companyId, LocalDate today, String by) {
        if (overdueAny(companyId, today)) {
            return;
        }
        int n = tenants.callAsPlatform(() -> jdbc.sql("""
                update company set status = 'ACTIVE', suspended_reason = null, suspended_at = null
                where id = ? and status = 'SUSPENDED' and suspended_reason = 'UNPAID'""").param(companyId).update());
        if (n > 0) {
            audit.recordAs(by, "company.reactivated", "company", companyId, Map.of("reason", "paid"));
            tenants.runAs(companyId, () -> audit.recordAs(by, "company.reactivated", "company", companyId, Map.of("reason", "paid")));
        }
    }

    private boolean overdueAny(long companyId, LocalDate today) {
        int grace = settings().suspendAfterDays();
        return tenants.callAsPlatform(() -> jdbc.sql("""
                select count(*) from invoice where company_id = ? and status = 'OPEN' and due_on + ? < ?""")
                .params(companyId, grace, today).query(Integer.class).single()) > 0;
    }

    // ---- what the company sees ----

    public record Standing(Subscription subscription, BigDecimal balanceUsd, List<Invoice> open, String suspendedReason,
                           LocalDate readOnlyFrom) {}

    /** The current company's subscription and unpaid invoices, for its notices. Null when it is not billed. */
    public Standing standing(long companyId, LocalDate today) {
        Optional<Subscription> s = subscription(companyId);
        if (s.isEmpty()) {
            return null;
        }
        List<Invoice> open = invoices(companyId, "OPEN");
        String reason = tenants.callAsPlatform(() -> jdbc.sql("select suspended_reason from company where id = ?")
                .param(companyId).query(String.class).optional().orElse(null));
        int grace = settings().suspendAfterDays();
        LocalDate readOnlyFrom = open.stream().map(i -> i.dueOn().plusDays(grace + 1L)).min(LocalDate::compareTo).orElse(null);
        BigDecimal balance = open.stream().filter(i -> "USD".equals(i.currency())).map(Invoice::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Standing(s.get(), balance, open, reason, s.get().autoSuspend() ? readOnlyFrom : null);
    }

    public static String day(LocalDate d) {
        return d == null ? "" : Texts.day(d);
    }

    public static String amount(BigDecimal v) {
        return String.format(Locale.ENGLISH, "%,.2f", v);
    }

    // ---- helpers ----

    private boolean hasInvoices(long companyId) {
        return tenants.callAsPlatform(() -> jdbc.sql("select count(*) from invoice where company_id = ?")
                .param(companyId).query(Integer.class).single()) > 0;
    }

    private String nextNumber(String prefix, int year) {
        int n = jdbc.sql("""
                insert into invoice_counter (year, last) values (?, 1)
                on conflict (year) do update set last = invoice_counter.last + 1 returning last""")
                .param(year).query(Integer.class).single();
        return "%s-%d-%05d".formatted(prefix, year, n);
    }

    private void tellAdmins(long companyId, Supplier<String> subject, Supplier<String> body) {
        try {
            tenants.runAs(companyId, () -> {
                List<Long> ids = jdbc.sql("""
                        select distinct u.id from app_user u join user_role ur on ur.user_id = u.id
                        join role_permission rp on rp.role_id = ur.role_id
                        where rp.permission_code = 'subscription.manage' and u.status = 'ACTIVE'""")
                        .query(Long.class).list();
                for (Long id : ids) {
                    AppUser admin = users.findById(id).orElse(null);
                    if (admin != null) {
                        notifications.email(admin, subject, body);
                    }
                }
            });
        } catch (RuntimeException e) {
            log.warn("Could not email the administrators of company {}: {}", companyId, e.getMessage());
        }
    }

    static String instructions(Settings s) {
        StringBuilder b = new StringBuilder();
        if (s.bankDetails() != null) {
            b.append("\n\n").append(Texts.t("Bank transfer:")).append("\n").append(s.bankDetails());
        }
        if (s.mobileMoney() != null) {
            b.append("\n\n").append(Texts.t("Mobile money:")).append("\n").append(s.mobileMoney());
        }
        return b.toString();
    }

    private static String checkCycle(String cycle) {
        if (!"MONTHLY".equals(cycle) && !"YEARLY".equals(cycle)) {
            throw new BusinessRuleException("Choose monthly or yearly.");
        }
        return cycle;
    }

    private static String checkCurrency(String currency) {
        if (!"USD".equals(currency) && !"CDF".equals(currency)) {
            throw new BusinessRuleException("Invoices are in USD or CDF.");
        }
        return currency;
    }

    static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String text(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim();
    }

    private static final String INVOICE_SELECT = """
            select i.id, i.company_id, i.number, i.issued_on, i.due_on, i.period_from, i.period_to, i.plan_code,
                   i.plan_name, i.cycle, i.currency, i.usd_cdf, i.price_usd, i.subtotal, i.tax_label, i.tax_rate, i.tax,
                   i.total, i.status,
                   coalesce((select sum(p.amount) from invoice_payment p where p.invoice_id = i.id), 0) as paid,
                   (i.status = 'OPEN' and i.due_on < current_date) as overdue,
                   i.paid_at, i.voided_at, i.void_reason, i.customer_name, i.customer_address, i.customer_tax_number,
                   i.created_at
            from invoice i""";
}
