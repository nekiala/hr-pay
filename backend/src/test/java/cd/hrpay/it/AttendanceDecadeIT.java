package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.user.UserRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** HR closes an attendance decade; its days are then locked until it is reopened with a reason. */
class AttendanceDecadeIT extends IntegrationTest {

    @Autowired
    UserRepository users;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aClosedDecadeLocksItsDaysUntilReopened() throws Exception {
        MockHttpSession hr = login(HR);
        long employee = users.findByEmailIgnoreCase(EMPLOYEE).orElseThrow().getId();
        String entry = """
                {"type":"IN","at":"2025-01-06T07:55:00","workDate":"2025-01-06","note":"Badge reader down"}""";

        // Employees cannot see the decades; a decade starts on the 1st, 11th or 21st.
        mvc.perform(get("/api/attendance/decades?month=2025-01").session(login(EMPLOYEE))).andExpect(status().isForbidden());
        mvc.perform(get("/api/attendance/decades?month=2025-01").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].from").value("2025-01-21"))
                .andExpect(jsonPath("$[2].to").value("2025-01-31"))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].ended").value(true));
        mvc.perform(post("/api/attendance/decades/2025-01-05/close").session(hr).with(csrf()))
                .andExpect(status().is(422));
        mvc.perform(post("/api/attendance/decades/2099-01-01/close").session(hr).with(csrf()))
                .andExpect(status().is(422));

        // A correction still waiting blocks the closing.
        long waiting = jdbc.sql("""
                insert into correction_request (user_id, work_date, action, event_type, local_time, reason, requested_by)
                values (?, ?, 'ADD', 'IN', '07:58', 'test', 'test') returning id""")
                .params(employee, LocalDate.of(2025, 1, 7)).query(Long.class).single();
        mvc.perform(get("/api/attendance/decades/2025-01-01").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.decade.correctionsPending").value(1));
        mvc.perform(post("/api/attendance/decades/2025-01-01/close").session(hr).with(csrf()))
                .andExpect(status().is(422));
        jdbc.sql("update correction_request set status = 'CANCELLED' where id = ?").param(waiting).update();

        mvc.perform(post("/api/attendance/decades/2025-01-01/close").session(hr).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedBy").value(HR));
        // Its days are locked.
        mvc.perform(json(post("/api/attendance/users/" + employee + "/events").session(hr), entry))
                .andExpect(status().is(422));
        mvc.perform(get("/api/attendance/decades/2025-01-01/sheet").session(hr)).andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf"));
        mvc.perform(get("/api/attendance/decades/2025-01-01/sheet?format=xlsx").session(hr)).andExpect(status().isOk());

        // Reopening takes a reason; then the day can be corrected again.
        mvc.perform(json(post("/api/attendance/decades/2025-01-01/reopen").session(hr), "{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/attendance/decades/2025-01-01/reopen").session(hr), "{\"reason\":\"Late badge export\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.reopenReason").value("Late badge export"));
        mvc.perform(json(post("/api/attendance/users/" + employee + "/events").session(hr), entry))
                .andExpect(status().isCreated());
        jdbc.sql("delete from clock_event where user_id = ? and work_date = ?").params(employee, LocalDate.of(2025, 1, 6)).update();
        jdbc.sql("delete from attendance_decade").update();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor csrf() {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf();
    }
}
