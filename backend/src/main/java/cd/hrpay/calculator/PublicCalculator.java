package cd.hrpay.calculator;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.payroll.ExchangeRates;
import cd.hrpay.payroll.IprCalculator;
import cd.hrpay.payroll.PayElements;
import cd.hrpay.payroll.PayslipCalculator;
import cd.hrpay.platform.CountryPacks;
import cd.hrpay.tenancy.Tenants;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * The website's salary calculator: one month's pay for one person, worked out by the payroll engine with the rates
 * of the country's pack (the official rates the platform keeps), never a company's own. Nothing is stored.
 *
 * <p>From a gross basic salary it gives the net; from a wanted net it finds the basic salary that gives it. The
 * employer's cost is the gross plus the employer's contributions and taxes.
 */
@Service
public class PublicCalculator {

    public enum Mode { GROSS, NET }

    /** What the visitor enters; amounts are monthly, in {@code currency}. */
    public record Ask(String country, Mode mode, BigDecimal amount, String currency, BigDecimal housing,
                      BigDecimal transport, BigDecimal familyAllowances, BigDecimal bonus, int children,
                      String maritalStatus, boolean spouseInCharge, int employees, boolean expatriate) {}

    public record Line(String code, String kind, BigDecimal base, BigDecimal rate, BigDecimal amount) {}

    /** A rate the result used, with the text it comes from. */
    public record Source(String code, BigDecimal value, LocalDate effectiveFrom, String note) {}

    public record Minimum(BigDecimal monthly, String currency, boolean below) {}

    public record Answer(String country, String currency, String localCurrency, LocalDate ratesOn, BigDecimal basic,
                         BigDecimal gross, BigDecimal employeeDeductions, BigDecimal net, BigDecimal employerCharges,
                         BigDecimal employerCost, List<Line> lines, BigDecimal usdRate, LocalDate usdRateDate,
                         Minimum minimum, List<Source> sources) {}

    /** The earnings a visitor can enter, each with the pack element that says how it is taxed. */
    private static final List<String> ENTERED = List.of("HOUSING", "TRANSPORT", "FAMILY_ALLOWANCE", "OTHER_BONUS");

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");

    private final CountryPacks packs;
    private final ExchangeRates exchangeRates;
    private final Tenants tenants;
    private final Clock clock;
    private final Set<String> countries;
    private final Map<String, JsonNode> cache = new java.util.concurrent.ConcurrentHashMap<>();

