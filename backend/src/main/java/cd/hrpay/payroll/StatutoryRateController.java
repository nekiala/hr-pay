package cd.hrpay.payroll;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/statutory")
@PreAuthorize("hasAuthority('salary.read') or hasAuthority('masterdata.manage')")
public class StatutoryRateController {

    public record RateInput(@NotNull LocalDate effectiveFrom,
                            @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 4) BigDecimal value,
                            @Size(max = 300) String note) {}

    public record BandInput(@NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 2) BigDecimal lowerBound,
                            @DecimalMin("0") @Digits(integer = 14, fraction = 2) BigDecimal upperBound,
                            @NotNull @DecimalMin("0") @Digits(integer = 3, fraction = 3) BigDecimal ratePercent) {}

    public record ScaleInput(@NotNull LocalDate effectiveFrom, @NotEmpty @Size(max = 30) List<@Valid BandInput> bands) {}

    public record SettingInput(@NotBlank @Size(max = 40) String value) {}

    public record Overview(LocalDate on, List<StatutoryRates.RateType> rates, StatutoryRates.BracketSet ipr,
                           List<LocalDate> iprDates, Map<String, String> settings) {}

    public record IprTrial(@NotNull @DecimalMin("0") BigDecimal taxableMonthlyCdf, @Min(0) int dependants, LocalDate on) {}

    private final StatutoryRates rates;
    private final Clock clock;

    public StatutoryRateController(StatutoryRates rates, Clock clock) {
        this.rates = rates;
        this.clock = clock;
    }

    @GetMapping
    public Overview overview(@RequestParam(required = false) LocalDate on) {
        LocalDate day = on != null ? on : LocalDate.now(clock);
        return new Overview(day, rates.types(day), rates.brackets(day), rates.bracketDates(), rates.settings());
    }

    @GetMapping("/rates/{code}")
    public List<StatutoryRates.RateEntry> history(@PathVariable String code) {
        return rates.history(code);
    }

    @PostMapping("/rates/{code}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('masterdata.manage')")
    public StatutoryRates.RateEntry add(@PathVariable String code, @Valid @RequestBody RateInput b) {
        return rates.addRate(code, b.effectiveFrom(), b.value(), b.note());
    }

    @GetMapping("/ipr")
    public StatutoryRates.BracketSet scale(@RequestParam LocalDate from) {
        return new StatutoryRates.BracketSet(from, rates.bracketsFrom(from));
    }

    @PostMapping("/ipr")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('masterdata.manage')")
    public StatutoryRates.BracketSet addScale(@Valid @RequestBody ScaleInput b) {
        return rates.addBrackets(b.effectiveFrom(), b.bands().stream()
                .map(x -> new IprCalculator.Bracket(x.lowerBound(), x.upperBound(), x.ratePercent())).toList());
    }

    /** Works out the IPR for an amount, so HR can check the barème against a known payslip. */
    @PostMapping("/ipr/try")
    public IprCalculator.Result tryIpr(@Valid @RequestBody IprTrial b) {
        return rates.ipr(b.taxableMonthlyCdf(), b.dependants(), b.on() != null ? b.on() : LocalDate.now(clock));
    }

    @PutMapping("/settings/{key}")
    @PreAuthorize("hasAuthority('masterdata.manage')")
    public Map<String, String> setSetting(@PathVariable String key, @Valid @RequestBody SettingInput b) {
        rates.setSetting(key, b.value());
        return rates.settings();
    }
}
