package cd.hrpay.payroll;

import cd.hrpay.i18n.Texts;

import cd.hrpay.audit.AuditService;
import cd.hrpay.notify.NotificationService;
import cd.hrpay.salary.SalaryService;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Permanent employees get a raise on each anniversary of their first permanent contract: the grade's base salary
 * times the grade category's percentage, added to their current basic. A daily job applies it with no approval;
 * on the 1st of each month HR gets one email listing last month's raises.
 */
@Service
public class AnniversaryRaises {

    private static final Logger log = LoggerFactory.getLogger(AnniversaryRaises.class);
    private static final ZoneId ZONE = ZoneId.of("Africa/Kinshasa");

    public record Raise(long id, long userId, String userName, LocalDate anniversaryDate, int years,
                        LocalDate effectiveFrom, String gradeCode, BigDecimal percent, BigDecimal gradeBase,
                        BigDecimal raiseAmount, BigDecimal oldBasic, BigDecimal newBasic, String currency,
                        OffsetDateTime createdAt, OffsetDateTime notifiedAt) {}

    public record Outcome(List<Raise> applied, List<String> skipped) {}

    private static final String SELECT = """
            select a.id, a.user_id, u.first_name || ' ' || u.last_name as user_name, a.anniversary_date, a.years,
                   a.effective_from, g.code as grade_code, a.percent, a.grade_base, a.raise_amount, a.old_basic,
                   a.new_basic, trim(a.currency) as currency, a.created_at, a.notified_at
            from anniversary_raise a join app_user u on u.id = a.user_id join grade g on g.id = a.grade_id
            """;

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final EmployeeFile files;
    private final MasterData masterData;
    private final SalaryService salaries;
    private final StatutoryRates settings;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Clock clock;

