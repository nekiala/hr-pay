package cd.hrpay.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class StatutoryRatesIT extends IntegrationTest {

    @Test
    void ratesAreDatedAndTheOldValueStaysForEarlierDates() throws Exception {
        MockHttpSession hr = login(HR);
        mvc.perform(json(post("/api/statutory/rates/ONEM").session(hr), """
                        {"effectiveFrom":"2031-01-01","value":0.7,"note":"Test change"}"""))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/statutory").param("on", "2030-12-31").session(hr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates[?(@.code=='ONEM')].value").value(0.5))
                .andExpect(jsonPath("$.rates[?(@.code=='ONEM')].nextValue").value(0.7));
        mvc.perform(get("/api/statutory").param("on", "2031-01-01").session(hr))
                .andExpect(jsonPath("$.rates[?(@.code=='ONEM')].value").value(0.7));
        mvc.perform(json(post("/api/statutory/rates/ONEM").session(hr), """
                        {"effectiveFrom":"2031-01-01","value":0.6}"""))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/statutory/rates/CNSS_EMPLOYEE").session(hr), """
                        {"effectiveFrom":"2031-01-01","value":150}"""))
                .andExpect(status().is(422));
        mvc.perform(json(post("/api/statutory/rates/ONEM").session(login(FINANCE)), """
                        {"effectiveFrom":"2032-01-01","value":0.6}"""))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/statutory").session(login(EMPLOYEE))).andExpect(status().isForbidden());
    }

    @Test
    void anIprScaleMustBeContinuousAndOpenEnded() throws Exception {
        MockHttpSession hr = login(HR);
        mvc.perform(json(post("/api/statutory/ipr").session(hr), """
                        {"effectiveFrom":"2031-01-01","bands":[
                          {"lowerBound":0,"upperBound":1000000,"ratePercent":0},
                          {"lowerBound":1200000,"ratePercent":10}]}"""))
                .andExpect(status().is(422));
        mvc.perform(json(post("/api/statutory/ipr").session(hr), """
                        {"effectiveFrom":"2031-01-01","bands":[
                          {"lowerBound":0,"upperBound":1200000,"ratePercent":0},
                          {"lowerBound":1200000,"upperBound":2400000,"ratePercent":10},
                          {"lowerBound":2400000,"ratePercent":20}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.brackets.length()").value(3));
        mvc.perform(json(post("/api/statutory/ipr/try").session(hr), """
                        {"taxableMonthlyCdf":300000,"dependants":3,"on":"2031-02-01"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tax").value(28200.00));
        mvc.perform(json(put("/api/statutory/settings/INPP_BAND").session(hr), """
                        {"value":"HUGE"}"""))
                .andExpect(status().is(422));
        // By default the band follows the number of people under contract.
        mvc.perform(get("/api/statutory").session(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.INPP_BAND").value("AUTO"))
                .andExpect(jsonPath("$.staff").isNumber())
                .andExpect(jsonPath("$.inppBand").isString());
    }
}
