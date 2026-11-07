package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** Annual leave pay: the company setting, what an employee has earned and keeps, closing a year, and the leave allowance. */
class LeavePayIT extends IntegrationTest {

    static final String MANAGER = "manager@demo.hrpay.cd";

    @Autowired
    JdbcClient jdbc;

    long annual(MockHttpSession s) throws Exception {
        String types = mvc.perform(get("/api/leave/types").session(s)).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Number>>read(types, "$[?(@.code=='ANNUAL')].id").getFirst().longValue();
    }

    long approvedLeave(MockHttpSession me, String from, String to) throws Exception {
        String created = mvc.perform(json(post("/api/leave/requests").session(me),
                        "{\"leaveTypeId\":%d,\"startDate\":\"%s\",\"endDate\":\"%s\"}".formatted(annual(me), from, to)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = JsonPath.<Number>read(created, "$.id").longValue();
        mvc.perform(json(post("/api/leave/requests/" + id + "/decision").session(login(MANAGER)), "{\"approve\":true}"))
                .andExpect(status().isOk());
        return id;
    }

    @Test
    void leaveAllowanceIsWorkedOutApprovedPaidAndTakenBackOnThePayslip() throws Exception {
        MockHttpSession me = login(EMPLOYEE);
        MockHttpSession hr = login(HR);
        MockHttpSession finance = login(FINANCE);
        mvc.perform(get("/api/leave/mine").session(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annual.paid").value(true))
                .andExpect(jsonPath("$.annual.grantedThisYear").value(20.0))
                .andExpect(jsonPath("$.annual.carryPercent").value(50));

        // A week across two months: 3 working days in August, 2 in September.
        long leave = approvedLeave(me, "2027-08-30", "2027-09-03");
        String overview = mvc.perform(get("/api/leave-pay").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.annualPaid").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Number>>read(overview, "$.due[*].leaveRequestId")).extracting(Number::longValue).contains(leave);
        assertThat(JsonPath.<List<String>>read(overview, "$.due[?(@.leaveRequestId==" + leave + ")].payBy")).containsExactly("2027-08-27");

        mvc.perform(json(post("/api/leave-pay/calculate").session(me), "{}")).andExpect(status().isForbidden());
        String run = mvc.perform(json(post("/api/leave-pay/calculate").session(hr), "{\"leaveRequestIds\":[%d]}".formatted(leave)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculated.length()").value(1))
                .andExpect(jsonPath("$.calculated[0].status").value("CALCULATED"))
                .andExpect(jsonPath("$.calculated[0].days").value(5.0))
                .andExpect(jsonPath("$.calculated[0].months.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        long id = JsonPath.<Number>read(run, "$.calculated[0].id").longValue();
        BigDecimal salaryPart = new BigDecimal(JsonPath.read(run, "$.calculated[0].salaryPart").toString());
        BigDecimal gross = new BigDecimal(JsonPath.read(run, "$.calculated[0].gross").toString());
        BigDecimal advance = new BigDecimal(JsonPath.read(run, "$.calculated[0].advance").toString());
        assertThat(salaryPart).isPositive();
        assertThat(gross).isGreaterThanOrEqualTo(salaryPart);
        assertThat(advance).isPositive().isLessThanOrEqualTo(gross);
        // Not due any more once worked out.
        String after = mvc.perform(get("/api/leave-pay").session(hr)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Number>>read(after, "$.due[*].leaveRequestId")).extracting(Number::longValue).doesNotContain(leave);

        // HR worked it out: Finance approves, and the payslips of August and September take the advance back.
        mvc.perform(post("/api/leave-pay/" + id + "/approve").session(hr).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/leave-pay/" + id + "/approve").session(finance).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        BigDecimal takenBack = jdbc.sql("""
                select coalesce(sum(p.amount), 0) from pay_entry p join pay_element e on e.id = p.element_id join app_user u on u.id = p.user_id
                where e.code = 'LEAVE_ADVANCE' and u.email = ? and p.month in ('2027-08-01', '2027-09-01')""")
                .param(EMPLOYEE).query(BigDecimal.class).single();
        assertThat(takenBack).isEqualByComparingTo(advance);
        assertThat(jdbc.sql("""
                select count(*) from pay_entry p join pay_element e on e.id = p.element_id join app_user u on u.id = p.user_id
                where e.code = 'LEAVE_ADVANCE' and u.email = ?""").param(EMPLOYEE).query(Integer.class).single()).isEqualTo(2);

        mvc.perform(json(post("/api/leave-pay/" + id + "/paid").session(finance), "{\"paidOn\":\"2027-08-25\",\"reference\":\"MP-1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        mvc.perform(json(post("/api/leave-pay/" + id + "/cancel").session(hr), "{}")).andExpect(status().is(422));
        mvc.perform(get("/api/leave-pay/mine").session(me))
                .andExpect(jsonPath("$[0].status").value("PAID"))
                .andExpect(jsonPath("$[0].paidOn").value("2027-08-25"));

        // The leave is cancelled after payment: the advance stays taken back, the person works and is paid those days.
        mvc.perform(json(post("/api/leave/requests/" + leave + "/cancel").session(hr), "{\"note\":\"Called back\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/leave-pay/mine").session(me))
                .andExpect(jsonPath("$[0].status").value("PAID"))
                .andExpect(jsonPath("$[0].variablePart").value(0));
        assertThat(jdbc.sql("""
                select coalesce(sum(p.amount), 0) from pay_entry p join pay_element e on e.id = p.element_id join app_user u on u.id = p.user_id
                where e.code = 'LEAVE_ADVANCE' and u.email = ?""").param(EMPLOYEE).query(BigDecimal.class).single())
                .isEqualByComparingTo(advance);
    }

    @Test
    void unpaidAnnualLeaveAndClosingAYear() throws Exception {
        MockHttpSession hr = login(HR);
        MockHttpSession me = login("employee2@demo.hrpay.cd");
        long leave = approvedLeave(me, "2027-05-03", "2027-05-04");

        // Closing 2026 in the DRC: half of the year's leave may be carried, the rest lapses (shown first, not applied).
        String preview = mvc.perform(get("/api/leave/close-year").param("year", "2026").session(hr))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Number>>read(preview, "$[?(@.registrationNo=='EMP-0002')].lapsing"))
                .extracting(n -> new BigDecimal(n.toString())).containsExactly(new BigDecimal("10.0"));
        mvc.perform(post("/api/leave/close-year").param("year", "2027").session(hr).with(csrf())).andExpect(status().is(422));

        String settings = "{\"seniorityYears\":5,\"seniorityDays\":1,\"annualPaid\":%s,\"carryPercent\":50,\"carryYears\":1}";
        mvc.perform(json(put("/api/leave/settings").session(hr), settings.formatted(false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.LEAVE_ANNUAL_PAID").value("false"));
        try {
            mvc.perform(get("/api/leave/mine").session(me)).andExpect(jsonPath("$.annual.paid").value(false));
            mvc.perform(get("/api/attendance/month").param("month", "2027-05").session(me))
                    .andExpect(jsonPath("$.days[2].status").value("LEAVE"))
                    .andExpect(jsonPath("$.days[2].payableMinutes").value(0));
            String overview = mvc.perform(get("/api/leave-pay").session(hr)).andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<Object>>read(overview, "$.due")).isEmpty();
            mvc.perform(json(post("/api/leave-pay/calculate").session(hr), "{}")).andExpect(status().is(422));
        } finally {
            mvc.perform(json(put("/api/leave/settings").session(hr), settings.formatted(true))).andExpect(status().isOk());
            mvc.perform(json(post("/api/leave/requests/" + leave + "/cancel").session(hr), "{\"note\":\"Test over\"}"))
                    .andExpect(status().isOk());
        }
    }
}
