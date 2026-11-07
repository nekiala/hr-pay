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

import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

/** Company logo and payslip settings, the employee details on the payslip, and the three layouts. */
class PayslipBrandingIT extends IntegrationTest {

    @Autowired
    UserRepository users;

    @Autowired
    cd.hrpay.payroll.ExchangeRates rates;

    static String png() throws Exception {
        BufferedImage img = new BufferedImage(120, 40, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    static void assertPdf(byte[] pdf) {
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void logoAndPayslipSettings() throws Exception {
        MockHttpSession hr = login(HR);
        mvc.perform(json(put("/api/company/payslip-settings").session(login(EMPLOYEE)), "{\"template\":\"LEDGER\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(json(put("/api/company/payslip-settings").session(hr), "{\"template\":\"FANCY\"}"))
                .andExpect(status().is4xxClientError());
        mvc.perform(json(put("/api/company/payslip-settings").session(hr), """
                {"phone":"+243 81 000 0000","email":"paie@demo.cd","website":"demo.cd","legalForm":"SARL",
                 "footer":"Questions: HR office","template":"COMPACT"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template").value("COMPACT"))
                .andExpect(jsonPath("$.hasLogo").value(false));

        // A file that is not a picture is refused; a PNG is kept and served.
        mvc.perform(json(put("/api/company/logo").session(hr), "{\"contentType\":\"image/png\",\"base64\":\""
                + Base64.getEncoder().encodeToString("not a picture".getBytes(StandardCharsets.UTF_8)) + "\"}"))
                .andExpect(status().is(422));
        mvc.perform(json(put("/api/company/logo").session(hr), "{\"contentType\":\"image/gif\",\"base64\":\"" + png() + "\"}"))
                .andExpect(status().is(422));
        mvc.perform(json(put("/api/company/logo").session(hr), "{\"contentType\":\"image/png\",\"base64\":\"" + png() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasLogo").value(true));
        mvc.perform(get("/api/company/logo").session(login(EMPLOYEE)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"));

        // Each layout renders as a sample with the logo, for HR only.
        for (String t : List.of("CLASSIC", "COMPACT", "LEDGER")) {
            byte[] pdf = mvc.perform(get("/api/company/payslip-preview.pdf").param("template", t).session(hr))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertPdf(pdf);
        }
        mvc.perform(get("/api/company/payslip-preview.pdf").param("template", "CLASSIC").session(login(EMPLOYEE)))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/company/logo").session(hr).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasLogo").value(false));
        mvc.perform(get("/api/company/logo").session(hr)).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/company/payslip-settings").session(hr), "{\"template\":\"CLASSIC\"}")).andExpect(status().isOk());
    }

    @Test
    void payslipShowsSeniorityMaritalCodeAndRendersInEachLayout() throws Exception {
        rates.store("""
                {"data":[{"currency_code":"USD","rate_date":"2026-07-31","indicative_rate":"2100"}]}""");
        MockHttpSession hr = login(HR);
        long jean = users.findByEmailIgnoreCase(EMPLOYEE).orElseThrow().getId();
        String run = mvc.perform(post("/api/payroll/runs/2026-07/calculate").session(hr).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long slip = JsonPath.<List<Number>>read(run, "$.payslips[?(@.userId==" + jean + ")].id").getFirst().longValue();

        String body = mvc.perform(get("/api/payslips/" + slip).session(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.registrationNo").exists())
                .andExpect(jsonPath("$.snapshot.grade").exists())
                .andExpect(jsonPath("$.snapshot.childrenInCharge").exists())
                .andExpect(jsonPath("$.snapshot.seniority").exists())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.snapshot.maritalCode");
        assertThat(code).isIn("S", "M", "D", "W");

        for (String t : List.of("CLASSIC", "COMPACT", "LEDGER")) {
            byte[] pdf = mvc.perform(get("/api/payslips/" + slip + ".pdf").param("template", t).session(hr))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertPdf(pdf);
        }
        mvc.perform(get("/api/payslips/" + slip + ".pdf").param("template", "OTHER").session(hr)).andExpect(status().is(422));
    }
}
