package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

/** No work, hire date or salary without a contract; the employee ID from the company's format. */
class ContractFirstIT extends IntegrationTest {

    private long create(MockHttpSession s, String body) throws Exception {
        String created = mvc.perform(json(post("/api/users").session(s), body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(created, "$.id").longValue();
    }

    private long grade(MockHttpSession s, String code) throws Exception {
        String grades = mvc.perform(get("/api/grades").session(s)).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Number>>read(grades, "$[?(@.code=='" + code + "')].id").getFirst().longValue();
    }

    @Test
    void nothingBeforeTheContractThenTheContractSetsHireDateAndFirstSalary() throws Exception {
        MockHttpSession hr = login(HR);
        long id = create(hr, newUser(null));
        String base = "/api/users/" + id;

        mvc.perform(get(base).session(hr))
                .andExpect(jsonPath("$.hireDate").doesNotExist())
                .andExpect(jsonPath("$.underContract").value(false))
                .andExpect(jsonPath("$.notEmployee").value(false));
        String list = mvc.perform(get("/api/users?noContract=true&size=100").session(hr)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Number>>read(list, "$.items[*].id").stream().map(Number::longValue)).contains(id);

        // No salary, no attendance without a contract.
        mvc.perform(json(post(base + "/salaries").session(hr), """
                        {"effectiveFrom":"2026-10-01","grossMonthly":500,"currency":"USD"}"""))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no contract")));
        mvc.perform(json(post("/api/attendance/users/" + id + "/events").session(hr), """
                        {"type":"IN","at":"2026-10-05T08:00:00","note":"Forgot to check in"}"""))
                .andExpect(status().is(422));
        // The hire date is no longer typed: the employment card only keeps the category.
        mvc.perform(json(put(base + "/employment").session(hr), "{\"gradeCategoryId\":null,\"hireDate\":\"2020-01-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hireDate").doesNotExist());

        // The first contract: hire date from "in the company since", first salary from the grade.
        mvc.perform(json(post(base + "/contracts").session(hr), """
                        {"contractType":"PERMANENT","startDate":"2026-10-01","gradeId":%d,"expatriate":false,
                         "hireDate":"2026-10-02"}""".formatted(grade(hr, "E4A"))))
                .andExpect(status().is(422));
        mvc.perform(json(post(base + "/contracts").session(hr), """
                        {"contractType":"PERMANENT","startDate":"2026-10-01","gradeId":%d,"expatriate":false,
                         "hireDate":"2019-03-01"}""".formatted(grade(hr, "E4A"))))
                .andExpect(status().isCreated());
        mvc.perform(get(base).session(hr))
                .andExpect(jsonPath("$.hireDate").value("2019-03-01"))
                .andExpect(jsonPath("$.underContract").value(true));
        mvc.perform(get(base + "/salaries").session(hr))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].effectiveFrom").value("2026-10-01"));
        mvc.perform(json(post("/api/attendance/users/" + id + "/events").session(hr), """
                        {"type":"IN","at":"2026-10-05T08:00:00","note":"Forgot to check in"}"""))
                .andExpect(status().isCreated());
        mvc.perform(json(post("/api/attendance/users/" + id + "/events").session(hr), """
                        {"type":"OUT","at":"2026-10-05T16:00:00","note":"Forgot to check out"}"""))
                .andExpect(status().isCreated());
        // A later salary under the contract is a raise.
        mvc.perform(json(post(base + "/salaries").session(hr), """
                        {"effectiveFrom":"2026-12-01","grossMonthly":%s,"currency":"USD"}""".formatted("1000")))
                .andExpect(status().isCreated());
    }

    @Test
    void onlyAnAdministratorMarksAnAccountAsNotAnEmployee() throws Exception {
        MockHttpSession hr = login(HR);
        MockHttpSession admin = login(ADMIN);
        long id = create(hr, newUser(null));
        String base = "/api/users/" + id;
        mvc.perform(json(put(base + "/not-employee").session(hr), "{\"notEmployee\":true}")).andExpect(status().isForbidden());
        mvc.perform(json(put(base + "/not-employee").session(admin), "{\"notEmployee\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notEmployee").value(true));
        String list = mvc.perform(get("/api/users?noContract=true&size=100").session(hr))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Number>>read(list, "$.items[*].id").stream().map(Number::longValue)).doesNotContain(id);
        // Not an employee: no contract.
        mvc.perform(json(post(base + "/contracts").session(hr), """
                        {"contractType":"PERMANENT","startDate":"2026-10-01","gradeId":%d,"expatriate":false}""".formatted(grade(hr, "E4A"))))
                .andExpect(status().is(422));
        // Someone under contract stays an employee.
        long employee = JsonPath.<Number>read(mvc.perform(get("/api/users?q=employee3").session(hr)).andReturn().getResponse()
                .getContentAsString(), "$.items[0].id").longValue();
        mvc.perform(json(put("/api/users/" + employee + "/not-employee").session(admin), "{\"notEmployee\":true}"))
                .andExpect(status().is(422));
        // The sample administrator is not staff.
        long adminId = JsonPath.<Number>read(mvc.perform(get("/api/users?q=admin@demo").session(hr)).andReturn().getResponse()
                .getContentAsString(), "$.items[0].id").longValue();
        mvc.perform(get("/api/users/" + adminId).session(hr)).andExpect(jsonPath("$.notEmployee").value(true));
    }

