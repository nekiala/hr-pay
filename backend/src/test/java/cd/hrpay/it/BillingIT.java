package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.billing.BillingJob;
import cd.hrpay.payroll.ExchangeRates;
import cd.hrpay.tenancy.Tenants;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** Trials, invoices in USD and CDF, payments, read-only suspension for unpaid invoices, and support access. */
class BillingIT extends IntegrationTest {

    @Autowired
    BillingJob job;

    @Autowired
    Tenants tenants;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ExchangeRates rates;

    private static int companies;

    record Customer(long id, String email, MockHttpSession session) {}

    private Customer customer() throws Exception {
        int n = ++companies;
        String email = "payer" + n + "." + System.nanoTime() % 100000 + "@client.cd";
        String body = """
                {"name":"Client %d","countryCode":"CD","planCode":"UP_TO_50","modules":["ATTENDANCE","PAYROLL"],
                 "admin":{"firstName":"Paul","lastName":"Payer","email":"%s","phone":"+2438150%05d"}}"""
                .formatted(n, email, (int) (System.nanoTime() % 100000));
        String response = mvc.perform(json(post("/api/platform/companies").session(login(ADMIN)), body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(response, "$.company.id")).longValue();
        String password = JsonPath.read(response, "$.temporaryPassword");
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))).andExpect(status().isOk());
        mvc.perform(json(post("/api/auth/password").session(session),
                "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(password, PASSWORD)))
                .andExpect(status().isNoContent());
        return new Customer(id, email, login(email));
    }

    private long issue(long companyId, MockHttpSession provider) throws Exception {
        String r = mvc.perform(post("/api/platform/companies/" + companyId + "/invoices").session(provider).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(r, "$.id")).longValue();
    }

    private static String payment(String method, String reference, String amount) {
        return "{\"method\":\"%s\",\"reference\":\"%s\",\"amount\":%s}".formatted(method, reference, amount);
    }

    @Test
    void aNewCompanyGetsAFreeTrialThenAMonthlyInvoiceInAdvance() throws Exception {
        Customer c = customer();
        LocalDate today = LocalDate.now();
        mvc.perform(get("/api/billing").session(c.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billed").value(true))
                .andExpect(jsonPath("$.subscription.status").value("TRIAL"))
                .andExpect(jsonPath("$.subscription.trialEnds").value(today.plusDays(30).toString()))
                .andExpect(jsonPath("$.price.monthlyPrice").value(60.0))
                .andExpect(jsonPath("$.invoices.length()").value(0));

        // Nothing is due before the trial ends.
        job.run(today.plusDays(29));
        assertThat(count("select count(*) from invoice where company_id = ?", c.id())).isZero();

        LocalDate end = today.plusDays(30);
        job.run(end);
        job.run(end); // a second run the same day issues nothing more
        assertThat(count("select count(*) from invoice where company_id = ?", c.id())).isOne();
        mvc.perform(get("/api/billing").session(c.session()))
                .andExpect(jsonPath("$.subscription.status").value("ACTIVE"))
                .andExpect(jsonPath("$.subscription.nextInvoiceOn").value(end.plusMonths(1).toString()))
                .andExpect(jsonPath("$.invoices[0].number").value(Matchers.startsWith("HP-" + end.getYear() + "-")))
                .andExpect(jsonPath("$.invoices[0].periodFrom").value(end.toString()))
                .andExpect(jsonPath("$.invoices[0].periodTo").value(end.plusMonths(1).minusDays(1).toString()))
                .andExpect(jsonPath("$.invoices[0].dueOn").value(end.plusDays(15).toString()))
                .andExpect(jsonPath("$.invoices[0].currency").value("USD"))
                .andExpect(jsonPath("$.invoices[0].subtotal").value(60.0))
                .andExpect(jsonPath("$.invoices[0].tax").value(9.6))
                .andExpect(jsonPath("$.invoices[0].total").value(69.6));
        // The administrator is told by email.
        assertThat(tenants.callAs(c.id(), () -> jdbc.sql("select count(*) from outbox_message where subject like 'Facture HP-%'")
                .query(Integer.class).single())).isOne();
    }

    @Test
    void aCompanyCanPayYearlyInCongoleseFrancsAtTheBccRate() throws Exception {
        Customer c = customer();
        LocalDate today = LocalDate.now();
        if (rates.on("USD", today).isEmpty()) {
            tenants.callAsPlatform(() -> jdbc.sql("""
                    insert into exchange_rate (currency, rate_date, cdf_per_unit, source, entered_by)
                    values ('USD', ?, 2850, 'test', 'test')""").param(today).update());
        }
        BigDecimal rate = rates.on("USD", today).orElseThrow().cdfPerUnit();
        mvc.perform(json(put("/api/billing/subscription").session(c.session()), "{\"cycle\":\"YEARLY\",\"currency\":\"CDF\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cycle").value("YEARLY"));

        MockHttpSession provider = login(ADMIN);
        long id = issue(c.id(), provider);
        BigDecimal subtotal = BigDecimal.valueOf(600).multiply(rate).setScale(2, RoundingMode.HALF_UP);
        String r = mvc.perform(get("/api/platform/companies/" + c.id() + "/billing").session(provider))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoices[0].id").value(id))
                .andExpect(jsonPath("$.invoices[0].currency").value("CDF"))
                .andExpect(jsonPath("$.invoices[0].cycle").value("YEARLY"))
                .andExpect(jsonPath("$.usage.activeEmployees").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(JsonPath.read(r, "$.invoices[0].subtotal").toString())).isEqualByComparingTo(subtotal);
        assertThat(new BigDecimal(JsonPath.read(r, "$.invoices[0].usdCdf").toString())).isEqualByComparingTo(rate);

        // The PDF shows the invoice; another company never reaches it.
        mvc.perform(get("/api/billing/invoices/" + id + ".pdf").session(c.session()))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().string(Matchers.startsWith("%PDF")));
        Customer other = customer();
        mvc.perform(get("/api/billing/invoices/" + id + ".pdf").session(other.session())).andExpect(status().isNotFound());
    }

    @Test
    void anUnpaidInvoiceMakesTheCompanyReadOnlyUntilItIsPaid() throws Exception {
        Customer c = customer();
        MockHttpSession provider = login(ADMIN);
        long invoice = issue(c.id(), provider);
        LocalDate today = LocalDate.now();

        // Due in 15 days, then 15 days of grace: read-only from the day after.
        job.run(today.plusDays(30));
        assertThat(companyStatus(c.id())).isEqualTo("ACTIVE");
        job.run(today.plusDays(31));
        assertThat(companyStatus(c.id())).isEqualTo("SUSPENDED");
        tenants.callAsPlatform(() -> jdbc.sql("update subscription set next_invoice_on = ? where company_id = ?")
                .params(today.plusYears(1), c.id()).update()); // no more invoices in this test

        mvc.perform(get("/api/auth/me").session(c.session()))
                .andExpect(jsonPath("$.company.readOnly").value(Matchers.containsString("read-only")));
        mvc.perform(json(post("/api/users").session(c.session()), employee(c.id())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("read-only")));
        mvc.perform(get("/api/users").session(c.session())).andExpect(status().isOk());
        mvc.perform(get("/api/billing").session(c.session())).andExpect(status().isOk());

        // Part of it, then the same transaction twice, then the rest.
        mvc.perform(json(post("/api/platform/invoices/" + invoice + "/payments").session(provider),
                        payment("MPESA", "MP-001-" + c.id(), "20")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.paid").value(20.0));
        assertThat(companyStatus(c.id())).isEqualTo("SUSPENDED");
        mvc.perform(json(post("/api/platform/invoices/" + invoice + "/payments").session(provider),
                        payment("MPESA", "mp-001-" + c.id(), "20")))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/platform/invoices/" + invoice + "/payments").session(provider),
                        payment("BANK_TRANSFER", "TRF-" + c.id(), "60")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(post("/api/platform/invoices/" + invoice + "/payments").session(provider),
                        payment("ORANGE_MONEY", "OM-" + c.id(), "49.60")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(companyStatus(c.id())).isEqualTo("ACTIVE");
        mvc.perform(json(post("/api/users").session(c.session()), employee(c.id()))).andExpect(status().isCreated());

        // Both audit logs keep the story.
        assertThat(tenants.callAs(c.id(), () -> jdbc.sql("""
                select count(*) from audit_log where action in ('company.suspended', 'invoice.payment_recorded', 'company.reactivated')""")
                .query(Integer.class).single())).isEqualTo(4);
    }

    @Test
    void theProviderCanSuspendByHandAndCancelAnInvoiceIssuedByMistake() throws Exception {
        Customer c = customer();
        MockHttpSession provider = login(ADMIN);
        long invoice = issue(c.id(), provider);
        mvc.perform(json(post("/api/platform/invoices/" + invoice + "/void").session(provider), "{\"reason\":\"\"}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(post("/api/platform/invoices/" + invoice + "/void").session(provider), "{\"reason\":\"Wrong plan\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VOID"));
        // The same period can be invoiced again.
        long again = issue(c.id(), provider);
        assertThat(again).isNotEqualTo(invoice);

        mvc.perform(json(put("/api/platform/companies/" + c.id()).session(provider),
                        "{\"planCode\":\"UP_TO_50\",\"modules\":[\"ATTENDANCE\",\"PAYROLL\"],\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.suspendedReason").value("MANUAL"));
        mvc.perform(json(post("/api/users").session(c.session()), employee(c.id()))).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/platform/companies/" + c.id()).session(provider),
                        "{\"planCode\":\"UP_TO_50\",\"modules\":[\"ATTENDANCE\",\"PAYROLL\"],\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.suspendedReason").doesNotExist());
        mvc.perform(json(post("/api/users").session(c.session()), employee(c.id()))).andExpect(status().isCreated());
    }

    @Test
    void pricesAndRulesAreTheProvidersAndBillingIsTheAdministrators() throws Exception {
        MockHttpSession provider = login(ADMIN);
        mvc.perform(get("/api/platform/billing").session(provider))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SAAS"))
                .andExpect(jsonPath("$.settings.taxRate").value(16.0))
                // The installation's plans; another test may add a plan of its own (TenancyIT's TEST_ONE).
                .andExpect(jsonPath("$.prices[?(@.code != 'TEST_ONE')]", Matchers.hasSize(4)));
        mvc.perform(json(put("/api/platform/billing").session(provider), """
                        {"trialDays":30,"paymentDays":15,"suspendAfterDays":15,"taxLabel":"TVA","taxRate":120,
                         "invoicePrefix":"HP"}"""))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(put("/api/platform/plans/UP_TO_300/price").session(provider), "{\"monthlyPrice\":210,\"yearlyPrice\":2100}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.monthlyPrice").value(210.0));
        mvc.perform(json(put("/api/platform/plans/UP_TO_300/price").session(provider), "{\"monthlyPrice\":200,\"yearlyPrice\":2000}"))
                .andExpect(status().isOk());

        // The provider's own company is not billed; HR and companies' administrators cannot reach the console.
        mvc.perform(get("/api/billing").session(provider)).andExpect(status().isOk()).andExpect(jsonPath("$.billed").value(false));
        mvc.perform(get("/api/billing").session(login(HR))).andExpect(status().isForbidden());
        Customer c = customer();
        mvc.perform(get("/api/platform/invoices").session(c.session())).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/platform/plans/UP_TO_50/price").session(c.session()), "{\"monthlyPrice\":1,\"yearlyPrice\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void supportAccessNeedsTheCompanysConsentIsReadOnlyAndLogged() throws Exception {
        Customer c = customer();
        MockHttpSession provider = login(ADMIN);
        mvc.perform(post("/api/platform/companies/" + c.id() + "/support").session(provider).with(csrf()))
                .andExpect(status().isUnprocessableContent());

        mvc.perform(json(post("/api/billing/support").session(c.session()), "{\"days\":30}"))
                .andExpect(status().isUnprocessableContent());
        String g = mvc.perform(json(post("/api/billing/support").session(c.session()), "{\"days\":3,\"reason\":\"Payroll question\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString();
        long grant = ((Number) JsonPath.read(g, "$.id")).longValue();

        mvc.perform(post("/api/platform/companies/" + c.id() + "/support").session(provider).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.companyId").value(c.id()));
        mvc.perform(get("/api/auth/me").session(provider))
                .andExpect(jsonPath("$.email").value(ADMIN))
                .andExpect(jsonPath("$.company.id").value(c.id()))
                .andExpect(jsonPath("$.support.companyId").value(c.id()))
                .andExpect(jsonPath("$.permissions", Matchers.not(Matchers.hasItem("platform.manage"))));
        mvc.perform(get("/api/users").session(provider))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].email").value(c.email()));
        mvc.perform(json(post("/api/users").session(provider), employee(c.id())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("Support access is read-only")));
        mvc.perform(get("/api/platform/companies").session(provider)).andExpect(status().isForbidden());

        // The company sees every request, and closes the access; the visit ends at the next request.
        mvc.perform(get("/api/billing").session(c.session()))
                .andExpect(jsonPath("$.supportRequests[0].visitor").value(ADMIN))
                .andExpect(jsonPath("$.support[0].requests").value(Matchers.greaterThanOrEqualTo(3)));
        mvc.perform(delete("/api/billing/support/" + grant).session(c.session()).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(provider))
                .andExpect(jsonPath("$.company.id").value(1))
                .andExpect(jsonPath("$.support").doesNotExist());
        mvc.perform(get("/api/platform/companies").session(provider)).andExpect(status().isOk());

        assertThat(tenants.callAs(c.id(), () -> jdbc.sql(
                "select count(*) from audit_log where action in ('support.granted', 'support.started', 'support.revoked')")
                .query(Integer.class).single())).isEqualTo(3);
    }

    @Test
    void theProviderEndsASupportVisitByHand() throws Exception {
        Customer c = customer();
        MockHttpSession provider = login(ADMIN);
        mvc.perform(json(post("/api/billing/support").session(c.session()), "{\"days\":1}")).andExpect(status().isCreated());
        mvc.perform(post("/api/platform/companies/" + c.id() + "/support").session(provider).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/support/end").session(provider).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(provider)).andExpect(jsonPath("$.company.id").value(1));
        assertThat(tenants.callAs(c.id(), () -> jdbc.sql("select count(*) from audit_log where action = 'support.ended'")
                .query(Integer.class).single())).isOne();
    }

    /** A new employee of the company, in its top unit. */
    private String employee(long companyId) {
        long top = tenants.callAs(companyId, () -> jdbc.sql("select id from org_unit where parent_id is null")
                .query(Long.class).single());
        return newUser("[\"EMPLOYEE\"]").replace("\"orgUnitId\":1,", "\"orgUnitId\":" + top + ",");
    }

    private String companyStatus(long id) {
        return tenants.callAsPlatform(() -> jdbc.sql("select status from company where id = ?").param(id)
                .query(String.class).single());
    }

    private int count(String sql, Object... params) {
        return tenants.callAsPlatform(() -> jdbc.sql(sql).params(params).query(Integer.class).single());
    }
}
