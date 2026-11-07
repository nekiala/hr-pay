package cd.hrpay.payroll;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Dated statutory rates, the IPR barème and payroll settings. */
@Service
public class StatutoryRates {

    public record RateType(String code, String name, String unit, String description, BigDecimal value,
                           LocalDate effectiveFrom, LocalDate nextChange, BigDecimal nextValue) {}

    public record RateEntry(long id, String code, LocalDate effectiveFrom, BigDecimal value, String note,
                            String createdBy, OffsetDateTime createdAt) {}

    public record BracketSet(LocalDate effectiveFrom, List<IprCalculator.Bracket> brackets) {}

    public static final List<String> SETTINGS = List.of("INPP_BAND", "RATE_DAY", "THIRTEENTH_ENABLED", "THIRTEENTH_BASE",
            "THIRTEENTH_FALLBACK_PERCENT", "THIRTEENTH_PAY_MONTH", "THIRTEENTH_PRORATE");

    private final JdbcClient jdbc;
    private final AuditService audit;

    public StatutoryRates(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Every rate type with the value in force on the date, and the next scheduled change if any. */
    public List<RateType> types(LocalDate on) {
        return jdbc.sql("""
                select t.code, t.name, t.unit, t.description,
                       cur.value, cur.effective_from,
                       nxt.effective_from as next_change, nxt.value as next_value
                from statutory_rate_type t
                left join lateral (select value, effective_from from statutory_rate r
                                   where r.code = t.code and r.effective_from <= ? order by effective_from desc limit 1) cur on true
                left join lateral (select value, effective_from from statutory_rate r
                                   where r.code = t.code and r.effective_from > ? order by effective_from limit 1) nxt on true
                order by t.display_order""").params(on, on).query(RateType.class).list();
    }

    public List<RateEntry> history(String code) {
        return jdbc.sql("""
                select id, code, effective_from, value, note, created_by, created_at from statutory_rate
                where code = ? order by effective_from desc""").param(code).query(RateEntry.class).list();
    }

    public BigDecimal rate(String code, LocalDate on) {
        return jdbc.sql("select value from statutory_rate where code = ? and effective_from <= ? order by effective_from desc limit 1")
                .params(code, on).query(BigDecimal.class).optional()
                .orElseThrow(() -> new BusinessRuleException("No " + code + " rate is in force on " + on + "."));
    }

    /** The rate in force, or zero when none is set. */
    public BigDecimal rateOrZero(String code, LocalDate on) {
        return jdbc.sql("select value from statutory_rate where code = ? and effective_from <= ? order by effective_from desc limit 1")
                .params(code, on).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    @Transactional
    public RateEntry addRate(String code, LocalDate effectiveFrom, BigDecimal value, String note) {
        String unit = jdbc.sql("select unit from statutory_rate_type where code = ?").param(code).query(String.class)
                .optional().orElseThrow(() -> new NotFoundException("Unknown rate " + code + "."));
        if (unit.equals("PERCENT") && value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessRuleException("A percentage cannot be above 100.");
        }
        if (unit.equals("COUNT") && value.stripTrailingZeros().scale() > 0) {
            throw new BusinessRuleException("This rate is a whole number.");
        }
        if (jdbc.sql("select count(*) from statutory_rate where code = ? and effective_from = ?").params(code, effectiveFrom)
                .query(Integer.class).single() > 0) {
            throw new ConflictException("A value already starts on " + effectiveFrom + ". Use another date.");
        }
        long id = jdbc.sql("""
                insert into statutory_rate (code, effective_from, value, note, created_by) values (?, ?, ?, ?, ?)
                returning id""").params(code, effectiveFrom, value, note, CurrentUser.email()).query(Long.class).single();
        audit.record("statutory_rate.added", "statutory_rate", id, Map.of("code", code,
                "effectiveFrom", effectiveFrom.toString(), "value", value.toPlainString()));
        return history(code).stream().filter(r -> r.id() == id).findFirst().orElseThrow();
    }

    // ---- IPR barème ------------------------------------------------------------------------------------------

    public BracketSet brackets(LocalDate on) {
        LocalDate from = jdbc.sql("select max(effective_from) from ipr_bracket where effective_from <= ?").param(on)
                .query(LocalDate.class).optional().orElse(null);
        if (from == null) {
            throw new BusinessRuleException("No IPR barème is in force on " + on + ".");
        }
        return new BracketSet(from, bracketsFrom(from));
    }

    public List<LocalDate> bracketDates() {
        return jdbc.sql("select distinct effective_from from ipr_bracket order by effective_from desc").query(LocalDate.class).list();
    }

    public List<IprCalculator.Bracket> bracketsFrom(LocalDate from) {
        return jdbc.sql("select lower_bound, upper_bound, rate_percent from ipr_bracket where effective_from = ? order by lower_bound")
                .param(from).query(IprCalculator.Bracket.class).list();
    }

    /** Adds a full barème from a date. Bands must start at 0, follow each other without gaps, and end open. */
    @Transactional
    public BracketSet addBrackets(LocalDate effectiveFrom, List<IprCalculator.Bracket> brackets) {
        if (brackets.isEmpty()) {
            throw new BusinessRuleException("Give at least one band.");
        }
        BigDecimal expected = BigDecimal.ZERO;
        for (int i = 0; i < brackets.size(); i++) {
            IprCalculator.Bracket b = brackets.get(i);
            boolean last = i == brackets.size() - 1;
            if (b.lowerBound().compareTo(expected) != 0) {
                throw new BusinessRuleException("Band " + (i + 1) + " must start at " + expected.toPlainString() + ".");
            }
            if (last != (b.upperBound() == null)) {
                throw new BusinessRuleException(last ? "The last band must have no upper limit." : "Band " + (i + 1) + " needs an upper limit.");
            }
            if (!last && b.upperBound().compareTo(b.lowerBound()) <= 0) {
                throw new BusinessRuleException("Band " + (i + 1) + " must end above where it starts.");
            }
            if (b.ratePercent().signum() < 0 || b.ratePercent().compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new BusinessRuleException("Band " + (i + 1) + " has a rate outside 0 to 100%.");
            }
            expected = b.upperBound();
        }
        if (jdbc.sql("select count(*) from ipr_bracket where effective_from = ?").param(effectiveFrom).query(Integer.class).single() > 0) {
            throw new ConflictException("A barème already starts on " + effectiveFrom + ". Use another date.");
        }
        for (IprCalculator.Bracket b : brackets) {
            jdbc.sql("insert into ipr_bracket (effective_from, lower_bound, upper_bound, rate_percent, created_by) values (?, ?, ?, ?, ?)")
                    .params(effectiveFrom, b.lowerBound(), b.upperBound(), b.ratePercent(), CurrentUser.email()).update();
        }
        audit.record("ipr_scale.added", "ipr_bracket", null, Map.of("effectiveFrom", effectiveFrom.toString(),
                "bands", brackets.size()));
        return new BracketSet(effectiveFrom, bracketsFrom(effectiveFrom));
    }

    public IprCalculator.Result ipr(BigDecimal taxableMonthlyCdf, int dependants, LocalDate on) {
        return IprCalculator.monthly(taxableMonthlyCdf, dependants, brackets(on).brackets(),
                rate("IPR_DEPENDANT_REDUCTION", on), rate("IPR_MAX_DEPENDANTS", on).intValue(), rate("IPR_CAP", on));
    }

    // ---- settings ----------------------------------------------------------------------------------------------

    public Map<String, String> settings() {
        Map<String, String> all = new java.util.LinkedHashMap<>();
        jdbc.sql("select key, value from payroll_setting order by key").query((rs, n) -> all.put(rs.getString(1), rs.getString(2))).list();
        return all;
    }

    public String setting(String key) {
        return jdbc.sql("select value from payroll_setting where key = ?").param(key).query(String.class).single();
    }

    @Transactional
    public void setSetting(String key, String value) {
        boolean valid = switch (key) {
            case "INPP_BAND" -> List.of("SMALL", "MEDIUM", "LARGE").contains(value);
            case "RATE_DAY" -> List.of("LAST_OF_MONTH", "RUN_DATE").contains(value);
            case "THIRTEENTH_ENABLED", "THIRTEENTH_PRORATE" -> List.of("true", "false").contains(value);
            case "THIRTEENTH_BASE" -> List.of("AVERAGE_BASIC", "LAST_BASIC").contains(value);
            case "THIRTEENTH_FALLBACK_PERCENT" -> value.matches("\\d{1,3}(\\.\\d{1,2})?") && new BigDecimal(value).compareTo(BigDecimal.valueOf(100)) <= 0;
            case "THIRTEENTH_PAY_MONTH" -> value.matches("[1-9]|1[0-2]");
            default -> throw new NotFoundException("Unknown setting " + key + ".");
        };
        if (!valid) {
            throw new BusinessRuleException("Invalid value for " + key + ".");
        }
        jdbc.sql("update payroll_setting set value = ? where key = ?").params(value, key).update();
        audit.record("payroll_setting.changed", "payroll_setting", null, Map.of("key", key, "value", value));
    }

    public BigDecimal inppRate(LocalDate on) {
        return rate("INPP_" + setting("INPP_BAND"), on);
    }
}
