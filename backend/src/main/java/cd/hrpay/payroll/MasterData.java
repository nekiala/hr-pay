package cd.hrpay.payroll;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Grades and occupations. Grade categories (with leave days and the anniversary raise rate) sit with leave. */
@Service
public class MasterData {

    public record Grade(long id, long categoryId, String categoryCode, String categoryName, String code, String name,
                        BigDecimal baseSalary, String currency, boolean active, BigDecimal smigCoefficient) {}

    public record Occupation(long id, String code, String name, boolean active) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public MasterData(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public List<Grade> grades() {
        return jdbc.sql("""
                select g.id, g.category_id, c.code as category_code, c.name as category_name, g.code, g.name,
                       g.base_salary, trim(g.currency) as currency, g.active, g.smig_coefficient
                from grade g join grade_category c on c.id = g.category_id order by c.code, g.code""")
                .query(Grade.class).list();
    }

    public Grade grade(long id) {
        return grades().stream().filter(g -> g.id() == id).findFirst()
                .orElseThrow(() -> new NotFoundException("Grade not found."));
    }

    @Transactional
    public Grade saveGrade(Long id, long categoryId, String code, String name, BigDecimal base, String currency,
                           boolean active) {
        return saveGrade(id, categoryId, code, name, base, currency, active, null);
    }

    /** smigCoefficient: the grade's coefficient on the SMIG (DRC Décret 25/22 annex 2, 100 for the lowest), or null. */
    @Transactional
    public Grade saveGrade(Long id, long categoryId, String code, String name, BigDecimal base, String currency,
                           boolean active, BigDecimal smigCoefficient) {
        if (id == null) {
            if (jdbc.sql("select count(*) from grade where upper(code) = upper(?)").param(code).query(Integer.class).single() > 0) {
                throw new ConflictException("The grade code " + code + " is already used.");
            }
            id = jdbc.sql("insert into grade (category_id, code, name, base_salary, currency, active, smig_coefficient) values (?, ?, ?, ?, ?, ?, ?) returning id")
                    .params(categoryId, code.toUpperCase(), name, base, currency, active, smigCoefficient).query(Long.class).single();
        } else if (jdbc.sql("update grade set category_id = ?, name = ?, base_salary = ?, currency = ?, active = ?, smig_coefficient = ? where id = ?")
                .params(categoryId, name, base, currency, active, smigCoefficient, id).update() == 0) {
            throw new NotFoundException("Grade not found.");
        }
        audit.record("grade.saved", "grade", id, Map.of("code", code, "baseSalary", base.toPlainString(),
                "currency", currency));
        return grade(id);
    }

    @Transactional
    public void setRaisePercent(long categoryId, BigDecimal percent) {
        if (jdbc.sql("update grade_category set anniversary_raise_percent = ? where id = ?").params(percent, categoryId).update() == 0) {
            throw new NotFoundException("Grade category not found.");
        }
        audit.record("grade_category.raise_percent", "grade_category", categoryId, Map.of("percent", percent));
    }

    @Transactional
    public void setRaiseTiming(long categoryId, String timing) {
        if (jdbc.sql("update grade_category set raise_timing = ? where id = ?").params(timing, categoryId).update() == 0) {
            throw new NotFoundException("Grade category not found.");
        }
        audit.record("grade_category.raise_timing", "grade_category", categoryId, Map.of("timing", timing));
    }

    /** The category's notice: base (days or months), days per full year of service, day kind, share owed on resignation. */
    @Transactional
    public void setNotice(long categoryId, BigDecimal base, String unit, BigDecimal perYear, String dayKind, BigDecimal share) {
        if ("MONTHS".equals(unit) && base.stripTrailingZeros().scale() > 0) {
            throw new cd.hrpay.common.BusinessRuleException("Give the months as a whole number.");
        }
        if (jdbc.sql("""
                update grade_category set notice_base = ?, notice_base_unit = ?, notice_days_per_year = ?, notice_day_kind = ?,
                notice_resignation_share = ? where id = ?""").params(base, unit, perYear, dayKind, share, categoryId).update() == 0) {
            throw new NotFoundException("Grade category not found.");
        }
        audit.record("grade_category.notice", "grade_category", categoryId, Map.of("base", base.toPlainString(), "unit", unit,
                "perYear", perYear.toPlainString(), "dayKind", dayKind, "resignationShare", share.toPlainString()));
    }

    /** The category's pay period: the calendar month, or three decades starting on the 1st, 11th or 21st. */
    @Transactional
    public void setPayPeriod(long categoryId, String kind, int firstDecadeDay) {
        int day = PayPeriods.MONTH.equals(kind) ? 1 : firstDecadeDay;
        if (jdbc.sql("update grade_category set pay_period = ?, first_decade_day = ? where id = ?")
                .params(kind, day, categoryId).update() == 0) {
            throw new NotFoundException("Grade category not found.");
        }
        audit.record("grade_category.pay_period", "grade_category", categoryId, Map.of("kind", kind, "firstDecadeDay", day));
    }

    public BigDecimal raisePercent(long categoryId) {
        return jdbc.sql("select anniversary_raise_percent from grade_category where id = ?").param(categoryId)
                .query(BigDecimal.class).single();
    }

    public List<Occupation> occupations() {
        return jdbc.sql("select id, code, name, active from occupation order by name").query(Occupation.class).list();
    }

    @Transactional
    public Occupation saveOccupation(Long id, String code, String name, boolean active) {
        if (id == null) {
            if (jdbc.sql("select count(*) from occupation where upper(code) = upper(?)").param(code).query(Integer.class).single() > 0) {
                throw new ConflictException("The occupation code " + code + " is already used.");
            }
            id = jdbc.sql("insert into occupation (code, name, active) values (?, ?, ?) returning id")
                    .params(code.toUpperCase(), name, active).query(Long.class).single();
        } else if (jdbc.sql("update occupation set name = ?, active = ? where id = ?").params(name, active, id).update() == 0) {
            throw new NotFoundException("Occupation not found.");
        }
        audit.record("occupation.saved", "occupation", id, Map.of("code", code, "name", name));
        long saved = id;
        return occupations().stream().filter(o -> o.id() == saved).findFirst().orElseThrow();
    }
}
