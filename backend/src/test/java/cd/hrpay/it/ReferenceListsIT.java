package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.reference.References;
import cd.hrpay.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

class ReferenceListsIT extends IntegrationTest {

    @Autowired
    UserRepository users;

    @Autowired
    References refs;

    @Autowired
    JdbcClient jdbc;

    long id(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow().getId();
    }

    long placeId(MockHttpSession s, String country, Long parent, String name) throws Exception {
        String body = mvc.perform(get("/api/reference/places").param("country", country)
                        .param("parent", parent == null ? "" : parent.toString()).session(s))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Number>>read(body, "$[?(@.name=='" + name + "')].id").getFirst().longValue();
    }

    long bankId(MockHttpSession s, String name) throws Exception {
        String body = mvc.perform(get("/api/reference/institutions").param("country", "CD").param("kind", "BANK").session(s))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Number>>read(body, "$[?(@.name=='" + name + "')].id").getFirst().longValue();
    }

    @Test
    void seededListsAreThere() throws Exception {
        MockHttpSession employee = login(EMPLOYEE);
        mvc.perform(get("/api/reference/countries").session(employee))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countries.length()").value(249))
                .andExpect(jsonPath("$.withPlaces[0]").value("CD"));
        mvc.perform(get("/api/reference/places").param("country", "CD").session(employee))
                .andExpect(jsonPath("$.length()").value(26));
        mvc.perform(get("/api/reference/places").param("country", "CG").session(employee))
                .andExpect(jsonPath("$.length()").value(15));
        long kinshasa = placeId(employee, "CD", null, "Kinshasa");
        long city = placeId(employee, "CD", kinshasa, "Kinshasa");
        mvc.perform(get("/api/reference/places").param("country", "CD").param("parent", String.valueOf(city)).session(employee))
                .andExpect(jsonPath("$.length()").value(24));
        assertThat(jdbc.sql("select count(*) from place where kind = 'TERRITORY' and country_code = 'CD'")
                .query(Long.class).single()).isEqualTo(145L);
    }

