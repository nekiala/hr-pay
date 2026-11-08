package cd.hrpay.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

class LeaveIT extends IntegrationTest {

    static final String EMPLOYEE2 = "employee2@demo.hrpay.cd";
    static final String MANAGER = "manager@demo.hrpay.cd";

    @Autowired
    JdbcClient jdbc;

    long type(MockHttpSession s, String code) throws Exception {
        String types = mvc.perform(get("/api/leave/types").session(s)).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Number>>read(types, "$[?(@.code=='" + code + "')].id").getFirst().longValue();
    }

    @Test
    void annualLeaveFromRequestToApprovalAndBack() throws Exception {
        MockHttpSession me = login(EMPLOYEE2);
        long annual = type(me, "ANNUAL");
        mvc.perform(get("/api/leave/mine").session(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balances[0].available").value(20.0));

        String week = "{\"leaveTypeId\":%d,\"startDate\":\"2026-12-07\",\"endDate\":\"2026-12-11\",\"reason\":\"Family visit\"}"
                .formatted(annual);
        String created = mvc.perform(json(post("/api/leave/requests").session(me), week))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workingDays").value(5.0))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(created, "$.id");
        mvc.perform(json(post("/api/leave/requests").session(me), week)).andExpect(status().isConflict());
        mvc.perform(json(post("/api/leave/requests").session(me),
                        "{\"leaveTypeId\":%d,\"startDate\":\"2027-01-04\",\"endDate\":\"2027-02-12\"}".formatted(annual)))
                .andExpect(status().is(422));   // 30 working days, only 15 left after the pending 5

        mvc.perform(json(post("/api/leave/requests/" + id + "/decision").session(login(EMPLOYEE)), "{\"approve\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/leave/requests/" + id + "/decision").session(login(MANAGER)), "{\"approve\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(get("/api/leave/mine").session(me))
                .andExpect(jsonPath("$.balances[0].available").value(15.0));
        mvc.perform(get("/api/attendance/month").param("month", "2026-12").session(me))
                .andExpect(jsonPath("$.days[6].status").value("LEAVE"))
                .andExpect(jsonPath("$.days[6].leaveType").value("Congé annuel"))
                .andExpect(jsonPath("$.days[6].payableMinutes").value(480));

        MockHttpSession hr = login(HR);
        mvc.perform(json(post("/api/leave/requests/" + id + "/cancel").session(hr), "{}"))
                .andExpect(status().is(422));
        mvc.perform(json(post("/api/leave/requests/" + id + "/cancel").session(hr), "{\"note\":\"Project deadline moved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/api/leave/mine").session(me))
                .andExpect(jsonPath("$.balances[0].available").value(20.0));
    }

    @Test
    void limitsAndYearlyGrantsAreEnforced() throws Exception {
        MockHttpSession me = login(EMPLOYEE2);
        mvc.perform(json(post("/api/leave/requests").session(me),
                        "{\"leaveTypeId\":%d,\"startDate\":\"2027-01-04\",\"endDate\":\"2027-05-14\"}".formatted(type(me, "MATERNITY"))))
                .andExpect(status().is(422));
        mvc.perform(json(post("/api/leave/requests").session(me),
                        "{\"leaveTypeId\":%d,\"startDate\":\"2026-12-12\",\"endDate\":\"2026-12-13\"}".formatted(type(me, "UNPAID"))))
                .andExpect(status().is(422));   // a weekend has no working days

        MockHttpSession hr = login(HR);
        mvc.perform(post("/api/leave/grant-annual").param("year", "2026").session(hr).with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(0));
        mvc.perform(post("/api/leave/grant-annual").param("year", "2026").session(me).with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void familyEventLeaveIsCountedInWorkingDaysPerEventAndAtMost15DaysAYear() throws Exception {
        MockHttpSession me = login(EMPLOYEE2);
        long death = type(me, "FAMILY_DEATH_CLOSE");
        String body = "{\"leaveTypeId\":%d,\"startDate\":\"%s\",\"endDate\":\"%s\"}";
        try {
            // Code du travail art. 146: 4 working days for the death of the spouse or a first-degree relative.
            mvc.perform(json(post("/api/leave/requests").session(me), body.formatted(death, "2027-02-01", "2027-02-05")))
                    .andExpect(status().is(422));
            // Thursday to the next Tuesday, over the weekend: 4 working days.
            mvc.perform(json(post("/api/leave/requests").session(me), body.formatted(death, "2027-02-04", "2027-02-09")))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.workingDays").value(4.0));
            mvc.perform(json(post("/api/leave/requests").session(me), body.formatted(death, "2027-03-01", "2027-03-04")))
                    .andExpect(status().isCreated());
            mvc.perform(json(post("/api/leave/requests").session(me), body.formatted(death, "2027-04-12", "2027-04-15")))
                    .andExpect(status().isCreated());
            long marriage = type(me, "FAMILY_MARRIAGE");
            mvc.perform(json(post("/api/leave/requests").session(me), body.formatted(marriage, "2027-06-07", "2027-06-08")))
                    .andExpect(status().isCreated());
            // 14 days so far in 2027: two more would pass the yearly 15.
            mvc.perform(json(post("/api/leave/requests").session(me), body.formatted(type(me, "FAMILY_BIRTH"), "2027-07-05", "2027-07-06")))
                    .andExpect(status().is(422))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("15 working days a year")));
            mvc.perform(get("/api/leave/types").session(me))
                    .andExpect(jsonPath("$[?(@.code=='CIRCUMSTANCE')].active").value(false));
        } finally {
            jdbc.sql("""
                    delete from leave_request where leave_type_id in (select id from leave_type where circumstance)
                    and user_id = (select id from app_user where email = ?)""").param(EMPLOYEE2).update();
        }
    }
}
