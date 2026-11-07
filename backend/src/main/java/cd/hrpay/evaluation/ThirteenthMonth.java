package cd.hrpay.evaluation;

import cd.hrpay.payroll.StatutoryRates;
import cd.hrpay.salary.SalaryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The optional 13th month: base x the year's evaluation score (or HR's fallback %), prorated by the months worked.
 * The year's score is the average of its approved evaluations, weighted by the months each one covers.
 */
@Service
public class ThirteenthMonth {

    public record YearScore(BigDecimal percent, int evaluations) {}

    public record Row(long userId, String name, BigDecimal scorePercent, String scoreSource, int evaluations,
                      BigDecimal base, String currency, int monthsWorked, BigDecimal amount) {}

    public record Settings(boolean enabled, String base, BigDecimal fallbackPercent, int payMonth, boolean prorate) {}

    private final JdbcClient jdbc;
    private final SalaryService salaries;
    private final StatutoryRates settings;

    public ThirteenthMonth(JdbcClient jdbc, SalaryService salaries, StatutoryRates settings) {
        this.jdbc = jdbc;
        this.salaries = salaries;
        this.settings = settings;
    }

    public Settings settings() {
        return new Settings(Boolean.parseBoolean(settings.setting("THIRTEENTH_ENABLED")), settings.setting("THIRTEENTH_BASE"),
                new BigDecimal(settings.setting("THIRTEENTH_FALLBACK_PERCENT")),
                Integer.parseInt(settings.setting("THIRTEENTH_PAY_MONTH")),
                Boolean.parseBoolean(settings.setting("THIRTEENTH_PRORATE")));
    }

    /** Average of the approved evaluations ending in the year, weighted by the months each covers. */
    public Optional<YearScore> yearScore(long userId, int year) {
        List<BigDecimal[]> rows = jdbc.sql("""
                select total_percent, period_start, period_end from evaluation
                where user_id = ? and status = 'APPROVED' and extract(year from period_end) = ? and total_percent is not null""")
                .params(userId, year)
                .query((rs, i) -> {
                    LocalDate s = rs.getObject("period_start", LocalDate.class);
                    LocalDate e = rs.getObject("period_end", LocalDate.class);
                    long months = YearMonth.from(s).until(YearMonth.from(e), java.time.temporal.ChronoUnit.MONTHS) + 1;
                    return new BigDecimal[] {rs.getBigDecimal("total_percent"), BigDecimal.valueOf(months)};
                }).list();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal weighted = rows.stream().map(r -> r[0].multiply(r[1])).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal months = rows.stream().map(r -> r[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(new YearScore(weighted.divide(months, 2, RoundingMode.HALF_UP), rows.size()));
    }

    /** The 13th month of one employee for a year, or empty if they have no contract or salary in it. */
    public Optional<Row> forEmployee(long userId, String name, int year) {
        Settings s = settings();
        List<YearMonth> worked = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            YearMonth ym = YearMonth.of(year, m);
            boolean underContract = jdbc.sql("""
                    select count(*) from employee_contract where user_id = ? and start_date <= ?
                      and (end_date is null or end_date >= ?)""")
                    .params(userId, ym.atEndOfMonth(), ym.atDay(1)).query(Integer.class).single() > 0;
            if (underContract) {
                worked.add(ym);
            }
        }
        if (worked.isEmpty()) {
            return Optional.empty();
        }
        List<SalaryService.Salary> basics = worked.stream()
                .map(ym -> salaries.on(userId, ym.atEndOfMonth()).orElse(null)).filter(java.util.Objects::nonNull).toList();
        if (basics.isEmpty()) {
            return Optional.empty();
        }
        String currency = basics.getLast().currency();
        BigDecimal base = s.base().equals("LAST_BASIC") ? basics.getLast().grossMonthly()
                : basics.stream().filter(b -> b.currency().equals(currency)).map(SalaryService.Salary::grossMonthly)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(basics.stream().filter(b -> b.currency().equals(currency)).count()), 2, RoundingMode.HALF_UP);
        Optional<YearScore> score = yearScore(userId, year);
        BigDecimal percent = score.map(YearScore::percent).orElse(s.fallbackPercent());
        BigDecimal amount = base.multiply(percent).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        if (s.prorate()) {
            amount = amount.multiply(BigDecimal.valueOf(worked.size())).divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP);
        }
        return Optional.of(new Row(userId, name, percent, score.isPresent() ? "EVALUATIONS" : "FALLBACK",
                score.map(YearScore::evaluations).orElse(0), base, currency, worked.size(),
                amount.setScale(2, RoundingMode.HALF_UP)));
    }

    /** Everyone under contract in the year, for HR's preview. */
    public List<Row> preview(int year) {
        return jdbc.sql("""
                select distinct u.id, u.first_name || ' ' || u.last_name as name, u.last_name
                from app_user u join employee_contract c on c.user_id = u.id
                where u.status = 'ACTIVE' and c.start_date <= ? and (c.end_date is null or c.end_date >= ?)
                order by u.last_name""").params(LocalDate.of(year, 12, 31), LocalDate.of(year, 1, 1))
                .query((rs, i) -> java.util.Map.entry(rs.getLong("id"), rs.getString("name"))).list().stream()
                .map(e -> forEmployee(e.getKey(), e.getValue(), year)).flatMap(Optional::stream).toList();
    }
}
