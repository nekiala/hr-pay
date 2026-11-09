package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

class OrgStructureIT extends IntegrationTest {

    @Autowired
    UserRepository users;

    long id(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow().getId();
    }

    long levelId(MockHttpSession s, String code) throws Exception {
        String levels = mvc.perform(get("/api/org/levels").session(s)).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Number>>read(levels, "$[?(@.code=='" + code + "')].id").getFirst().longValue();
    }

    long unitId(MockHttpSession s, String code) throws Exception {
        String units = mvc.perform(get("/api/org/units").session(s)).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Number>>read(units, "$[?(@.code=='" + code + "')].id").getFirst().longValue();
    }

    @Test
    void sampleStructureGivesEveryoneAUnitAndAManager() throws Exception {
        MockHttpSession hr = login(HR);
        String people = mvc.perform(get("/api/org/people").session(hr)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // The Service logistique is vacant, so its people report to the head of the Direction des opérations.
        assertThat(JsonPath.<List<String>>read(people, "$[?(@.userId==" + id(EMPLOYEE) + ")].reportsToName"))
                .containsExactly("TSHIBANGU Sarah");
        // Sarah heads the Direction; the Direction générale above is vacant, so she reports to nobody.
        assertThat(JsonPath.<List<Object>>read(people, "$[?(@.userId==" + id("manager@demo.hrpay.cd") + ")].reportsToId"))
                .containsExactly((Object) null);
        mvc.perform(get("/api/org/units").session(hr))
                .andExpect(jsonPath("$[?(@.code=='DOP')].totalMembers").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.greaterThanOrEqualTo(4))))
                .andExpect(jsonPath("$[?(@.code=='SLOG')].head").value(org.hamcrest.Matchers.contains((Object) null)));
        // Everyone sees the chart, without headcounts; the people list needs user.read.
        MockHttpSession employee = login(EMPLOYEE);
        mvc.perform(get("/api/org/units").session(employee)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].members").doesNotExist());
        mvc.perform(get("/api/org/people").session(employee)).andExpect(status().isForbidden());
        mvc.perform(get("/api/org/me").session(employee))
                .andExpect(jsonPath("$.history[0].unitCode").value("SLOG"))
                .andExpect(jsonPath("$.manager.userName").value("TSHIBANGU Sarah"));
    }

    @Test
    void unitsHeadsAndMoves() throws Exception {
        MockHttpSession hr = login(HR);
        long dop = unitId(hr, "DOP");
        long service = levelId(hr, "SERVICE");
        long direction = levelId(hr, "DIRECTION");
        long equipe = levelId(hr, "EQUIPE");

        // A Direction cannot sit under a Service-level parent; an Équipe can sit under a Direction (levels skipped).
        String created = mvc.perform(json(post("/api/org/units").session(hr), """
                        {"code":"SMAINT","name":"Service maintenance","levelId":%d,"parentId":%d,"validFrom":"2026-01-01"}"""
                        .formatted(service, dop)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long smaint = JsonPath.<Number>read(created, "$.id").longValue();
        mvc.perform(json(post("/api/org/units").session(hr), """
                        {"code":"DBAD","name":"Wrong","levelId":%d,"parentId":%d}""".formatted(direction, smaint)))
                .andExpect(status().is(422));
        mvc.perform(json(post("/api/org/units").session(hr), """
                        {"code":"EQNUIT","name":"Équipe de nuit","levelId":%d,"parentId":%d,"validFrom":"2026-01-01"}""".formatted(equipe, dop)))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/org/units").session(hr), """
                        {"code":"SMAINT","name":"Duplicate","levelId":%d,"parentId":%d}""".formatted(service, dop)))
                .andExpect(status().isConflict());
        // Moving the Direction under its own Service would be a cycle.
        mvc.perform(json(put("/api/org/units/" + dop).session(hr), """
                        {"code":"DOP","name":"Direction des opérations","levelId":%d,"parentId":%d}""".formatted(direction, smaint)))
                .andExpect(status().is(422));

        // David moves to maintenance; a later move cannot start before that, except as a correction.
        long david = id("employee3@demo.hrpay.cd");
        long sarah = id("manager@demo.hrpay.cd");
        mvc.perform(json(post("/api/org/users/" + david + "/assignments").session(hr), """
                        {"unitId":%d,"from":"2026-03-01","reason":"TRANSFER"}""".formatted(smaint)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].unitCode").value("SMAINT"))
                .andExpect(jsonPath("$[1].validTo").value("2026-02-28"));
        long slog = unitId(hr, "SLOG");
        mvc.perform(json(post("/api/org/users/" + david + "/assignments").session(hr), """
                        {"unitId":%d,"from":"2026-02-01","reason":"TRANSFER"}""".formatted(slog)))
                .andExpect(status().is(422));

        // Sarah, head of the Direction, also heads the Service ad interim; she cannot be its deputy too.
        mvc.perform(json(put("/api/org/units/" + smaint + "/head").session(hr), """
                        {"userId":%d,"role":"HEAD","interim":true,"from":"2026-03-01"}""".formatted(sarah)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.head.interim").value(true));
        mvc.perform(json(put("/api/org/units/" + smaint + "/head").session(hr), """
                        {"userId":%d,"role":"DEPUTY"}""".formatted(sarah)))
                .andExpect(status().is(422));
        mvc.perform(json(put("/api/org/units/" + smaint + "/head").session(hr), """
                        {"userId":%d,"role":"DEPUTY","from":"2026-03-01"}""".formatted(id(EMPLOYEE))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deputy.userName").value("MUKENDI Jean"));
        mvc.perform(get("/api/org/users/" + sarah + "/assignments").session(hr))
                .andExpect(jsonPath("$.heads.length()").value(2));
        String people = mvc.perform(get("/api/org/people").session(hr)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(people, "$[?(@.userId==" + david + ")].reportsToName")).containsExactly("TSHIBANGU Sarah");

        // A unit with people cannot close; once empty, it can, and its heads' terms end with it.
        mvc.perform(json(post("/api/org/units/" + smaint + "/close").session(hr), "{\"on\":\"2026-06-30\"}"))
                .andExpect(status().is(422));
        mvc.perform(json(post("/api/org/users/" + david + "/assignments").session(hr), """
                        {"unitId":%d,"from":"2026-03-01","reason":"CORRECTION","note":"Never moved"}""".formatted(slog)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].unitCode").value("SLOG"));
        mvc.perform(json(post("/api/org/units/" + smaint + "/close").session(hr), "{\"on\":\"2026-06-30\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.open").value(false)).andExpect(jsonPath("$.head").doesNotExist());

        // Only HR edits the structure.
        mvc.perform(json(post("/api/org/units").session(login(FINANCE)), """
                        {"code":"X1","name":"X","levelId":%d,"parentId":%d}""".formatted(service, dop)))
                .andExpect(status().isForbidden());
    }

    @Test
    void csvRoundTripAndNewUsersNeedAUnit() throws Exception {
        MockHttpSession hr = login(HR);
        String csv = mvc.perform(get("/api/org/units.csv").session(hr)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("code,name,level,parent,cost_centre,head_registration_no").contains("DOP,Direction des opérations,DIRECTION,DG,,MGR-0001");

        String bad = "code,name,level,parent\\nSQUAL,Service qualité,NOPE,DOP\\n";
        mvc.perform(json(post("/api/org/units/import").session(hr), "{\"csv\":\"" + bad + "\"}")).andExpect(status().is(422));
        String good = "code;name;level;parent;cost_centre;head_registration_no\\nSQUAL;Service qualité;SERVICE;DOP;CC-40;EMP-0001\\nEQUAL;Équipe audits;EQUIPE;SQUAL;;\\nDAF;Direction administrative et financière;DIRECTION;DG;CC-20;FIN-0001\\n";
        mvc.perform(json(post("/api/org/units/import").session(hr), "{\"csv\":\"" + good + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.headsSet").value(1));
        mvc.perform(get("/api/org/units").session(hr))
                .andExpect(jsonPath("$[?(@.code=='SQUAL')].head.userName").value(org.hamcrest.Matchers.contains("MUKENDI Jean")))
                .andExpect(jsonPath("$[?(@.code=='DAF')].costCentre").value(org.hamcrest.Matchers.contains("CC-20")));

        String noUnit = newUser(null).replace("\"orgUnitId\":1,", "");
        mvc.perform(json(post("/api/users").session(login(ADMIN)), noUnit)).andExpect(status().isBadRequest());
        long squal = unitId(hr, "SQUAL");
        String body = newUser(null).replace("\"orgUnitId\":1", "\"orgUnitId\":" + squal);
        String created = mvc.perform(json(post("/api/users").session(login(ADMIN)), body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long newId = JsonPath.<Number>read(created, "$.id").longValue();
        mvc.perform(get("/api/org/users/" + newId + "/assignments").session(hr))
                .andExpect(jsonPath("$.history[0].unitCode").value("SQUAL"))
                .andExpect(jsonPath("$.history[0].reason").value("HIRE"))
                .andExpect(jsonPath("$.manager.userName").value("MUKENDI Jean"));
        mvc.perform(post("/api/users/" + newId + "/disable").session(login(ADMIN)).with(csrf())).andExpect(status().isOk());
    }
}
