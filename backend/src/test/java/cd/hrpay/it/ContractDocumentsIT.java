package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** Contracts written from the full CDD/CDI models, with the Labour Code checks (art. 41, 43, 46, 212). */
class ContractDocumentsIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    private long create(MockHttpSession s) throws Exception {
        String created = mvc.perform(json(post("/api/users").session(s), newUser(null))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(created, "$.id").longValue();
    }

    private long grade(MockHttpSession s, String code) throws Exception {
        String grades = mvc.perform(get("/api/grades").session(s)).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Number>>read(grades, "$[?(@.code=='" + code + "')].id").getFirst().longValue();
    }

    private long contract(MockHttpSession hr, long user, String start, String end) throws Exception {
        String c = mvc.perform(json(post("/api/users/" + user + "/contracts").session(hr), """
                        {"contractType":"FIXED_TERM","startDate":"%s","endDate":"%s","gradeId":%d,"occupationId":%d,"expatriate":false}"""
                        .formatted(start, end, grade(hr, "E4A"), jdbc.sql("select min(id) from occupation where company_id = 1")
                                .query(Long.class).single())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(c, "$.id").longValue();
    }

    private static String document(byte[] docx) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equals("word/document.xml")) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("No word/document.xml");
    }

    @Test
    void aFixedTermContractIsWrittenFromTheModelWithTheLegalChecks() throws Exception {
        MockHttpSession hr = login(HR);
        // The town documents are signed in is picked from the lists: a typed one, or a place that is not a town, is refused.
        long kinshasa = jdbc.sql("select id from place where country_code = 'CD' and kind = 'CITY' and name = 'Kinshasa' and company_id is null")
                .query(Long.class).single();
        long province = jdbc.sql("select parent_id from place where id = ?").param(kinshasa).query(Long.class).single();
        mvc.perform(get("/api/reference/cities?country=CD").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Kinshasa')].id").value(Matchers.contains((int) kinshasa)))
                .andExpect(jsonPath("$[?(@.name == 'Masisi')]", Matchers.empty()));
        mvc.perform(json(put("/api/company/representative").session(hr),
                        "{\"name\":\"KIALA NTONA\",\"title\":\"Gérant\",\"signingPlaceId\":%d}".formatted(province)))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(put("/api/company/representative").session(hr),
                        "{\"name\":\"KIALA NTONA\",\"title\":\"Gérant\",\"signingPlaceId\":%d}".formatted(kinshasa)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Gérant"))
                .andExpect(jsonPath("$.signingPlace").value("Kinshasa"))
                .andExpect(jsonPath("$.signingCountry").value("CD"));
        // The place of work is one of the company's sites.
        long site = jdbc.sql("""
                insert into site (code, name, time_zone, gps_required, company_id) values ('SILIKIN', 'Ngaliema (Silikin village)',
                    'Africa/Kinshasa', false, 1) on conflict do nothing returning id""").query(Long.class).optional()
                .orElseGet(() -> jdbc.sql("select id from site where code = 'SILIKIN' and company_id = 1").query(Long.class).single());
        jdbc.sql("update company set cnss_number = coalesce(cnss_number, '1001377700'), rccm = coalesce(rccm, 'CD/KNG/RCCM/17-B-00814'), "
                + "national_id = coalesce(national_id, '01-83-N21745E') where id = 1")
                .update();

        long user = create(hr);
        String base = "/api/users/" + user;
        mvc.perform(json(put(base + "/profile").session(hr), """
                        {"birthDate":"1989-04-14","gender":"MALE","nationality":"Congolaise","maritalStatus":"MARRIED",
                         "paymentMethod":"CASH","birthPlaceText":"Kinshasa"}"""))
                .andExpect(status().isOk());
        mvc.perform(json(post(base + "/dependants").session(hr), """
                        {"fullName":"MAMBU Grace","relationship":"SPOUSE","birthDate":"1992-02-01","inCharge":true}"""))
                .andExpect(status().is2xxSuccessful());
        long schedule = jdbc.sql("select min(id) from work_schedule where company_id = 1").query(Long.class).single();
        mvc.perform(json(post(base + "/schedules").session(hr), "{\"scheduleId\":%d,\"effectiveFrom\":\"2026-11-01\"}"
                .formatted(schedule))).andExpect(status().is2xxSuccessful());
        long first = contract(hr, user, "2026-12-01", "2027-11-30");
        String terms = "/api/lifecycle/people/" + user + "/contracts/" + first + "/terms";

        // Without a reason the CDD cannot go out (art. 40, 45); without the medical fitness either (art. 212).
        mvc.perform(get(terms).session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.issues[?(@.code == 'CDD_REASON')].level").value(Matchers.contains("BLOCK")))
                .andExpect(jsonPath("$.issues[?(@.code == 'MEDICAL')]", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.defaultSignedPlace").value("Kinshasa"));
        // The trial: six months at most, one month for an unskilled labourer (art. 43).
        mvc.perform(json(put(terms).session(hr), "{\"trialMonths\":7}")).andExpect(status().isBadRequest());
        mvc.perform(json(put(terms).session(hr), "{\"trialMonths\":3,\"unskilledLabourer\":true}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(put(terms).session(hr), """
                        {"trialMonths":6,"fixedTermReason":"TEMPORARY_NEED","workSiteId":999999}"""))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(put(terms).session(hr), """
                        {"trialMonths":6,"fixedTermReason":"TEMPORARY_NEED","workSiteId":%d,"workPlace":"typed, ignored",
                         "supervisor":"Responsable informatique","medicalFitOn":"2026-11-20","medicalDoctor":"Dr Lukusa",
                         "signedPlaceId":%d}""".formatted(site, kinshasa)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terms.workPlace").value("Ngaliema (Silikin village)"))
                .andExpect(jsonPath("$.terms.signedPlace").value("Kinshasa"))
                .andExpect(jsonPath("$.signedCountry").value("CD"))
                .andExpect(jsonPath("$.issues[?(@.level == 'BLOCK')]", Matchers.empty()));

        long template = jdbc.sql("select id from letter_template where code = 'CONTRACT_CDD_FULL' and company_id = 1")
                .query(Long.class).single();
        assertThat(jdbc.sql("select active from letter_template where code = 'CONTRACT_CDD' and company_id = 1")
                .query(Boolean.class).single()).isFalse();
        String preview = mvc.perform(json(post("/api/lifecycle/people/" + user + "/letters/preview").session(hr),
                        "{\"templateId\":%d,\"contractId\":%d}".formatted(template, first)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String body = JsonPath.read(preview, "$.body");
        assertThat(body).contains("douze (12) mois", "six (6) mois", "Ngaliema (Silikin village)", "le 1 décembre 2026");
        assertThat(JsonPath.<List<String>>read(preview, "$.missing")).doesNotContain("contract.medical", "contract.reason");

        String templateBody = jdbc.sql("select body from letter_template where id = ?").param(template).query(String.class).single();
        String created = mvc.perform(json(post("/api/lifecycle/people/" + user + "/letters").session(hr),
                        tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(java.util.Map.of(
                                "templateId", template, "contractId", first, "title", "Contrat CDD", "body", templateBody,
                                "requiresSignature", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contractId").value(first))
                .andReturn().getResponse().getContentAsString();
        long letter = JsonPath.<Number>read(created, "$.id").longValue();
        byte[] draft = mvc.perform(get("/api/lifecycle/letters/" + letter + "/file").session(hr)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(draft, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        byte[] word = mvc.perform(get("/api/lifecycle/letters/" + letter + "/word").session(hr)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(document(word)).contains("douze (12) mois", "KIALA NTONA", "MAMBU Grace", "Lu et approuvé");

        mvc.perform(post("/api/lifecycle/letters/" + letter + "/send").session(hr).with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").isNotEmpty())
                // The person signs two working days after the draft reached them (art. 46).
                .andExpect(jsonPath("$.signableFrom").isNotEmpty());

        // A second CDD renews the first; a third is refused unless it is one of the cases of arrêté 063/2011 (art. 41).
        contract(hr, user, "2027-12-01", "2028-11-30");
        long third = contract(hr, user, "2028-12-01", "2029-11-30");
        String thirdTerms = "/api/lifecycle/people/" + user + "/contracts/" + third + "/terms";
        mvc.perform(json(put(thirdTerms).session(hr), "{\"trialMonths\":0,\"fixedTermReason\":\"TEMPORARY_NEED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues[?(@.code == 'CDD_COUNT')].level").value(Matchers.contains("BLOCK")));
        mvc.perform(json(put(thirdTerms).session(hr), "{\"trialMonths\":0,\"fixedTermReason\":\"PROJECT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues[?(@.code == 'CDD_COUNT')]", Matchers.empty()));
    }
}
