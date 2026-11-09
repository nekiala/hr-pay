package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.payroll.ExchangeRates;
import cd.hrpay.payroll.PayrollChecks;
import cd.hrpay.security.DataCrypto;
import cd.hrpay.tenancy.Tenants;
import com.jayway.jsonpath.JsonPath;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/**
 * Checks before a month is approved: a legal one blocks until it is fixed, the others are accepted with a reason, and a
 * new calculation starts the acceptances over.
 */
class PayrollChecksIT extends IntegrationTest {

    @Autowired
    ExchangeRates rates;

    @Autowired
    PayrollChecks checks;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataCrypto crypto;

    @Autowired
    Tenants tenants;

    @BeforeEach
    void on() {
        checks.setRequired(true);
    }

    @AfterEach
    void off() {
        checks.setRequired(false);
    }

    long id(String email) {
        return jdbc.sql("select id from app_user where email = ?").param(email).query(Long.class).single();
    }

    @Test
    void aMonthIsApprovedOnlyOnceItsChecksAreFixedOrAccepted() throws Exception {
        rates.store("""
                {"data":[{"currency_code":"USD","rate_date":"2025-09-20","indicative_rate":"2250"}]}""");
        MockHttpSession hr = login(HR);
        MockHttpSession finance = login(FINANCE);
        long one = id("employee1@demo.hrpay.cd");
        long two = id("employee2@demo.hrpay.cd");
        // Two people paid into the same bank account.
        String account = crypto.encrypt("CD12 3456 7890");
        tenants.runAs(1L, () -> {
            for (long u : List.of(one, two)) {
                jdbc.sql("""
                        insert into employee_profile (user_id, payment_method, bank_account, cnss_number, tax_number)
                        values (?, 'BANK', ?, ?, ?) on conflict (user_id) do update set payment_method = 'BANK',
                        bank_account = excluded.bank_account""")
                        .params(u, account, crypto.encrypt("CNSS-" + u), crypto.encrypt("NIF-" + u)).update();
            }
        });

        mvc.perform(post("/api/payroll/runs/2025-09/calculate").session(hr).with(csrf())).andExpect(status().isOk());
        String report = mvc.perform(get("/api/payroll/runs/2025-09/checks").session(finance)).andExpect(status().isOk())
                .andExpect(jsonPath("$.clear").value(false))
                .andExpect(jsonPath("$.settings.netChangePercent").value(20))
                .andExpect(jsonPath("$.checks[?(@.code == 'SHARED_ACCOUNT' && @.userId == %d)]".formatted(one)).exists())
                .andExpect(jsonPath("$.checks[?(@.code == 'SHARED_ACCOUNT' && @.userId == %d)]".formatted(two)).exists())
                .andExpect(jsonPath("$.checks[?(@.code == 'RATE_OLD')].userId").value(org.hamcrest.Matchers.contains((Object) null)))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(report, "$.legal")).isZero();

        // Open warnings stop the approval.
        mvc.perform(post("/api/payroll/runs/2025-09/approve").session(finance).with(csrf())).andExpect(status().is(422));

        // A net pay below zero is against the law: it cannot be accepted, only fixed.
        tenants.runAs(1L, () -> jdbc.sql("""
                update payslip set net = -10 where user_id = ? and run_id = (select id from payroll_run where month = '2025-09-01')""")
                .param(one).update());
        String broken = mvc.perform(get("/api/payroll/runs/2025-09/checks").session(hr))
                .andExpect(jsonPath("$.legal").value(1))
                .andExpect(jsonPath("$.checks[0].code").value("NET_NEGATIVE"))
                .andExpect(jsonPath("$.checks[0].level").value("LEGAL"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(json(post("/api/payroll/runs/2025-09/checks/accept").session(hr),
                "{\"code\":\"NET_NEGATIVE\",\"reason\":\"We will pay it later\"}")).andExpect(status().is(422));

        // HR accepts every warning with a reason; Finance cannot accept.
        // (the changed net can raise a warning of its own, so the codes are read again)
        Set<String> codes = new LinkedHashSet<>(JsonPath.read(report, "$.checks[?(@.level == 'WARNING')].code"));
        codes.addAll(JsonPath.read(broken, "$.checks[?(@.level == 'WARNING')].code"));
        mvc.perform(json(post("/api/payroll/runs/2025-09/checks/accept").session(finance),
                "{\"code\":\"SHARED_ACCOUNT\",\"reason\":\"Married couple, one account\"}")).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/payroll/runs/2025-09/checks/accept").session(hr),
                        "{\"code\":\"SHARED_ACCOUNT\",\"userIds\":[%d],\"reason\":\"ok\"}".formatted(one)))
                .andExpect(status().is(422));
        for (String code : codes) {
            mvc.perform(json(post("/api/payroll/runs/2025-09/checks/accept").session(hr),
                            "{\"code\":\"%s\",\"reason\":\"Checked with the team\"}".formatted(code)))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/payroll/runs/2025-09/checks").session(hr))
                .andExpect(jsonPath("$.warnings").value(0))
                .andExpect(jsonPath("$.legal").value(1))
                .andExpect(jsonPath("$.checks[?(@.code == 'SHARED_ACCOUNT')].reason").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("Checked with the team"))));
        mvc.perform(post("/api/payroll/runs/2025-09/approve").session(finance).with(csrf())).andExpect(status().is(422));

        // Calculating again fixes the payslip and starts the acceptances over.
        mvc.perform(post("/api/payroll/runs/2025-09/calculate").session(hr).with(csrf())).andExpect(status().isOk());
        String fresh = mvc.perform(get("/api/payroll/runs/2025-09/checks").session(hr))
                .andExpect(jsonPath("$.legal").value(0))
                .andExpect(jsonPath("$.accepted").value(0))
                .andReturn().getResponse().getContentAsString();
        for (String code : new LinkedHashSet<String>(JsonPath.read(fresh, "$.checks[?(@.level == 'WARNING')].code"))) {
            mvc.perform(json(post("/api/payroll/runs/2025-09/checks/accept").session(hr),
                    "{\"code\":\"%s\",\"reason\":\"Checked with the team\"}".formatted(code))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/payroll/runs/2025-09/checks").session(hr)).andExpect(jsonPath("$.clear").value(true));
        mvc.perform(post("/api/payroll/runs/2025-09/approve").session(finance).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.run.status").value("APPROVED"));

        // The thresholds belong to the company.
        mvc.perform(json(put("/api/payroll/check-settings").session(hr), "{\"netChangePercent\":0,\"rateMaxDays\":7}"))
                .andExpect(status().is(422));
        mvc.perform(json(put("/api/payroll/check-settings").session(hr), "{\"netChangePercent\":15,\"rateMaxDays\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.netChangePercent").value(15));
        mvc.perform(get("/api/payroll/runs/2025-09/checks").session(login(EMPLOYEE))).andExpect(status().isForbidden());
    }
}
