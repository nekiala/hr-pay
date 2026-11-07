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
                        BigDecimal baseSalary, String currency, boolean active) {}

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
                       g.base_salary, trim(g.currency) as currency, g.active
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
        if (id == null) {
            if (jdbc.sql("select count(*) from grade where upper(code) = upper(?)").param(code).query(Integer.class).single() > 0) {
                throw new ConflictException("The grade code " + code + " is already used.");
            }
            id = jdbc.sql("insert into grade (category_id, code, name, base_salary, currency, active) values (?, ?, ?, ?, ?, ?) returning id")
                    .params(categoryId, code.toUpperCase(), name, base, currency, active).query(Long.class).single();
        } else if (jdbc.sql("update grade set category_id = ?, name = ?, base_salary = ?, currency = ?, active = ? where id = ?")
                .params(categoryId, name, base, currency, active, id).update() == 0) {
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