    @Test
    void theEmployeeIdComesFromTheCompanyFormat() throws Exception {
        MockHttpSession hr = login(HR);
        MockHttpSession admin = login(ADMIN);
        mvc.perform(json(put("/api/matricule-format").session(hr), """
                        {"enabled":true,"pattern":"AUTO-{SEQ:3}","resetYearly":false,"nextSeq":7}"""))
                .andExpect(status().isForbidden());
        mvc.perform(json(put("/api/matricule-format").session(admin), """
                        {"enabled":true,"pattern":"AUTO-{XX}-{SEQ:3}","resetYearly":false,"nextSeq":7}"""))
                .andExpect(status().is(422));
        mvc.perform(json(put("/api/matricule-format").session(admin), """
                        {"enabled":true,"pattern":"AUTO-{YY}","resetYearly":false,"nextSeq":7}"""))
                .andExpect(status().is(422));
        try {
            mvc.perform(json(put("/api/matricule-format").session(admin), """
                            {"enabled":true,"pattern":"auto-{SEQ:3}","resetYearly":true,"nextSeq":7}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.next").value("AUTO-007"));
            mvc.perform(get("/api/matricule-format").session(hr)).andExpect(jsonPath("$.enabled").value(true));
            mvc.perform(json(post("/api/matricule-format/preview").session(admin),
                            "{\"pattern\":\"{YYYY}-{SEQ:4}\",\"nextSeq\":12}"))
                    .andExpect(jsonPath("$.value").value(org.hamcrest.Matchers.matchesPattern("\\d{4}-0012")));

            String blank = newUser(null).replaceFirst("\"registrationNo\":\"[^\"]*\"", "\"registrationNo\":\"\"");
            long first = create(hr, blank);
            mvc.perform(get("/api/users/" + first).session(hr)).andExpect(jsonPath("$.registrationNo").value("AUTO-007"));
            // A number already in use is skipped.
            create(hr, newUser(null).replaceFirst("\"registrationNo\":\"[^\"]*\"", "\"registrationNo\":\"AUTO-008\""));
            long third = create(hr, newUser(null).replaceFirst("\"registrationNo\":\"[^\"]*\"", "\"registrationNo\":\"\""));
            mvc.perform(get("/api/users/" + third).session(hr)).andExpect(jsonPath("$.registrationNo").value("AUTO-009"));
            mvc.perform(get("/api/matricule-format").session(hr)).andExpect(jsonPath("$.nextSeq").value(10));
        } finally {
            mvc.perform(json(put("/api/matricule-format").session(admin), """
                    {"enabled":false,"pattern":"{SEQ:5}","resetYearly":false,"nextSeq":1}""")).andExpect(status().isOk());
        }
        // Without a format, the employee ID is typed.
        mvc.perform(json(post("/api/users").session(hr),
                        newUser(null).replaceFirst("\"registrationNo\":\"[^\"]*\"", "\"registrationNo\":\"\"")))
                .andExpect(status().is(422));
    }
}
