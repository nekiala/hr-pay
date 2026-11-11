package cd.hrpay.evaluation;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The "Individual objectives" criterion, and for the managers' grade categories (codes starting with CADRE) a sample
 * template where objectives weigh 40%, once per company: active in a company that has no evaluation yet, inactive
 * otherwise so running evaluations keep their template. Other templates keep 0% for objectives until HR adds them.
 */
@Component
public class ObjectiveDefaults {

    static final String FLAG = "OBJECTIVES_ADDED";
    static final String CODE = "OBJECTIVES";

    private record Weight(String code, int weight) {}

    private static final List<Weight> MANAGERS = List.of(new Weight("PRODUCTIVITY", 20), new Weight("QUALITY", 20),
            new Weight("TEAMWORK", 10), new Weight("PUNCTUALITY", 10), new Weight(CODE, 40));

    private final JdbcClient jdbc;

    public ObjectiveDefaults(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void addSamples() {
        if (jdbc.sql("select count(*) from payroll_setting where key = ?").param(FLAG).query(Integer.class).single() > 0) {
            return;
        }
        boolean fr = "fr".equals(jdbc.sql("select language from company where id = current_company_id()").query(String.class)
                .optional().orElse("fr"));
        jdbc.sql("""
                insert into evaluation_criterion (code, name, description, source) values (?, ?, ?, 'OBJECTIVES')
                on conflict do nothing""")
                .params(CODE, fr ? "Objectifs individuels" : "Individual objectives",
                        fr ? "Moyenne pondérée des objectifs de l'année, notés un par un."
                                : "Weighted average of the year's objectives, each scored on its own.")
                .update();
        List<long[]> categories = jdbc.sql("""
                select g.id from grade_category g
                where upper(g.code) like 'CADRE%' and g.active
                  and not exists (select 1 from evaluation_template t where t.active and t.grade_category_id = g.id
                                  and t.org_unit_id is null)
                order by g.id""").query((rs, i) -> new long[] {rs.getLong(1)}).list();
        boolean complete = jdbc.sql("select count(*) from evaluation_criterion where code in (?, ?, ?, ?, ?) and active")
                .params(MANAGERS.stream().map(Weight::code).toArray()).query(Integer.class).single() == MANAGERS.size();
        // A company already evaluating keeps its templates: the sample starts inactive there, for HR to turn on.
        boolean active = jdbc.sql("select count(*) from evaluation").query(Integer.class).single() == 0;
        if (complete) {
            for (long[] c : categories) {
                String name = jdbc.sql("select name from grade_category where id = ?").param(c[0]).query(String.class).single();
                long t = jdbc.sql("""
                        insert into evaluation_template (name, frequency, grade_category_id, active) values (?, 'SEMI_ANNUAL', ?, ?)
                        returning id""")
                        .params((fr ? "Revue avec objectifs (exemple) · " : "Review with objectives (sample) · ") + name, c[0], active)
                        .query(Long.class).single();
                for (Weight w : MANAGERS) {
                    jdbc.sql("""
                            insert into evaluation_template_criterion (template_id, criterion_id, weight)
                            select ?, id, ? from evaluation_criterion where code = ?""").params(t, w.weight(), w.code()).update();
                }
            }
        }
        jdbc.sql("insert into payroll_setting (key, value) values (?, 'true') on conflict do nothing").param(FLAG).update();
    }
}
