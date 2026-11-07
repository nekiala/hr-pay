package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

class ReportIT extends IntegrationTest {

    @Test
    void hrExportsAttendanceAndFinanceExportsEarnings() throws Exception {
        String summary = mvc.perform(get("/api/reports/attendance.csv").param("month", "2026-10").session(login(HR)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"attendance-2026-10.csv\""))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(summary).startsWith("﻿Registration no,Name,Site");
        assertThat(summary).contains("EMP-0002,Esther Kalala");

        String daily = mvc.perform(get("/api/reports/attendance.csv").param("month", "2026-10").param("detail", "true")
                        .session(login(HR)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(daily).contains("EMP-0002,Esther Kalala,2026-10-01,");

        String finance = mvc.perform(get("/api/reports/finance.csv").param("month", "2026-10").session(login(FINANCE)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(finance).contains("EMP-0001,Jean Mukendi,USD,4500.00");

        mvc.perform(get("/api/reports/finance.csv").param("month", "2026-10").session(login(EMPLOYEE)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/attendance.csv").param("month", "2026-10").session(login(EMPLOYEE)))
                .andExpect(status().isForbidden());
    }

    @Test
    void cellsCannotBecomeFormulas() {
        assertThat(cd.hrpay.report.ReportController.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(cd.hrpay.report.ReportController.cell("-12.5")).isEqualTo("-12.5");
        assertThat(cd.hrpay.report.ReportController.cell("Kalala, Esther")).isEqualTo("\"Kalala, Esther\"");
    }
}
