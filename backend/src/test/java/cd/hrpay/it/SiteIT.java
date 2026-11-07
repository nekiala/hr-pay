package cd.hrpay.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class SiteIT extends IntegrationTest {

    static String site(String code, String ranges, boolean gpsRequired, String radius) {
        return """
                {"code":"%s","name":"Lubumbashi office","countryCode":"CD","timeZone":"Africa/Lubumbashi",
                 "centerLat":-11.6609,"centerLng":27.4794,"radiusM":%s,"gpsRequired":%s,"maxAccuracyM":80,
                 "active":true,"ipRanges":%s}""".formatted(code, radius, gpsRequired, ranges);
    }

    @Test
    void adminCreatesAndUpdatesASiteWithNormalizedRanges() throws Exception {
        MockHttpSession admin = login(ADMIN);
        String created = mvc.perform(json(post("/api/sites").session(admin),
                        site("FBM", "[{\"cidr\":\"198.51.100.7/24\",\"label\":\"Fibre\"}]", true, "200")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ipRanges[0].cidr").value("198.51.100.0/24"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();

        // Same range again plus a new one: the existing row is kept, no duplicate-key error.
        mvc.perform(json(put("/api/sites/" + id).session(admin),
                        site("FBM", "[{\"cidr\":\"198.51.100.0/24\",\"label\":\"Fibre (main)\"},"
                                + "{\"cidr\":\"192.0.2.10\",\"label\":\"Backup 4G router\"}]", true, "200")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ipRanges.length()").value(2))
                .andExpect(jsonPath("$.ipRanges[?(@.cidr=='198.51.100.0/24')].label").value("Fibre (main)"));
    }

    @Test
    void invalidRangesAndIncompleteGpsAreasAreRejected() throws Exception {
        MockHttpSession admin = login(ADMIN);
        mvc.perform(json(post("/api/sites").session(admin), site("BAD1", "[{\"cidr\":\"10.0.0.0/40\"}]", false, "100")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(post("/api/sites").session(admin), site("BAD2", "[]", true, "null")))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void hrCanReadButNotChangeSites() throws Exception {
        MockHttpSession hr = login(HR);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/sites").session(hr))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/sites").session(hr), site("HRX", "[]", false, "100")))
                .andExpect(status().isForbidden());
    }
}