    public PublicCalculator(CountryPacks packs, ExchangeRates exchangeRates, Tenants tenants, Clock clock,
                            @Value("${app.public-calculator.countries:CD,CG}") List<String> countries) {
        this.packs = packs;
        this.exchangeRates = exchangeRates;
        this.tenants = tenants;
        this.clock = clock;
        this.countries = Set.copyOf(countries.stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    public Set<String> countries() {
        return countries;
    }

    public Answer calculate(Ask in) {
        if (!countries.contains(in.country())) {
            throw new BusinessRuleException("The calculator is not open for this country yet.");
        }
        String local = CountryPacks.CURRENCIES.get(in.country());
        if (!in.currency().equals(local) && !in.currency().equals("USD")) {
            throw new BusinessRuleException("Choose " + local + " or USD.");
        }
        check(in.amount(), "The amount");
        for (BigDecimal v : List.of(orZero(in.housing()), orZero(in.transport()), orZero(in.familyAllowances()), orZero(in.bonus()))) {
            check(v, "An allowance");
        }
        if (in.children() < 0 || in.children() > 20 || in.employees() < 0) {
            throw new BusinessRuleException("Check the number of children and employees.");
        }

        LocalDate today = LocalDate.now(clock);
        JsonNode pack = cache.computeIfAbsent(in.country(), packs::pack);
        Map<String, Source> rates = rates(pack, today);
        List<IprCalculator.Bracket> brackets = brackets(pack, today);
        List<PayElements.Element> elements = elements(pack);

        Optional<ExchangeRates.Rate> usd = tenants.callAsPlatform(() -> {
            try {
                return Optional.of(exchangeRates.local(local, today));
            } catch (BusinessRuleException e) {
                return Optional.<ExchangeRates.Rate>empty();
            }
        });
        if (in.currency().equals("USD") && usd.isEmpty()) {
            throw new BusinessRuleException("No dollar rate is known yet; enter the amounts in " + local + ".");
        }
        // Without a stored rate, a placeholder: nothing is converted when every amount is in the local currency.
        BigDecimal usdLocal = usd.map(ExchangeRates.Rate::cdfPerUnit).orElse(BigDecimal.ONE);

        // Cameroon, Gabon: like the Congo, a monthly SMIG.
        boolean congo = !"CD".equals(in.country());
        List<String> used = new ArrayList<>();
        PayslipCalculator.Rates r = CountryPacks.RULES_AS_DATA.contains(in.country())
                ? rulesRates(pack, rates, brackets, usdLocal, in.country(), local, today, used)
                : "CG".equals(in.country()) ? congoRates(rates, brackets, usdLocal, local, used)
                : drcRates(rates, brackets, usdLocal, in.employees(), used);

        BigDecimal basic;
        PayslipCalculator.Result result;
        if (in.mode() == Mode.GROSS) {
            basic = in.amount().setScale(2, RoundingMode.HALF_UP);
            result = run(in, basic, elements, r);
        } else {
            basic = solveBasic(in, elements, r);
            result = run(in, basic, elements, r);
        }

        Minimum minimum = minimum(congo, rates, r, in.currency(), basic, used);
        List<Line> lines = result.lines().stream()
                .map(l -> new Line(l.code(), l.kind(), l.base(), l.rate(), l.amount())).toList();
        List<Source> sources = used.stream().distinct().map(rates::get).filter(java.util.Objects::nonNull).toList();
        return new Answer(in.country(), in.currency(), local, today, basic, result.gross(), result.employeeDeductions(),
                result.net(), result.employerCharges(), result.gross().add(result.employerCharges()), lines,
                usd.map(ExchangeRates.Rate::cdfPerUnit).orElse(null), usd.map(ExchangeRates.Rate::rateDate).orElse(null),
                minimum, sources);
    }

    // ---- the month ----------------------------------------------------------------------------------------------

    private PayslipCalculator.Result run(Ask in, BigDecimal basic, List<PayElements.Element> elements,
                                         PayslipCalculator.Rates r) {
        List<PayElements.Entry> entries = new ArrayList<>();
        BigDecimal[] amounts = {in.housing(), in.transport(), in.familyAllowances(), in.bonus()};
        Map<String, PayElements.Element> byCode = new HashMap<>();
        elements.forEach(e -> byCode.put(e.code(), e));
        for (int i = 0; i < ENTERED.size(); i++) {
            BigDecimal v = orZero(amounts[i]);
            if (v.signum() == 0) {
                continue;
            }
            PayElements.Element e = byCode.get(ENTERED.get(i));
            entries.add(new PayElements.Entry(0, 0, null, e.id(), e.code(), e.name(), e.kind(), null, v,
                    in.currency(), null, null, null));
        }
        // A full month: every scheduled hour paid (the number of hours does not change a monthly salary).
        int minutes = 26 * 8 * 60;
        boolean spouse = in.spouseInCharge();
        int dependants = in.children() + (spouse ? 1 : 0);
        String marital = in.maritalStatus() == null || in.maritalStatus().isBlank() ? null : in.maritalStatus();
        PayslipCalculator.Input input = new PayslipCalculator.Input(in.currency(), basic, minutes, minutes, 26, 26,
                BigDecimal.ZERO, 0, null, null, in.children(), dependants, in.expatriate() && "CD".equals(in.country()),
                elements, List.of(), entries, List.of(), r, 0, null, marital);
        return PayslipCalculator.calculate(input);
    }

    /** The basic salary whose month gives the wanted net, within one cent. */
    private BigDecimal solveBasic(Ask in, List<PayElements.Element> elements, PayslipCalculator.Rates r) {
        BigDecimal target = in.amount();
        BigDecimal low = BigDecimal.ZERO;
        BigDecimal high = target.max(BigDecimal.ONE);
        // The net never grows faster than the basic; double until the net passes the target.
        for (int i = 0; i < 60 && run(in, high, elements, r).net().compareTo(target) < 0; i++) {
            high = high.multiply(BigDecimal.TWO);
        }
        if (run(in, low, elements, r).net().compareTo(target) >= 0) {
            return low.setScale(2, RoundingMode.HALF_UP);   // the allowances alone already reach it
        }
        for (int i = 0; i < 80 && high.subtract(low).compareTo(new BigDecimal("0.005")) > 0; i++) {
            BigDecimal mid = low.add(high).divide(BigDecimal.TWO, 6, RoundingMode.HALF_UP);
            if (run(in, mid.setScale(2, RoundingMode.HALF_UP), elements, r).net().compareTo(target) < 0) {
                low = mid;
            } else {
                high = mid;
            }
        }
        BigDecimal answer = high.setScale(2, RoundingMode.HALF_UP);
        // Rounding may leave the net a cent short; take the next cent then.
        if (run(in, answer, elements, r).net().compareTo(target) < 0) {
            answer = answer.add(new BigDecimal("0.01"));
        }
        return answer;
    }

    private Minimum minimum(boolean congo, Map<String, Source> rates, PayslipCalculator.Rates r, String currency,
                            BigDecimal basic, List<String> used) {
        BigDecimal localMonthly;
        if (congo) {
            Source smig = rates.get("SMIG_MONTHLY");
            if (smig == null) {
                return null;
            }
            used.add("SMIG_MONTHLY");
            localMonthly = smig.value();
        } else {
            Source smig = rates.get("SMIG_DAILY");
            if (smig == null) {
                return null;
            }
            used.add("SMIG_DAILY");
            localMonthly = PayslipCalculator.minimumMonthly(smig.value(), BigDecimal.valueOf(100));
        }
        BigDecimal inCurrency = ExchangeRates.convert(localMonthly, r.local(), currency, r.usdCdf());
        return new Minimum(inCurrency, currency, basic.compareTo(inCurrency) < 0);
    }

    // ---- the pack's rates -----------------------------------------------------------------------------------------

    private PayslipCalculator.Rates drcRates(Map<String, Source> rates, List<IprCalculator.Bracket> brackets,
                                             BigDecimal usdLocal, int employees, List<String> used) {
        String inpp = employees <= 50 ? "INPP_SMALL" : employees <= 300 ? "INPP_MEDIUM" : "INPP_LARGE";
        used.addAll(List.of("CNSS_EMPLOYEE", "CNSS_EMPLOYER", inpp, "ONEM", "IERE", "IPR_DEPENDANT_REDUCTION",
                "IPR_MAX_DEPENDANTS", "IPR_CAP", "IPR_MIN_MONTHLY"));
        return new PayslipCalculator.Rates(usdLocal, value(rates, "CNSS_EMPLOYEE"), value(rates, "CNSS_EMPLOYER"),
                value(rates, inpp), value(rates, "ONEM"), value(rates, "IERE"), BigDecimal.valueOf(100), brackets,
                value(rates, "IPR_DEPENDANT_REDUCTION"), value(rates, "IPR_MAX_DEPENDANTS").intValue(),
                value(rates, "IPR_CAP"), null, "CD", "CDF",
                Map.of("IPR_MIN_MONTHLY", rates.containsKey("IPR_MIN_MONTHLY") ? value(rates, "IPR_MIN_MONTHLY") : BigDecimal.ZERO));
    }

    private PayslipCalculator.Rates congoRates(Map<String, Source> rates, List<IprCalculator.Bracket> brackets,
                                               BigDecimal usdLocal, String local, List<String> used) {
        Map<String, BigDecimal> more = new HashMap<>();
        for (String code : List.of("CNSS_CEILING", "CNSS_FAMILY", "CNSS_ACCIDENT", "CNSS_FAMILY_CEILING", "TUS",
                "ITS_PROFESSIONAL_DEDUCTION", "ITS_MAX_PARTS", "SMIG_MONTHLY")) {
            more.put(code, value(rates, code));
            used.add(code);
        }
        for (String code : List.of("ITS_FIRST_BAND_FLAT", "ITS_MINIMUM_YEARLY")) {
            if (rates.containsKey(code)) {
                more.put(code, value(rates, code));
                used.add(code);
            }
        }
        used.addAll(List.of("CNSS_EMPLOYEE", "CNSS_EMPLOYER"));
        return new PayslipCalculator.Rates(usdLocal, value(rates, "CNSS_EMPLOYEE"), value(rates, "CNSS_EMPLOYER"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(100), brackets, BigDecimal.ZERO, 0,
                BigDecimal.valueOf(100), null, "CG", local, Map.copyOf(more));
    }

    /** A country whose rules are data: its contributions, scales and tax settings from the pack. */
    private PayslipCalculator.Rates rulesRates(JsonNode pack, Map<String, Source> rates, List<IprCalculator.Bracket> brackets,
                                               BigDecimal usdLocal, String country, String local, LocalDate on,
                                               List<String> used) {
        List<cd.hrpay.payroll.CountryRules.Row> rows = new ArrayList<>();
        for (JsonNode c : table(pack, "payroll_contribution")) {
            rows.add(new cd.hrpay.payroll.CountryRules.Row(c.get("code").asString(), c.get("side").asString(),
                    c.get("base").asString(), text(c, "rate_code"), text(c, "ceiling_code"), text(c, "exempt_code"),
                    text(c, "scale_code"), c.get("before_tax").asBoolean(), c.get("display_order").asInt()));
        }
        cd.hrpay.payroll.CountryRules rules = cd.hrpay.payroll.CountryRules.of(rows, code -> {
            Source s = rates.get(code);
            if (s == null) {
                return null;
            }
            used.add(code);
            return s.value();
        }, code -> scale(pack, code, on));
        return PayslipCalculator.Rates.of(usdLocal, country, local, BigDecimal.valueOf(100), brackets, rules);
    }

    /** A flat-amount scale's bands in force on the day. */
    static List<cd.hrpay.payroll.CountryRules.Band> scale(JsonNode pack, String code, LocalDate on) {
        LocalDate start = null;
        for (JsonNode row : table(pack, "payroll_scale")) {
            LocalDate from = LocalDate.parse(row.get("effective_from").asString());
            if (code.equals(row.get("code").asString()) && !from.isAfter(on) && (start == null || from.isAfter(start))) {
                start = from;
            }
        }
        List<cd.hrpay.payroll.CountryRules.Band> out = new ArrayList<>();
        for (JsonNode row : table(pack, "payroll_scale")) {
            if (code.equals(row.get("code").asString()) && LocalDate.parse(row.get("effective_from").asString()).equals(start)) {
                JsonNode upper = row.get("upper_bound");
                out.add(new cd.hrpay.payroll.CountryRules.Band(row.get("lower_bound").decimalValue(),
                        upper == null || upper.isNull() ? null : upper.decimalValue(), row.get("amount").decimalValue()));
            }
        }
        out.sort(java.util.Comparator.comparing(cd.hrpay.payroll.CountryRules.Band::lower));
        return out;
    }

    private static String text(JsonNode row, String field) {
        JsonNode v = row.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    /** Each rate's value in force on the day: the row with the latest start on or before it. */
    static Map<String, Source> rates(JsonNode pack, LocalDate on) {
        Map<String, Source> latest = new HashMap<>();
        for (JsonNode row : table(pack, "statutory_rate")) {
            LocalDate from = LocalDate.parse(row.get("effective_from").asString());
            if (from.isAfter(on)) {
                continue;
            }
            String code = row.get("code").asString();
            Source known = latest.get(code);
            if (known == null || from.isAfter(known.effectiveFrom())) {
                JsonNode note = row.get("note");
                latest.put(code, new Source(code, row.get("value").decimalValue(), from,
                        note == null || note.isNull() ? null : note.asString()));
            }
        }
        return latest;
    }

    /** The income tax scale in force on the day. */
    static List<IprCalculator.Bracket> brackets(JsonNode pack, LocalDate on) {
        LocalDate start = null;
        for (JsonNode row : table(pack, "ipr_bracket")) {
            LocalDate from = LocalDate.parse(row.get("effective_from").asString());
            if (!from.isAfter(on) && (start == null || from.isAfter(start))) {
                start = from;
            }
        }
        List<IprCalculator.Bracket> out = new ArrayList<>();
        for (JsonNode row : table(pack, "ipr_bracket")) {
            if (LocalDate.parse(row.get("effective_from").asString()).equals(start)) {
                JsonNode upper = row.get("upper_bound");
                out.add(new IprCalculator.Bracket(row.get("lower_bound").decimalValue(),
                        upper == null || upper.isNull() ? null : upper.decimalValue(), row.get("rate_percent").decimalValue()));
            }
        }
        out.sort(java.util.Comparator.comparing(IprCalculator.Bracket::lowerBound));
        return out;
    }

    /** The pack's pay elements, none of them given to everyone: the visitor's entries are the only allowances. */
    static List<PayElements.Element> elements(JsonNode pack) {
        List<PayElements.Element> out = new ArrayList<>();
        for (JsonNode e : table(pack, "pay_element")) {
            out.add(new PayElements.Element(e.get("id").asLong(), e.get("code").asString(), e.get("name").asString(),
                    e.get("kind").asString(), e.get("method").asString(), null, null, null,
                    e.get("subject_to_cnss").asBoolean(), e.get("taxable_ipr").asBoolean(),
                    e.get("ipr_exempt_cap_percent") == null || e.get("ipr_exempt_cap_percent").isNull() ? null
                            : e.get("ipr_exempt_cap_percent").decimalValue(),
                    e.get("prorated").asBoolean(), false, e.get("system").asBoolean(), e.get("display_order").asInt(),
                    e.get("active").asBoolean()));
        }
        return out;
    }

    private static Iterable<JsonNode> table(JsonNode pack, String name) {
        for (JsonNode t : pack.get("tables")) {
            if (name.equals(t.get("table").asString())) {
                return t.get("rows");
            }
        }
        throw new IllegalStateException("The pack has no " + name + " table");
    }

    private static BigDecimal value(Map<String, Source> rates, String code) {
        Source s = rates.get(code);
        if (s == null) {
            throw new IllegalStateException("The rate " + code + " is missing from the pack");
        }
        return s.value();
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static void check(BigDecimal v, String what) {
        if (v == null || v.signum() < 0 || v.compareTo(MAX_AMOUNT) > 0) {
            throw new BusinessRuleException(what + " must be between 0 and 1,000,000,000,000.");
        }
    }
}
