package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cd.hrpay.payroll.ExchangeRates;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

class ExchangeRatesIT extends IntegrationTest {

    @Autowired
    ExchangeRates rates;

    @Test
    void storesTheApiAnswerOncePerCurrencyAndDay() {
        String answer = """
                {"data":[{"currency_code":"USD","rate_date":"2020-03-06","indicative_rate":"2261.000000",
                          "source_name":"BCC","source_url":"https://www.bcc.cd"},
                         {"currency_code":"EUR","rate_date":"2020-03-06","indicative_rate":"2450.5"},
                         {"currency_code":"XX","rate_date":"2020-03-06","indicative_rate":"1"}]}""";
        ExchangeRates.FetchResult first = rates.store(answer);
        assertThat(first.stored()).isEqualTo(2);
        ExchangeRates.FetchResult again = rates.store(answer);
        assertThat(again.stored()).isZero();
        assertThat(again.alreadyKnown()).isEqualTo(2);
        assertThat(rates.on("USD", LocalDate.parse("2020-03-08")).orElseThrow().cdfPerUnit())
                .isEqualByComparingTo("2261");
        assertThat(rates.store("not json").error()).isNotNull();
    }

    @Test
    void convertsThroughTheCdfRate() {
        BigDecimal rate = new BigDecimal("2261");
        assertThat(ExchangeRates.convert(new BigDecimal("4500"), "USD", "CDF", rate)).isEqualByComparingTo("10174500");
        assertThat(ExchangeRates.convert(new BigDecimal("10174500"), "CDF", "USD", rate)).isEqualByComparingTo("4500");
        assertThat(ExchangeRates.convert(new BigDecimal("12.345"), "USD", "USD", rate)).isEqualByComparingTo("12.35");
    }

    @Test
    void hrCanEnterARateByHand() throws Exception {
        MockHttpSession hr = login(HR);
        mvc.perform(json(post("/api/exchange-rates").session(hr), """
                        {"currency":"USD","rateDate":"2020-04-01","cdfPerUnit":2300}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("Entered by HR"));
        mvc.perform(json(post("/api/exchange-rates").session(hr), """
                        {"currency":"USD","rateDate":"2020-04-01","cdfPerUnit":2301}"""))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/exchange-rates").session(login(FINANCE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latest.currency").value("USD"));
        mvc.perform(get("/api/exchange-rates").session(login(EMPLOYEE))).andExpect(status().isForbidden());
    }
}
