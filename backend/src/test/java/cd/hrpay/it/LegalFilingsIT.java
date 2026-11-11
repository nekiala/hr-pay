package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** The DRC hiring and departure declarations, the annual workforce declaration, the bilan social and the register. */
class LegalFilingsIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    private MockHttpSession hrFromStart() throws Exception {
        MockHttpSession hr = login(HR);
        mvc.perform(json(put("/api/legal-filings/settings").session(hr), "{\"filingsFrom\":\"2015-01-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.country").value("CD"))
                .andExpect(jsonPath("$.movements").value(true))
                .andExpect(jsonPath("$.personnelRegister").value(false));
        return hr;
    }

    @Test
    void hiringsAreListedPrintedAndRecordedAsFiled() throws Exception {
        MockHttpSession hr = hrFromStart();
        String page = mvc.perform(get("/api/legal-filings").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rules[?(@.kind=='HIRE')].dueDays").value(15))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> hires = JsonPath.read(page, "$.movements[?(@.kind=='HIRE')]");
        assertThat(hires).isNotEmpty();
        Map<String, Object> h = hires.getFirst();
        Number userId = (Number) h.get("userId");
        String event = (String) h.get("eventDate");
        assertThat(LocalDate.parse((String) h.get("due"))).isEqualTo(LocalDate.parse(event).plusDays(15));
        String base = "/api/legal-filings/movements/HIRE/" + userId + "/" + event;

        mvc.perform(get(base).session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("legal.hire"))
                .andExpect(jsonPath("$.tables.length()").value(3))
                .andExpect(jsonPath("$.tables[2].rows[?(@.cells[0]=='Hiring date')]").isNotEmpty());
        byte[] pdf = mvc.perform(get(base).param("format", "pdf").session(hr)).andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf")).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");

        mvc.perform(json(post(base + "/filed").session(hr), "{\"filedOn\":\"" + LocalDate.now() + "\",\"reference\":\"IT-123\"}"))
                .andExpect(status().isOk());
        String after = mvc.perform(get("/api/legal-filings").session(hr)).andReturn().getResponse().getContentAsString();
        List<String> statuses = JsonPath.read(after, "$.movements[?(@.kind=='HIRE' && @.userId==" + userId + " && @.eventDate=='" + event + "')].status");
        assertThat(statuses).containsExactly("FILED");
        mvc.perform(json(post(base + "/filed").session(hr), "{\"filedOn\":\"" + LocalDate.now() + "\"}"))
                .andExpect(status().isConflict());
        mvc.perform(json(post(base + "/filed").session(hr), "{\"filedOn\":\"" + LocalDate.now().plusDays(3) + "\"}"))
                .andExpect(status().isConflict());
        mvc.perform(delete(base + "/filed").session(hr).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'filing.exported'").query(Integer.class).single()).isPositive();

        mvc.perform(get("/api/legal-filings/movements").param("from", "2015-01-01").param("to", LocalDate.now().toString())
                        .param("format", "xlsx").session(hr))
                .andExpect(status().isOk());
    }

    @Test
    void theAnnualWorkforceAndBilanSocialCountTheStaff() throws Exception {
        MockHttpSession hr = hrFromStart();
        int year = LocalDate.now().getYear();
        String workforce = mvc.perform(get("/api/legal-filings/annual/WORKFORCE/" + year).session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("legal.workforce"))
                .andReturn().getResponse().getContentAsString();
        Integer total = JsonPath.read(workforce, "$.tables[1].rows[-1].cells[6]");
        int staff = jdbc.sql("""
                select count(distinct c.user_id) from employee_contract c join app_user u on u.id = c.user_id
                where not u.not_employee and c.contract_type <> 'CONSULTANT' and c.start_date <= ? and (c.end_date is null or c.end_date >= ?)
                  and not exists (select 1 from departure x where x.user_id = c.user_id and x.status = 'CONFIRMED' and x.last_day < ? and x.last_day >= c.start_date)""")
                .params(LocalDate.of(year, 1, 1), LocalDate.of(year, 1, 1), LocalDate.of(year, 1, 1)).query(Integer.class).single();
        assertThat(total).isEqualTo(staff);
        mvc.perform(get("/api/legal-filings/annual/WORKFORCE/" + year).param("format", "xlsx").session(hr)).andExpect(status().isOk());
        mvc.perform(get("/api/legal-filings/annual/BILAN_SOCIAL/" + (year - 1)).param("format", "pdf").session(hr))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
        mvc.perform(get("/api/legal-filings/annual/BILAN_SOCIAL/" + (year - 1)).session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[1].rows[0].cells[1]").isNumber());

        mvc.perform(json(post("/api/legal-filings/annual/BILAN_SOCIAL/" + (year - 1) + "/filed").session(hr),
                "{\"filedOn\":\"" + LocalDate.now() + "\"}")).andExpect(status().isOk());
        mvc.perform(json(post("/api/legal-filings/annual/BILAN_SOCIAL/" + (year - 1) + "/filed").session(hr),
                "{\"filedOn\":\"" + LocalDate.now() + "\"}")).andExpect(status().isConflict());
    }

    @Test
    void theRegisterListsPeoplePayAndInspectorVisits() throws Exception {
        MockHttpSession hr = hrFromStart();
        mvc.perform(json(post("/api/legal-filings/register/visits").session(hr),
                        "{\"visitedOn\":\"" + LocalDate.now().minusDays(2) + "\",\"inspector\":\"M. Inspecteur\",\"observations\":\"Registre à jour.\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.inspector").value("M. Inspecteur"));
        mvc.perform(json(post("/api/legal-filings/register/visits").session(hr), "{\"visitedOn\":\"" + LocalDate.now() + "\",\"inspector\":\" \"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/legal-filings/register").param("from", LocalDate.now().minusMonths(6).toString())
                        .param("to", LocalDate.now().toString()).session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.tables.length()").value(3))
                .andExpect(jsonPath("$.tables[0].rows[0].cells[0]").value(1))
                .andExpect(jsonPath("$.tables[2].rows[0].cells[1]").value("M. Inspecteur"));
        mvc.perform(get("/api/legal-filings/register").param("from", LocalDate.now().minusMonths(6).toString())
                        .param("to", LocalDate.now().toString()).param("format", "pdf").session(hr)).andExpect(status().isOk());
    }

    @Test
    void employeesCannotSeeTheFilings() throws Exception {
        mvc.perform(get("/api/legal-filings").session(login(EMPLOYEE))).andExpect(status().isForbidden());
    }
}
