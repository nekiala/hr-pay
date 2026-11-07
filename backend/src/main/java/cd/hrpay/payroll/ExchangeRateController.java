package cd.hrpay.payroll;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@cd.hrpay.platform.RequiresModule("PAYROLL")
@RequestMapping("/api/exchange-rates")
@PreAuthorize("hasAuthority('salary.read') or hasAuthority('masterdata.manage')")
public class ExchangeRateController {

    public record RateInput(@NotNull @Pattern(regexp = "USD|EUR") String currency, @NotNull LocalDate rateDate,
                            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal cdfPerUnit) {}

    public record Overview(LocalDate today, ExchangeRates.Rate latest, boolean stale, List<ExchangeRates.Rate> recent) {}

    private final ExchangeRates rates;

    public ExchangeRateController(ExchangeRates rates) {
        this.rates = rates;
    }

    @GetMapping
    public Overview overview(@RequestParam(defaultValue = "USD") String currency) {
        LocalDate today = rates.today();
        ExchangeRates.Rate latest = rates.on(currency, today).orElse(null);
        // Older than 4 days means the fetch has not worked for a while (weekends have no new rate).
        boolean stale = latest == null || latest.rateDate().isBefore(today.minusDays(4));
        return new Overview(today, latest, stale, rates.recent(currency, 30));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('masterdata.manage') or hasAuthority('salary.write')")
    public ExchangeRates.Rate enter(@Valid @RequestBody RateInput b) {
        return rates.enter(b.currency(), b.rateDate(), b.cdfPerUnit());
    }

    @PostMapping("/fetch")
    @PreAuthorize("hasAuthority('masterdata.manage') or hasAuthority('salary.write')")
    public ExchangeRates.FetchResult fetch() {
        return rates.fetch();
    }
}
