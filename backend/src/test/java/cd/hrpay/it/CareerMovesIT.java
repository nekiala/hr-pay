package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.Base64;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

/** Promotions and transfers, and the imports that follow the employee file. */
class CareerMovesIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    long grade(MockHttpSession s, String code) throws Exception {
        String grades = mvc.perform(get("/api/grades").session(s)).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Number>>read(grades, "$[?(@.code=='" + code + "')].id").getFirst().longValue();
    }

    long freshEmployee() throws Exception {
        String created = mvc.perform(json(post("/api/users").session(login(ADMIN)), newUser("[\"EMPLOYEE\"]")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(created, "$.id").longValue();
    }

    private static String file(String name, String csv) {
        return "{\"fileName\":\"%s\",\"content\":\"%s\"}".formatted(name,
                Base64.getEncoder().encodeToString(csv.getBytes(StandardCharsets.UTF_8)));
    }

    long unit(String code) {
        return jdbc.sql("select id from org_unit where code = ?").param(code).query(Long.class).single();
    }

    @Test
    void aPromotionWithTransferMakesTheAvenantTheMoveAndTheLetter() throws Exception {
        MockHttpSession hr = login(HR);
        long id = freshEmployee();
        String base = "/api/users/" + id;
        mvc.perform(json(post(base + "/contracts").session(hr), """
                        {"contractType":"PERMANENT","startDate":"%s","gradeId":%d,"expatriate":false}"""
                        .formatted(YearMonth.now().minusMonths(1).atDay(1), grade(hr, "E4A"))))
                .andExpect(status().isCreated());
        String from = YearMonth.now().plusMonths(1).atDay(1).toString();
        long accountant = jdbc.sql("select id from occupation where code = 'ACCOUNTANT'").query(Long.class).single();

        // A promotion keeps the unit; the trial is six months at most; nothing changing is refused.
        mvc.perform(json(post(base + "/career").session(hr), """
                        {"kind":"PROMOTION","effectiveOn":"%s","gradeId":%d,"unitId":%d}""".formatted(from, grade(hr, "M1"), unit("DAF"))))
                .andExpect(status().is(422));
        mvc.perform(json(post(base + "/career").session(hr), """
                        {"kind":"PROMOTION","effectiveOn":"%s","gradeId":%d,"trialMonths":7}""".formatted(from, grade(hr, "M1"))))
                .andExpect(status().is(400));
        mvc.perform(json(post(base + "/career").session(hr), """
                        {"kind":"TRANSFER","effectiveOn":"%s"}""".formatted(from)))
                .andExpect(status().is(422));

        String saved = mvc.perform(json(post(base + "/career").session(hr), """
                        {"kind":"PROMOTION_TRANSFER","effectiveOn":"%s","gradeId":%d,"occupationId":%d,"unitId":%d,
                         "trialMonths":3,"keepNet":false,"note":"Chef comptable"}""".formatted(from, grade(hr, "M1"), accountant, unit("DAF"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.move.kind").value("PROMOTION_TRANSFER"))
                .andExpect(jsonPath("$.move.toGrade").value(Matchers.startsWith("M1")))
                .andExpect(jsonPath("$.move.fromGrade").value(Matchers.startsWith("E4A")))
                .andExpect(jsonPath("$.move.toUnit").value("Direction administrative et financière"))
                .andExpect(jsonPath("$.move.letterStatus").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        long letter = JsonPath.<Number>read(saved, "$.move.letterId").longValue();

        // The avenant: a new contract record from that date, with the grade and the occupation.
        mvc.perform(get(base + "/file").session(hr))
                .andExpect(jsonPath("$.contracts.length()").value(2))
                .andExpect(jsonPath("$.contracts[0].gradeCode").value("M1"))
                .andExpect(jsonPath("$.contracts[0].startDate").value(from))
                .andExpect(jsonPath("$.contracts[0].occupationName").value(Matchers.notNullValue()));
        // The structure: moved from that date, as a promotion.
        assertThat(jdbc.sql("select reason from org_assignment where user_id = ? and valid_to is null").param(id)
                .query(String.class).single()).isEqualTo("PROMOTION");
        // The document to sign names what changes and the trial period.
        String body = jdbc.sql("select body from employee_letter where id = ?").param(letter).query(String.class).single();
        assertThat(body).contains("M1").contains("Direction administrative et financière").contains("3").doesNotContain("{{career.");
        mvc.perform(get(base + "/career").session(hr))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].trialMonths").value(3));
        mvc.perform(get(base + "/career").session(login(EMPLOYEE))).andExpect(status().isForbidden());
    }

    @Test
    void importsFollowTheEmployeeFile() throws Exception {
        MockHttpSession hr = login(HR);
        String n = String.valueOf(System.nanoTime() % 1_000_000);
        mvc.perform(json(post("/api/imports/employees").session(hr), file("e.csv", """
                        Matricule;Prénom;Nom;Email;Téléphone;Code de l'unité;Pays de naissance;Lieu de naissance;Quartier;Avenue et numéro;Pièce d'identité;Numéro de la pièce;Délivrée le
                        CAR-%1$s;Mado;Lukusa;mado.%1$s@import.cd;+243 81 100 %1$s;SLOG;CD;Somewhere far;Matonge;Av. Kasa-Vubu 12;Passeport;OP1234567;15/06/2020
                        """.formatted(n))))
                .andExpect(jsonPath("$.saved").value(true));
        long id = jdbc.sql("select id from app_user where registration_no = ?").param("CAR-" + n).query(Long.class).single();
        mvc.perform(get("/api/users/" + id + "/file").session(hr))
                .andExpect(jsonPath("$.profile.addressQuarter").value("Matonge"))
                .andExpect(jsonPath("$.profile.addressStreet").value("Av. Kasa-Vubu 12"))
                .andExpect(jsonPath("$.profile.idDocumentType").value("PASSPORT"))
                .andExpect(jsonPath("$.profile.idDocumentNumber").value("OP1234567"))
                .andExpect(jsonPath("$.profile.idDocumentIssuedOn").value("2020-06-15"))
                .andExpect(jsonPath("$.profile.birthPlaceText").value("Somewhere far"));

        // Another unit for someone already here is a transfer from the date given.
        String later = YearMonth.now().plusMonths(1).atDay(1).toString();
        mvc.perform(json(post("/api/imports/employees").session(hr), file("e.csv", """
                        Matricule;Prénom;Nom;Email;Téléphone;Code de l'unité;Date du changement
                        CAR-%1$s;Mado;Lukusa;mado.%1$s@import.cd;+243 81 100 %1$s;DAF;%2$s
                        """.formatted(n, later))))
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.results[0].warnings[0]").value(Matchers.containsString("ransfer")));
        assertThat(jdbc.sql("select valid_from::text from org_assignment where user_id = ? and valid_to is null").param(id)
                .query(String.class).single()).isEqualTo(later);
        assertThat(jdbc.sql("select count(*) from career_move where user_id = ? and kind = 'TRANSFER'").param(id)
                .query(Integer.class).single()).isEqualTo(1);

        // Family: added, then the same name updates.
        String family = """
                registration_no,full_name,relationship,birth_date,student
                CAR-%1$s,Kito Lukusa,enfant,2015-04-02,non
                """.formatted(n);
        mvc.perform(json(post("/api/imports/family").session(hr), file("f.csv", family)))
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.results[0].action").value("ADD"));
        mvc.perform(json(post("/api/imports/family").session(hr), file("f.csv", family.replace("non", "oui"))))
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.results[0].action").value("UPDATE"));
        assertThat(jdbc.sql("select count(*) from dependant where user_id = ?").param(id).query(Integer.class).single()).isEqualTo(1);

        // Moves: a promotion from a file.
        mvc.perform(json(post("/api/imports/contracts").session(hr), file("c.csv", """
                        registration_no,contract_type,start_date,grade,trial_months,signed_on
                        CAR-%1$s,CDI,%2$s,E4A,2,%2$s
                        """.formatted(n, YearMonth.now().minusMonths(1).atDay(1)))))
                .andExpect(jsonPath("$.saved").value(true));
        assertThat(jdbc.sql("select t.trial_months from contract_terms t join employee_contract c on c.id = t.contract_id where c.user_id = ?")
                .param(id).query(Integer.class).single()).isEqualTo(2);
        mvc.perform(json(post("/api/imports/moves").session(hr), file("m.csv", """
                        registration_no,effective_on,kind,grade
                        CAR-%1$s,%2$s,promotion,M1
                        """.formatted(n, YearMonth.now().plusMonths(2).atDay(1)))))
                .andExpect(jsonPath("$.saved").value(true));
        mvc.perform(get("/api/users/" + id + "/career").session(hr))
                .andExpect(jsonPath("$[0].kind").value("PROMOTION"));

        // The employees' template can come filled, ready to import back unchanged.
        String filled = mvc.perform(get("/api/imports/employees/template.csv").param("filled", "true").session(hr))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(filled).contains("CAR-" + n).contains("Matonge").contains("PASSPORT");
        mvc.perform(get("/api/imports/contracts/template.csv").param("filled", "true").session(hr)).andExpect(status().is(422));
    }
}
