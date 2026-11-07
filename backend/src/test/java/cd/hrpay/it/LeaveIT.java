package cd.hrpay.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class LeaveIT extends IntegrationTest {

    static final String EMPLOYEE2 = "employee2@demo.hrpay.cd";
    static final String MANAGER = "manager@demo.hrpay.cd";

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
}