    public AnniversaryRaises(JdbcClient jdbc, UserRepository users, EmployeeFile files, MasterData masterData,
                             SalaryService salaries, StatutoryRates settings, NotificationService notifications,
                             AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.files = files;
        this.masterData = masterData;
        this.salaries = salaries;
        this.settings = settings;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    public List<Raise> list(int limit) {
        return jdbc.sql(SELECT + "order by a.anniversary_date desc, u.last_name limit ?").param(limit)
                .query(Raise.class).list();
    }

    /** Applies every anniversary due up to the date (and not before RAISES_FROM) that has not been applied yet. */
    @Transactional
    public Outcome applyDue(LocalDate today, Long onlyUser) {
        LocalDate from = LocalDate.parse(settings.setting("RAISES_FROM"));
        List<Raise> applied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<Map<String, Object>> employees = jdbc.sql("""
                select c.user_id, min(c.start_date) as first_permanent
                from employee_contract c join app_user u on u.id = c.user_id
                where c.contract_type = 'PERMANENT' and u.status = 'ACTIVE' and (cast(? as bigint) is null or c.user_id = ?)
                group by c.user_id""").params(onlyUser, onlyUser).query().listOfRows();
        for (Map<String, Object> row : employees) {
            long userId = ((Number) row.get("user_id")).longValue();
            LocalDate start = ((java.sql.Date) row.get("first_permanent")).toLocalDate();
            for (int years = 1; ; years++) {
                LocalDate anniversary = start.plusYears(years);
                if (anniversary.isAfter(today)) {
                    break;
                }
                if (anniversary.isBefore(from) || alreadyApplied(userId, anniversary)) {
                    continue;
                }
                var contract = files.contractOn(userId, anniversary).orElse(null);
                if (contract == null || !contract.contractType().equals("PERMANENT")) {
                    continue;   // not permanent on that day
                }
                MasterData.Grade grade = masterData.grade(contract.gradeId());
                String timing = jdbc.sql("select raise_timing from grade_category where id = ?").param(grade.categoryId())
                        .query(String.class).single();
                BigDecimal percent = masterData.raisePercent(grade.categoryId());
                LocalDate effective = timing.equals("ANNIVERSARY_MONTH") ? anniversary.withDayOfMonth(1)
                        : YearMonth.from(anniversary).plusMonths(1).atDay(1);
                AppUser user = users.findById(userId).orElseThrow();
                String name = user.getFirstName() + " " + user.getLastName();
                var current = salaries.on(userId, effective.minusDays(1)).orElse(null);
                if (current == null) {
                    skipped.add(name + ": no basic salary before " + effective + ".");
                    continue;
                }
                if (percent.signum() == 0) {
                    continue;
                }
                if (!current.currency().equals(grade.currency())) {
                    skipped.add(name + ": the basic is in " + current.currency() + " but grade " + grade.code()
                            + " is in " + grade.currency() + "; raise this salary by hand.");
                    continue;
                }
                if (salaries.history(userId).stream().anyMatch(s -> s.effectiveFrom().equals(effective))) {
                    skipped.add(name + ": a salary change already starts on " + effective + "; add the "
                            + years + "-year raise by hand.");
                    continue;
                }
                BigDecimal raise = grade.baseSalary().multiply(percent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                BigDecimal newBasic = current.grossMonthly().add(raise);
                salaries.add(userId, effective, newBasic, current.currency(),
                        "Anniversary raise, " + years + " year(s): " + percent.stripTrailingZeros().toPlainString()
                                + "% of grade " + grade.code() + " base", "system");
                long id = jdbc.sql("""
                        insert into anniversary_raise (user_id, anniversary_date, years, effective_from, grade_id, percent,
                            grade_base, raise_amount, old_basic, new_basic, currency)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                        .params(userId, anniversary, years, effective, grade.id(), percent, grade.baseSalary(), raise,
                                current.grossMonthly(), newBasic, current.currency())
                        .query(Long.class).single();
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("years", years);
                details.put("effectiveFrom", effective.toString());
                details.put("raise", raise.toPlainString());
                details.put("newBasic", newBasic.toPlainString());
                audit.recordAs("system", "salary.anniversary_raise", "user", userId, details);
                applied.add(jdbc.sql(SELECT + "where a.id = ?").param(id).query(Raise.class).single());
            }
        }
        return new Outcome(applied, skipped);
    }

    private boolean alreadyApplied(long userId, LocalDate anniversary) {
        return jdbc.sql("select count(*) from anniversary_raise where user_id = ? and anniversary_date = ?")
                .params(userId, anniversary).query(Integer.class).single() > 0;
    }

    /** Emails HR one list of the raises not yet reported. Returns how many raises it listed. */
    @Transactional
    public int sendSummary() {
        List<Raise> pending = jdbc.sql(SELECT + "where a.notified_at is null order by a.effective_from, u.last_name")
                .query(Raise.class).list();
        if (pending.isEmpty()) {
            return 0;
        }
        List<AppUser> recipients = jdbc.sql("""
                select distinct u.id from app_user u join user_role ur on ur.user_id = u.id
                join role_permission rp on rp.role_id = ur.role_id
                where rp.permission_code = 'salary.write' and u.status = 'ACTIVE'""")
                .query(Long.class).list().stream().map(id -> users.findById(id).orElseThrow()).toList();
        for (AppUser hr : recipients) {
            notifications.email(hr, () -> Texts.t("HR Pay: %s anniversary raise(s)", pending.size()), () -> {
                StringBuilder body = new StringBuilder(Texts.t("Anniversary raises applied automatically:")).append("\n\n");
                for (Raise r : pending) {
                    body.append(Texts.t("- %s: %s year(s) on %s. Basic %s -> %s %s from %s (%s%% of grade %s base %s).",
                            r.userName(), r.years(), Texts.day(r.anniversaryDate()), r.oldBasic().toPlainString(),
                            r.newBasic().toPlainString(), r.currency(), Texts.day(r.effectiveFrom()),
                            r.percent().stripTrailingZeros().toPlainString(), r.gradeCode(), r.gradeBase().toPlainString()))
                            .append("\n");
                }
                return body.append("\n").append(Texts.t("The new salaries are in each employee's salary history. A wrong raise is corrected there."))
                        .toString();
            });
        }
        jdbc.sql("update anniversary_raise set notified_at = now() where notified_at is null").update();
        return pending.size();
    }

    /** Every morning: apply today's raises. On the 1st, send HR the list of raises not yet reported. */
    private cd.hrpay.ops.JobLocks locks;

    @org.springframework.beans.factory.annotation.Autowired
    void setLocks(cd.hrpay.ops.JobLocks locks) {
        this.locks = locks;
    }

    @Scheduled(cron = "${app.raises.cron:0 30 6 * * *}", zone = "Africa/Kinshasa")
    public void daily() {
        locks.runForEachCompany("anniversary-raises", java.time.Duration.ofMinutes(30), () -> {
            LocalDate today = LocalDate.now(clock.withZone(ZONE));
            Outcome o = applyDue(today, null);
            if (!o.applied().isEmpty() || !o.skipped().isEmpty()) {
                log.info("Anniversary raises: {} applied, {} to do by hand", o.applied().size(), o.skipped().size());
            }
            if (today.getDayOfMonth() == 1) {
                sendSummary();
            }
        });
    }
}
