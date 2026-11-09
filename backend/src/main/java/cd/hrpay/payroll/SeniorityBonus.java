package cd.hrpay.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The seniority bonus (prime d'ancienneté) of a collective agreement: a percentage of the basic salary per full year
 * with the employer, from a number of years, up to a ceiling. The law leaves it to branch and company agreements
 * (DRC interprofessional agreement art. 20 and 24), so it is off (0) until a sector pack or HR sets it.
 */
@Component
public class SeniorityBonus {

    public record Rule(BigDecimal percentPerYear, BigDecimal maxPercent, int fromYears) {

        public boolean on() {
            return percentPerYear.signum() > 0;
        }
    }

    private final JdbcClient jdbc;

    public SeniorityBonus(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private BigDecimal setting(String key) {
        return jdbc.sql("select value from payroll_setting where key = ?").param(key).query(String.class).optional()
                .map(BigDecimal::new).orElse(BigDecimal.ZERO);
    }

    public Rule rule() {
        return new Rule(setting("SENIORITY_BONUS_PERCENT_PER_YEAR"), setting("SENIORITY_BONUS_MAX_PERCENT"),
                setting("SENIORITY_BONUS_FROM_YEARS").intValue());
    }

    /** The percentage of the basic salary due on a day to a person hired on {@code hired}; null when none. */
    public BigDecimal percent(LocalDate hired, LocalDate on) {
        Rule r = rule();
        return r.on() ? percent(r, hired, on) : null;
    }

    static BigDecimal percent(Rule r, LocalDate hired, LocalDate on) {
        if (hired == null || !r.on() || hired.isAfter(on)) {
            return null;
        }
        long years = ChronoUnit.YEARS.between(hired, on);
        if (years < 1 || years < r.fromYears()) {
            return null;
        }
        BigDecimal p = r.percentPerYear().multiply(BigDecimal.valueOf(years));
        if (r.maxPercent().signum() > 0) {
            p = p.min(r.maxPercent());
        }
        return p;
    }
}
