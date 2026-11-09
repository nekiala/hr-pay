package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.tenancy.Tenants;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/**
 * An accounting firm works for its clients with the rights each client gave it, its staff only for the clients they
 * are given, every step in the client's audit log, and either side can end it; HR Pay is paid by the client (the firm
 * earns a commission) or by the firm at the wholesale price.
 */
class AccountantPortalIT extends IntegrationTest {

    @Autowired
    Tenants tenants;

    @Autowired
    JdbcClient jdbc;

    private static int companies;

    record Company(long id, String email, MockHttpSession session) {}

    private Company company(String name) throws Exception {
        int n = ++companies;
        String email = "boss" + n + "." + System.nanoTime() % 100000 + "@" + name.toLowerCase().replace(' ', '-') + ".cd";
        String body = """
                {"name":"%s","countryCode":"CD","planCode":"UP_TO_50","modules":["ATTENDANCE","PAYROLL"],
                 "admin":{"firstName":"Ada","lastName":"Boss","email":"%s","phone":"+2438160%05d"}}"""
                .formatted(name, email, (int) (System.nanoTime() % 100000));
        String response = mvc.perform(json(post("/api/platform/companies").session(login(ADMIN)), body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(response, "$.company.id")).longValue();
        String password = JsonPath.read(response, "$.temporaryPassword");
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))).andExpect(status().isOk());
        mvc.perform(json(post("/api/auth/password").session(session),
                "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(password, PASSWORD))).andExpect(status().isNoContent());
        return new Company(id, email, login(email));
    }

    private Company firm(String name) throws Exception {
        Company c = company(name);
        tenants.callAsPlatform(() -> {
            jdbc.sql("update company set kind = 'FIRM', firm_registration = 'ONEC-123' where id = ?").param(c.id()).update();
            jdbc.sql("delete from subscription where company_id = ?").param(c.id()).update();
            return null;
        });
        return c;
    }

    /** A staff member of the company, who signs in with the test password. */
    private String staff(long companyId, MockHttpSession admin) throws Exception {
        long top = tenants.callAs(companyId, () -> jdbc.sql("select id from org_unit where parent_id is null").query(Long.class).single());
        String body = newUser("[\"EMPLOYEE\"]").replace("\"orgUnitId\":1,", "\"orgUnitId\":" + top + ",")
                .replace("}}", "},\"initialPassword\":\"" + PASSWORD + "\"}");
        String created = mvc.perform(json(post("/api/users").session(admin), body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.email");
    }

    private long invite(Company client, Company firm, String roles) throws Exception {
        String r = mvc.perform(json(post("/api/company/firms").session(client.session()),
                        "{\"firmEmail\":\"%s\",\"roleCodes\":%s,\"clauseAccepted\":true}".formatted(firm.email(), roles)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("INVITED"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(r, "$.id")).longValue();
    }

    @Test
    void aFirmWorksInItsClientsWithTheRightsTheyGave() throws Exception {
        Company firm = firm("Cabinet Mbodo");
        Company client = company("Client Kin");
        Company other = company("Client Other");

        // The client accepts the processing clause and invites the firm by its administrator's email.
        mvc.perform(json(post("/api/company/firms").session(client.session()),
                        "{\"firmEmail\":\"%s\",\"roleCodes\":[\"HR\"],\"clauseAccepted\":false}".formatted(firm.email())))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(post("/api/company/firms").session(client.session()),
                        "{\"firmEmail\":\"%s\",\"roleCodes\":[\"HR\"],\"clauseAccepted\":true}".formatted(other.email())))
                .andExpect(status().isUnprocessableContent());
        long link = invite(client, firm, "[\"HR\"]");
        mvc.perform(json(post("/api/company/firms").session(client.session()),
                        "{\"firmEmail\":\"%s\",\"roleCodes\":[\"HR\"],\"clauseAccepted\":true}".formatted(firm.email())))
                .andExpect(status().isUnprocessableContent());

        // The firm sees the invitation and accepts it; the client pays HR Pay itself.
        mvc.perform(get("/api/auth/me").session(firm.session())).andExpect(jsonPath("$.firmMember").value(true));
        mvc.perform(get("/api/firm").session(firm.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.clients[0].status").value("INVITED"))
                .andExpect(jsonPath("$.clients[0].clientName").value("Client Kin"));
        mvc.perform(json(post("/api/firm/clients/" + link + "/open").session(firm.session()), "{}"))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/api/firm/clients/" + link + "/accept").session(firm.session()), "{\"billing\":\"MAYBE\"}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(post("/api/firm/clients/" + link + "/accept").session(firm.session()), "{\"billing\":\"COMMISSION\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/firm").session(firm.session()))
                .andExpect(jsonPath("$.clients[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.clients[0].employees").value(1));

        // A staff member of the firm works only for the clients they are given.
        String clerk = staff(firm.id(), firm.session());
        MockHttpSession clerkSession = login(clerk);
        mvc.perform(get("/api/firm").session(clerkSession)).andExpect(jsonPath("$.clients.length()").value(0));
        mvc.perform(json(post("/api/firm/clients/" + link + "/open").session(clerkSession), "{}"))
                .andExpect(status().isUnprocessableContent());
        long clerkId = tenants.callAs(firm.id(), () -> jdbc.sql("select id from app_user where email = ?").param(clerk)
                .query(Long.class).single());
        mvc.perform(json(put("/api/firm/clients/" + link + "/staff").session(firm.session()),
                "{\"userIds\":[%d]}".formatted(clerkId))).andExpect(status().isNoContent());
        mvc.perform(json(post("/api/firm/clients/" + link + "/open").session(clerkSession), "{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.clientName").value("Client Kin"));

        // In the client: its data, with HR's rights but never billing nor the firm links.
        mvc.perform(get("/api/auth/me").session(clerkSession))
                .andExpect(jsonPath("$.company.id").value(client.id()))
                .andExpect(jsonPath("$.firm.clientName").value("Client Kin"))
                .andExpect(jsonPath("$.firm.firmName").value("Cabinet Mbodo"))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.hasItem("user.read")))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("subscription.manage"))));
        mvc.perform(get("/api/users").session(clerkSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].email").value(client.email()));
        mvc.perform(get("/api/company/firms").session(clerkSession)).andExpect(status().isForbidden());
        mvc.perform(get("/api/billing").session(clerkSession)).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/users").session(clerkSession), staffBody(client.id()))).andExpect(status().isCreated());
        assertThat(tenants.callAs(client.id(), () -> jdbc.sql("select actor from audit_log where action = 'user.created' order by id desc limit 1")
                .query(String.class).single())).isEqualTo(clerk + " (Cabinet Mbodo)");

        // The client sees what the firm opened, and can end it at once.
        mvc.perform(get("/api/company/firms/" + link + "/visits").session(client.session()))
                .andExpect(jsonPath("$[?(@.path == '/api/users' && @.method == 'POST')]").exists());
        mvc.perform(json(post("/api/company/firms/" + link + "/end").session(client.session()), "{\"reason\":\"New firm\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENDED"));
        mvc.perform(get("/api/auth/me").session(clerkSession)).andExpect(jsonPath("$.company.id").value(firm.id()));
        mvc.perform(get("/api/users").session(clerkSession)).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/firm/clients/" + link + "/open").session(firm.session()), "{}"))
                .andExpect(status().isNotFound());
    }

    private String staffBody(long companyId) {
        long top = tenants.callAs(companyId, () -> jdbc.sql("select id from org_unit where parent_id is null").query(Long.class).single());
        return newUser("[\"EMPLOYEE\"]").replace("\"orgUnitId\":1,", "\"orgUnitId\":" + top + ",");
    }

    @Test
    void theClientOrTheFirmPaysHrPay() throws Exception {
        MockHttpSession provider = login(ADMIN);
        mvc.perform(json(put("/api/platform/firms/terms").session(provider), "{\"commissionPercent\":10,\"discountPercent\":20}"))
                .andExpect(status().isOk());
        Company firm = firm("Cabinet Pay");
        Company payer = company("Client Payer");
        Company billed = company("Client Billed");
        long a = invite(payer, firm, "[\"FINANCE\"]");
        long b = invite(billed, firm, "[\"FINANCE\"]");
        mvc.perform(json(post("/api/firm/clients/" + a + "/accept").session(firm.session()), "{\"billing\":\"COMMISSION\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(json(post("/api/firm/clients/" + b + "/accept").session(firm.session()), "{\"billing\":\"WHOLESALE\"}"))
                .andExpect(status().isNoContent());

        // The client that pays itself: the full price, and the firm earns 10% once it is paid.
        String r = mvc.perform(post("/api/platform/companies/" + payer.id() + "/invoices").session(provider).with(csrf()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.subtotal").value(60.0))
                .andReturn().getResponse().getContentAsString();
        long invoice = ((Number) JsonPath.read(r, "$.id")).longValue();
        double total = ((Number) JsonPath.read(r, "$.total")).doubleValue();
        mvc.perform(json(post("/api/platform/invoices/" + invoice + "/payments").session(provider),
                "{\"method\":\"MPESA\",\"reference\":\"FIRM-%d\",\"amount\":%s}".formatted(invoice, total))).andExpect(status().isOk());

        // The client the firm pays for: 20% off, addressed to the firm.
        mvc.perform(post("/api/platform/companies/" + billed.id() + "/invoices").session(provider).with(csrf()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.subtotal").value(48.0))
                .andExpect(jsonPath("$.customerName").value("Cabinet Pay"));

        mvc.perform(get("/api/firm").session(firm.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.commissions[0].amount").value(6.0))
                .andExpect(jsonPath("$.commissions[0].clientName").value("Client Payer"))
                .andExpect(jsonPath("$.invoices[0].clientName").value("Client Billed"))
                .andExpect(jsonPath("$.invoices[0].discountPercent").value(20.0));
        String all = mvc.perform(get("/api/platform/firms/commissions").session(provider)).andReturn().getResponse().getContentAsString();
        long commission = ((Number) JsonPath.<List<Object>>read(all, "$[?(@.invoiceNumber && @.firmName == 'Cabinet Pay')].id").getFirst()).longValue();
        mvc.perform(json(post("/api/platform/firms/commissions/" + commission + "/paid").session(provider), "{\"reference\":\"TRF-9\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/platform/firms/commissions").session(login(HR))).andExpect(status().isForbidden());
    }

    @Test
    void aFirmAsksToJoinAndTheProviderApproves() throws Exception {
        String email = "cabinet" + System.nanoTime() % 100000 + "@firm.cd";
        mvc.perform(post("/api/trials").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                {"companyName":"Cabinet Trial","countryCode":"CG","employees":"1-50","firstName":"Albert","lastName":"M",
                 "email":"%s","phone":"+242060000000","language":"fr","kind":"FIRM","firmRegistration":"ONECCA 42"}""".formatted(email)))
                .andExpect(status().isNoContent());
        MockHttpSession provider = login(ADMIN);
        String list = mvc.perform(get("/api/platform/trials").session(provider)).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.<List<Object>>read(list, "$[?(@.email == '" + email + "')].id").getFirst()).longValue();
        assertThat(JsonPath.<List<String>>read(list, "$[?(@.email == '" + email + "')].kind")).containsExactly("FIRM");
        String approved = mvc.perform(json(post("/api/platform/trials/" + id + "/approve").session(provider),
                "{\"planCode\":\"UP_TO_50\",\"modules\":[\"PAYROLL\"]}")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long company = ((Number) JsonPath.read(approved, "$.companyId")).longValue();
        record Row(String kind, String firmRegistration, int subscriptions) {}
        Row row = tenants.callAsPlatform(() -> jdbc.sql("""
                select kind, firm_registration, (select count(*) from subscription where company_id = c.id) as subscriptions
                from company c where id = ?""").param(company).query(Row.class).single());
        assertThat(row).isEqualTo(new Row("FIRM", "ONECCA 42", 0));
    }
}