    @Test
    void profileIsPickedFromTheLists() throws Exception {
        long employee = id("employee2@demo.hrpay.cd");
        MockHttpSession hr = login(HR);
        long kinshasa = placeId(hr, "CD", null, "Kinshasa");
        long city = placeId(hr, "CD", kinshasa, "Kinshasa");
        long ngaliema = placeId(hr, "CD", city, "Ngaliema");
        long nordKivu = placeId(hr, "CD", null, "Nord-Kivu");
        long masisi = placeId(hr, "CD", nordKivu, "Masisi");
        long equity = bankId(hr, "Equity Banque Commerciale du Congo");

        mvc.perform(json(put("/api/users/" + employee + "/profile").session(hr), """
                        {"maritalStatus":"SINGLE","paymentMethod":"BANK","bankId":%d,"bankAccount":"0362-000",
                         "nationalityCode":"CD","birthCountryCode":"CD","birthPlaceId":%d,"addressPlaceId":%d,
                         "addressQuarter":"Binza Ozone","addressStreet":"Av. de la Paix 12",
                         "idDocumentType":"ELECTOR_CARD","idDocumentNumber":"1234567890","idDocumentIssuedAt":"Goma"}"""
                        .formatted(equity, masisi, ngaliema)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankName").value("Equity Banque Commerciale du Congo"))
                .andExpect(jsonPath("$.commune").value("Ngaliema"))
                .andExpect(jsonPath("$.nationality").value("Congolese (DRC)"))
                .andExpect(jsonPath("$.idDocumentNumber").value("1234567890"));
        mvc.perform(get("/api/users/" + employee + "/file").session(hr))
                .andExpect(jsonPath("$.addressPlacePath.length()").value(3))
                .andExpect(jsonPath("$.addressPlacePath[2].name").value("Ngaliema"))
                .andExpect(jsonPath("$.birthPlacePath[1].name").value("Masisi"));
        // The identity number is kept encrypted.
        assertThat(jdbc.sql("select id_document_number from employee_profile where user_id = ?").param(employee)
                .query(String.class).single()).isNotEqualTo("1234567890");

        // A place of birth abroad is typed; a place from the lists must be in the country of birth.
        mvc.perform(json(put("/api/users/" + employee + "/profile").session(hr), """
                        {"maritalStatus":"SINGLE","paymentMethod":"CASH","birthCountryCode":"FR","birthPlaceId":%d}"""
                        .formatted(masisi)))
                .andExpect(status().is(422));
        mvc.perform(json(put("/api/users/" + employee + "/profile").session(hr), """
                        {"maritalStatus":"SINGLE","paymentMethod":"CASH","birthCountryCode":"FR","birthPlaceText":"Lyon"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.birthPlaceText").value("Lyon"));
        // A mobile money operator is not a bank.
        mvc.perform(json(put("/api/users/" + employee + "/profile").session(hr), """
                        {"maritalStatus":"SINGLE","paymentMethod":"BANK","bankId":%d,"bankAccount":"1"}"""
                        .formatted(JsonPath.<Number>read(mvc.perform(get("/api/reference/institutions")
                                .param("country", "CD").param("kind", "MOBILE_MONEY").session(hr)).andReturn().getResponse()
                                .getContentAsString(), "$[0].id").longValue())))
                .andExpect(status().is(422));
    }

    @Test
    void companyAddsWhatIsMissingAndOnlyHrPayChangesTheSharedLists() throws Exception {
        MockHttpSession hr = login(HR);
        long kinshasa = placeId(hr, "CD", null, "Kinshasa");
        long city = placeId(hr, "CD", kinshasa, "Kinshasa");
        String added = mvc.perform(json(post("/api/reference/places").session(hr), """
                        {"countryCode":"CD","parentId":%d,"kind":"COMMUNE","name":"Ngaliema-Est"}""".formatted(city)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shared").value(false))
                .andReturn().getResponse().getContentAsString();
        long own = JsonPath.<Number>read(added, "$.id").longValue();
        mvc.perform(json(post("/api/reference/places").session(hr), """
                        {"countryCode":"CD","parentId":%d,"kind":"COMMUNE","name":"ngaliema est"}""".formatted(city)))
                .andExpect(status().isConflict());
        // HR may not touch a shared place, nor add to the shared lists.
        long gombe = placeId(hr, "CD", city, "Gombe");
        mvc.perform(json(put("/api/reference/places/" + gombe).session(hr), "{\"name\":\"Gombe 2\"}"))
                .andExpect(status().is(422));
        mvc.perform(json(post("/api/reference/institutions").session(hr), """
                        {"countryCode":"CD","kind":"BANK","name":"Banque Test","shared":true}"""))
                .andExpect(status().is(422));
        mvc.perform(json(post("/api/reference/institutions").session(hr), """
                        {"countryCode":"CD","kind":"BANK","name":"Banque Test","shortName":"BT","swiftCode":"btstcdki"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.swiftCode").value("BTSTCDKI"));
        mvc.perform(json(put("/api/reference/places/" + own).session(hr), "{\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        // An employee reads the lists but does not write them.
        mvc.perform(json(post("/api/reference/places").session(login(EMPLOYEE)), """
                        {"countryCode":"CD","parentId":%d,"kind":"COMMUNE","name":"X"}""".formatted(city)))
                .andExpect(status().isForbidden());
        // The provider's administrator keeps the shared list.
        mvc.perform(json(post("/api/reference/institutions").session(login(ADMIN)), """
                        {"countryCode":"CD","kind":"MOBILE_MONEY","name":"Opérateur Test","shared":true,"verified":true}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shared").value(true));
    }

    @Test
    void theBranchOfActivityIsPickedFromHrPaysList() throws Exception {
        jdbc.sql("delete from activity_branch where code = '59'").update();
        MockHttpSession hr = login(HR);
        String list = mvc.perform(get("/api/reference/branches").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("A"))
                .andExpect(jsonPath("$[?(@.code == 'J')].name").value(Matchers.contains("Information and communication")))
                .andReturn().getResponse().getContentAsString();
        long telecoms = JsonPath.<List<Number>>read(list, "$[?(@.code == '61')].id").getFirst().longValue();
        // Each division follows its section.
        List<String> codes = JsonPath.read(list, "$[*].code");
        assertThat(codes.indexOf("61")).isEqualTo(codes.indexOf("J") + 3);

        mvc.perform(json(put("/api/company/payslip-settings").session(hr),
                        "{\"template\":\"CLASSIC\",\"activityBranchId\":999999}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(put("/api/company/payslip-settings").session(hr),
                        "{\"template\":\"CLASSIC\",\"activityBranchId\":%d}".formatted(telecoms)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityBranchId").value(telecoms));
        String lang = jdbc.sql("select language from company where id = 1").query(String.class).single();
        assertThat(jdbc.sql("select activity from company where id = 1").query(String.class).single())
                .isEqualTo("en".equals(lang) ? "Telecommunications" : "Télécommunications");

        // Only HR Pay writes the list.
        mvc.perform(json(post("/api/reference/branches").session(hr), """
                        {"code":"99","parentId":%d,"nameFr":"X","nameEn":"X"}""".formatted(telecoms)))
                .andExpect(status().isForbidden());
        MockHttpSession admin = login(ADMIN);
        long section = JsonPath.<List<Number>>read(list, "$[?(@.code == 'J')].id").getFirst().longValue();
        mvc.perform(json(post("/api/reference/branches").session(admin), """
                        {"code":"59","parentId":%d,"nameFr":"Cinéma et musique","nameEn":"Film and music"}""".formatted(telecoms)))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(post("/api/reference/branches").session(admin), """
                        {"code":"59","parentId":%d,"nameFr":"Cinéma et musique","nameEn":"Film and music"}""".formatted(section)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.verified").value(false));
        // A rename reaches the companies that picked the branch.
        mvc.perform(json(put("/api/reference/branches/" + telecoms).session(admin), """
                        {"nameFr":"Télécoms","nameEn":"Telecoms","verified":true}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(true));
        assertThat(jdbc.sql("select activity from company where id = 1").query(String.class).single())
                .isEqualTo("en".equals(lang) ? "Telecoms" : "Télécoms");
        mvc.perform(json(put("/api/reference/branches/" + telecoms).session(admin), """
                        {"nameFr":"Télécommunications","nameEn":"Telecommunications"}""")).andExpect(status().isOk());
    }

    @Test
    void typedNamesAreMatched() {
        assertThat(refs.matchCountry("Congolaise", "CD")).contains("CD");
        assertThat(refs.matchCountry("congolais", "CG")).contains("CG");
        assertThat(refs.matchCountry("Française", "CD")).contains("FR");
        assertThat(refs.matchCountry("Ivoirien", "CD")).contains("CI");
        assertThat(refs.matchCountry("RDC", "CG")).contains("CD");
        assertThat(refs.matchCountry("Atlantis", "CD")).isEmpty();
        assertThat(refs.matchInstitution("BCDC", "CD", "BANK").map(References.Institution::shortName)).contains("Equity BCDC");
        assertThat(refs.matchInstitution("tmb", "CD", "BANK").map(References.Institution::name)).contains("Trust Merchant Bank");
        assertThat(refs.matchInstitution("mpesa", "CD", "MOBILE_MONEY")).isPresent();
        assertThat(refs.matchPlace("Ngaliema", "CD").map(References.Place::kind)).contains("COMMUNE");
        assertThat(refs.matchPlace("Kalamu", "CD")).isEmpty();               // in Kinshasa and in Boma
        assertThat(refs.matchPlace("Kalamu (Boma)", "CD")).isPresent();
        assertThat(refs.matchPlace("Masisi", "CD").map(References.Place::kind)).contains("TERRITORY");
        assertThat(refs.label(refs.matchPlace("Ngaliema", "CD").orElseThrow().id())).isEqualTo("Ngaliema, Kinshasa");
    }
}
