package cd.hrpay.report;

import cd.hrpay.attendance.AttendanceDays;
import cd.hrpay.attendance.DayCalculator.DayResult;
import cd.hrpay.attendance.DayCalculator.Status;
import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.leave.LeaveService;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.payment.Company;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.Contracts;
import cd.hrpay.user.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HR rankings: who is late, absent, leaves early, forgets to check out, works overtime, is often on sick leave, keeps
 * leave untaken, travels, is sanctioned, and which units lose or keep people. Each ranking compares the period with the
 * one just before it. HR sees the whole company; a unit head sees their branch for the attendance rankings only.
 * Sick leave patterns, sanctions follow-up and turnover stay with HR. Nothing here decides anything: it points HR to the
 * days behind each number.
 */
@Service
public class HrRankings {

    public static final int MAX_DAYS = 366;

    public enum Kind { LATENESS, ABSENCE, EARLY, CLOCKING, OVERTIME, SICK, LEAVE, MISSIONS, SANCTIONS, FOLLOW_UP, TURNOVER, UNITS }

    /** TEXT, NUMBER (a count), HOURS, PERCENT, MONEY, DATE. */
    public record Column(String key, String label, String type) {}

    /** A person (userId set) or a unit (unitId set). Values by column key; score orders the ranking. */
    public record Row(Long userId, Long unitId, String name, String registrationNo, String directionName, String unitName,
                      Map<String, Object> values, BigDecimal score, BigDecimal previous) {}

    public record Ranking(String kind, String title, String hint, LocalDate from, LocalDate to, LocalDate previousFrom,
                          LocalDate previousTo, boolean companyWide, boolean byUnit, String scoreLabel,
                          List<Column> columns, List<Row> rows, int total) {}

    public record KindInfo(String kind, String title, String hint, boolean period, boolean byUnit) {}

    /** One line behind a person's number: a day, a leave, a mission, a sanction. */
    public record Line(LocalDate date, String label, String value, String detail) {}

    private final AttendanceDays attendance;
    private final UserRepository users;
    private final OrgStructure org;
    private final Contracts contracts;
    private final LeaveService leave;
    private final JdbcClient jdbc;
    private final Company company;
    private final AuditService audit;

    public HrRankings(AttendanceDays attendance, UserRepository users, OrgStructure org, Contracts contracts,
                      LeaveService leave, JdbcClient jdbc, Company company, AuditService audit) {
        this.attendance = attendance;
        this.users = users;
        this.org = org;
        this.contracts = contracts;
        this.leave = leave;
        this.jdbc = jdbc;
        this.company = company;
        this.audit = audit;
    }

    // ---- who sees what -----------------------------------------------------------------------------------------

    private static boolean hr() {
        return CurrentUser.has("report.hr");
    }

    private static boolean attendanceAll() {
        return hr() || CurrentUser.has("attendance.read.all");
    }

    /** May the current person see this ranking at all? */
    public static boolean allowed(Kind kind) {
        return switch (kind) {
            case LATENESS, ABSENCE, EARLY, CLOCKING, OVERTIME, LEAVE, UNITS ->
                    attendanceAll() || CurrentUser.has("attendance.read.team");
            case MISSIONS -> hr() || CurrentUser.has("mission.manage");
            case SANCTIONS -> hr() || CurrentUser.has("discipline.manage") || CurrentUser.has("discipline.propose");
            case SICK, FOLLOW_UP, TURNOVER -> hr();
        };
    }

    /** The whole company, or only the current person's team (their branch). */
    private static boolean companyWide(Kind kind) {
        return switch (kind) {
            case MISSIONS -> hr() || CurrentUser.has("mission.manage");
            case SANCTIONS -> hr() || CurrentUser.has("discipline.manage");
            default -> attendanceAll();
        };
    }

    public List<KindInfo> kinds() {
        List<KindInfo> out = new ArrayList<>();
        for (Kind k : Kind.values()) {
            if (allowed(k)) {
                out.add(new KindInfo(k.name(), title(k), hint(k), k != Kind.LEAVE, k == Kind.TURNOVER || k == Kind.UNITS));
            }
        }
        return out;
    }

    private static String title(Kind k) {
        return switch (k) {
            case LATENESS -> Texts.t("Most lateness");
            case ABSENCE -> Texts.t("Most unjustified absences");
            case EARLY -> Texts.t("Most early departures");
            case CLOCKING -> Texts.t("Clocking discipline");
            case OVERTIME -> Texts.t("Most overtime");
            case SICK -> Texts.t("Short sick leave pattern");
            case LEAVE -> Texts.t("Leave not taken");
            case MISSIONS -> Texts.t("Most mission days");
            case SANCTIONS -> Texts.t("Most sanctions");
            case FOLLOW_UP -> Texts.t("Lateness without follow-up");
            case TURNOVER -> Texts.t("Turnover by unit");
            case UNITS -> Texts.t("Unit punctuality");
        };
    }

    private String hint(Kind k) {
        return switch (k) {
            case LATENESS -> Texts.t("Days arrived after the schedule's grace period, with the minutes late. Lateness within the grace period is not counted.");
            case ABSENCE -> Texts.t("Working days with no check-in and no approved leave, mission or holiday. Mondays and Fridays are counted apart, as is the longest run of days.");
            case EARLY -> Texts.t("Days left before the end of the schedule, beyond the grace period.");
            case CLOCKING -> Texts.t("Days without a check-out, days closed automatically, days corrected by HR and correction requests made.");
            case OVERTIME -> Texts.t("Overtime worked, approved and refused. The pay is the overtime on the payslips of the months in the period.");
            case SICK -> Texts.t("Approved sick leave in the period: the number of separate spells, the days, and the Bradford score (spells × spells × days), which grows with many short absences. The reason and the medical file are never shown.");
            case LEAVE -> Texts.t("Annual leave balances today: days available, carried over from earlier years, taken this year and requested.");
            case MISSIONS -> Texts.t("Approved and closed missions in the period: travel days away, short missions, per diem and accepted expenses.");
            case SANCTIONS -> Texts.t("Sanctions decided in the period, never older than %d months, weighted by their type (the weights are set with the sanction types). Cancelled sanctions do not count.", rankingMonths());
            case FOLLOW_UP -> Texts.t("People late on %d days or more in a month of the period, with no discipline case opened since that month began: a to-do list for HR, never an automatic sanction.", followUpDays());
            case TURNOVER -> Texts.t("Departures confirmed in the period by unit: the rate is departures for the average headcount, with the reasons and how many left within their first year.");
            case UNITS -> Texts.t("For each unit: the share of working days with lateness and with an unjustified absence, and the overtime worked.");
        };
    }

    int rankingMonths() {
        return intSetting("DISCIPLINE_RANKING_MONTHS", 24);
    }

    int followUpDays() {
        return intSetting("LATENESS_FOLLOWUP_DAYS", 4);
    }

    private int intSetting(String key, int fallback) {
        try {
            return Integer.parseInt(jdbc.sql("select value from payroll_setting where key = ?").param(key)
                    .query(String.class).optional().orElse(String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---- the period and the people -----------------------------------------------------------------------------

    private record Period(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo) {}

    private Period period(LocalDate from, LocalDate to) {
        LocalDate today = org.today();
        if (from == null || to == null) {
            throw new BusinessRuleException("Choose the start and the end of the period.");
        }
        LocalDate end = to.isAfter(today) ? today : to;
        if (end.isBefore(from)) {
            throw new BusinessRuleException("The end comes before the start.");
        }
        long days = ChronoUnit.DAYS.between(from, end) + 1;
        if (days > MAX_DAYS) {
            throw new BusinessRuleException("Choose at most %d days.".formatted(MAX_DAYS));
        }
        if (from.getDayOfMonth() == 1 && to.getDayOfMonth() == to.lengthOfMonth()) {
            // Whole months (this month, last month, a quarter, a year): compare with the same number of whole months.
            long months = ChronoUnit.MONTHS.between(from, to.plusDays(1));
            return new Period(from, end, from.minusMonths(months), from.minusDays(1));
        }
        return new Period(from, end, from.minusDays(days), from.minusDays(1));
    }

    private record Person(AppUser user, OrgStructure.Placement where) {}

    /** The people in reach, under contract at some time in the period, in the unit's branch when one is chosen. */
    private List<Person> people(Kind kind, LocalDate from, LocalDate to, Long unitId) {
        Map<Long, OrgStructure.Placement> where = org.placementsOn(to);
        Set<Long> employed = contracts.underContract(from, to);
        Set<Long> branch = unitId == null ? null : org.branch(unitId);
        List<AppUser> list = companyWide(kind) ? users.findAll() : users.findAllById(org.teamOf(CurrentUser.id()));
        return list.stream().filter(u -> employed.contains(u.getId()))
                .filter(u -> branch == null || (where.containsKey(u.getId()) && branch.contains(where.get(u.getId()).unitId())))
                .map(u -> new Person(u, where.get(u.getId())))
                .sorted(Comparator.comparing((Person p) -> p.user().getLastName()).thenComparing(p -> p.user().fullName()))
                .toList();
    }

    private static Row row(Person p, Map<String, Object> values, BigDecimal score, BigDecimal previous) {
        return new Row(p.user().getId(), null, p.user().fullName(), p.user().getRegistrationNo(),
                p.where() == null ? null : p.where().directionName(), p.where() == null ? null : p.where().unitName(),
                values, score, previous);
    }

    private static BigDecimal n(long v) {
        return BigDecimal.valueOf(v);
    }

    private static BigDecimal hours(long minutes) {
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(long part, long whole) {
        return whole == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    // ---- rankings ----------------------------------------------------------------------------------------------

    @Transactional // the calendar may create a year's holiday dates on first use
    public Ranking ranking(Kind kind, LocalDate from, LocalDate to, Long unitId, int top, boolean byUnit) {
        if (!allowed(kind)) {
            throw new org.springframework.security.access.AccessDeniedException("Not allowed");
        }
        Period p = kind == Kind.LEAVE ? new Period(org.today(), org.today(), null, null) : period(from, to);
        List<Column> cols = new ArrayList<>();
        List<Row> rows = switch (kind) {
            case LATENESS, ABSENCE, EARLY, CLOCKING, OVERTIME -> attendanceRanking(kind, p, unitId, cols);
            case SICK -> sick(p, unitId, cols);
            case LEAVE -> leaveNotTaken(unitId, cols);
            case MISSIONS -> missions(p, unitId, cols);
            case SANCTIONS -> sanctions(p, unitId, cols);
            case FOLLOW_UP -> followUp(p, unitId, cols);
            case TURNOVER -> turnover(p, unitId, byUnit, cols);
            case UNITS -> units(p, unitId, byUnit, cols);
        };
        List<Row> ranked = rows.stream().filter(r -> r.score() != null && r.score().signum() > 0)
                .sorted(Comparator.comparing(Row::score).reversed().thenComparing(Row::name)).toList();
        if (kind == Kind.UNITS || kind == Kind.TURNOVER) {
            ranked = rows.stream().sorted(Comparator.comparing((Row r) -> r.score() == null ? BigDecimal.ZERO : r.score())
                    .reversed().thenComparing(Row::name)).toList();
        }
        int total = ranked.size();
        List<Row> shown = top > 0 && ranked.size() > top ? ranked.subList(0, top) : ranked;
        boolean units = kind == Kind.TURNOVER || kind == Kind.UNITS;
        return new Ranking(kind.name(), title(kind), hint(kind), p.from(), p.to(), p.previousFrom(), p.previousTo(),
                companyWide(kind), units && byUnit, scoreLabel(kind), cols, shown, total);
    }

    private static String scoreLabel(Kind kind) {
        return switch (kind) {
            case LATENESS -> Texts.t("Late days");
            case ABSENCE -> Texts.t("Absent days");
            case EARLY -> Texts.t("Early departures");
            case CLOCKING -> Texts.t("Clocking issues");
            case OVERTIME -> Texts.t("Overtime hours");
            case SICK -> Texts.t("Bradford score");
            case LEAVE -> Texts.t("Days available");
            case MISSIONS -> Texts.t("Days away");
            case SANCTIONS -> Texts.t("Sanction score");
            case FOLLOW_UP -> Texts.t("Late days (worst month)");
            case TURNOVER -> Texts.t("Departure rate %");
            case UNITS -> Texts.t("Late days %");
        };
    }

    /** What one person's days give for a ranking: the score and the columns. */
    private record Tally(BigDecimal score, Map<String, Object> values) {}

    private List<Row> attendanceRanking(Kind kind, Period p, Long unitId, List<Column> cols) {
        switch (kind) {
            case LATENESS -> {
                cols.add(new Column("lateMinutes", Texts.t("Minutes late"), "NUMBER"));
                cols.add(new Column("averageMinutes", Texts.t("Average minutes"), "NUMBER"));
                cols.add(new Column("worstMinutes", Texts.t("Worst day (minutes)"), "NUMBER"));
                cols.add(new Column("deductedMinutes", Texts.t("Minutes deducted"), "NUMBER"));
            }
            case ABSENCE -> {
                cols.add(new Column("longestRun", Texts.t("Longest run (days)"), "NUMBER"));
                cols.add(new Column("mondaysFridays", Texts.t("Mondays and Fridays"), "NUMBER"));
                cols.add(new Column("lastAbsence", Texts.t("Last absence"), "DATE"));
            }
            case EARLY -> {
                cols.add(new Column("earlyMinutes", Texts.t("Minutes"), "NUMBER"));
                cols.add(new Column("deductedMinutes", Texts.t("Minutes deducted"), "NUMBER"));
            }
            case CLOCKING -> {
                cols.add(new Column("noCheckOut", Texts.t("No check-out"), "NUMBER"));
                cols.add(new Column("autoClosed", Texts.t("Closed automatically"), "NUMBER"));
                cols.add(new Column("corrected", Texts.t("Corrected by HR"), "NUMBER"));
                cols.add(new Column("requests", Texts.t("Correction requests"), "NUMBER"));
            }
            case OVERTIME -> {
                cols.add(new Column("approvedHours", Texts.t("Approved hours"), "HOURS"));
                cols.add(new Column("refusedHours", Texts.t("Refused hours"), "HOURS"));
                cols.add(new Column("worstWeekHours", Texts.t("Highest week (hours)"), "HOURS"));
                if (CurrentUser.has("salary.read") || CurrentUser.has("payslip.read.all")) {
                    cols.add(new Column("pay", Texts.t("Overtime pay (%s)", company.localCurrency()), "MONEY"));
                }
            }
            default -> throw new IllegalArgumentException(kind.name());
        }
        Map<Long, Integer> requests = kind == Kind.CLOCKING ? correctionRequests(p) : Map.of();
        Map<Long, Integer[]> decisions = kind == Kind.OVERTIME ? overtimeDecisions(p.from(), p.to()) : Map.of();
        Map<Long, BigDecimal> pay = kind == Kind.OVERTIME ? overtimePay(p.from(), p.to()) : Map.of();
        Map<Long, Integer> earlier = kind == Kind.CLOCKING
                ? correctionRequests(new Period(p.previousFrom(), p.previousTo(), null, null)) : Map.of();
        List<Row> out = new ArrayList<>();
        for (Person person : people(kind, p.previousFrom(), p.to(), unitId)) {
            List<AttendanceDays.Graced> all = attendance.withGrace(person.user(), p.previousFrom(), p.to());
            List<AttendanceDays.Graced> now = all.stream().filter(g -> !g.day().date().isBefore(p.from())).toList();
            List<AttendanceDays.Graced> before = all.stream().filter(g -> g.day().date().isBefore(p.from())).toList();
            long id = person.user().getId();
            Tally t = tally(kind, now, requests.getOrDefault(id, 0), decisions.get(id), pay.get(id));
            Tally prev = tally(kind, before, 0, null, null);
            out.add(row(person, t.values(), t.score(), prev.score().add(n(earlier.getOrDefault(id, 0)))));
        }
        return out;
    }

    private Tally tally(Kind kind, List<AttendanceDays.Graced> days, int requests, Integer[] decision, BigDecimal pay) {
        Map<String, Object> v = new LinkedHashMap<>();
        switch (kind) {
            case LATENESS -> {
                List<AttendanceDays.Graced> late = days.stream().filter(HrRankings::late).toList();
                long minutes = late.stream().mapToLong(g -> g.day().lateMinutes()).sum();
                v.put("lateMinutes", minutes);
                v.put("averageMinutes", late.isEmpty() ? 0 : Math.round((double) minutes / late.size()));
                v.put("worstMinutes", late.stream().mapToInt(g -> g.day().lateMinutes()).max().orElse(0));
                v.put("deductedMinutes", late.stream().mapToLong(g -> g.day().lateDeductedMinutes()).sum());
                return new Tally(n(late.size()), v);
            }
            case ABSENCE -> {
                int count = 0;
                int run = 0;
                int longest = 0;
                int edges = 0;
                LocalDate last = null;
                for (AttendanceDays.Graced g : days) {
                    DayResult d = g.day();
                    if (absent(d)) {
                        count++;
                        run++;
                        longest = Math.max(longest, run);
                        last = d.date();
                        if (d.date().getDayOfWeek() == DayOfWeek.MONDAY || d.date().getDayOfWeek() == DayOfWeek.FRIDAY) {
                            edges++;
                        }
                    } else if (d.scheduledMinutes() > 0) {   // a rest day or a holiday does not break the run
                        run = 0;
                    }
                }
                v.put("longestRun", longest);
                v.put("mondaysFridays", edges);
                v.put("lastAbsence", last);
                return new Tally(n(count), v);
            }
            case EARLY -> {
                List<AttendanceDays.Graced> early = days.stream().filter(HrRankings::early).toList();
                v.put("earlyMinutes", early.stream().mapToLong(g -> g.day().earlyMinutes()).sum());
                v.put("deductedMinutes", early.stream().mapToLong(g -> g.day().earlyDeductedMinutes()).sum());
                return new Tally(n(early.size()), v);
            }
            case CLOCKING -> {
                long noOut = days.stream().filter(g -> g.day().missingCheckOut()).count();
                long auto = days.stream().filter(g -> g.day().autoClosed()).count();
                long corrected = days.stream().filter(g -> g.day().corrected()).count();
                v.put("noCheckOut", noOut);
                v.put("autoClosed", auto);
                v.put("corrected", corrected);
                v.put("requests", requests);
                return new Tally(n(noOut + auto + corrected + requests), v);
            }
            case OVERTIME -> {
                long worked = days.stream().mapToLong(g -> g.day().overtimeMinutes()).sum();
                Map<Object, Long> weeks = days.stream().collect(Collectors.groupingBy(
                        g -> g.day().date().get(IsoFields.WEEK_BASED_YEAR) * 100 + g.day().date().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
                        Collectors.summingLong(g -> g.day().overtimeMinutes())));
                v.put("approvedHours", decision == null ? BigDecimal.ZERO : hours(decision[0]));
                v.put("refusedHours", decision == null ? BigDecimal.ZERO : hours(decision[1]));
                v.put("worstWeekHours", hours(weeks.values().stream().mapToLong(Long::longValue).max().orElse(0)));
                v.put("pay", pay == null ? BigDecimal.ZERO : pay);
                return new Tally(hours(worked), v);
            }
            default -> throw new IllegalArgumentException(kind.name());
        }
    }

    static boolean late(AttendanceDays.Graced g) {
        return g.day().lateMinutes() > g.graceMinutes();
    }

    static boolean early(AttendanceDays.Graced g) {
        return g.day().earlyMinutes() > g.graceMinutes() && !g.day().missingCheckOut();
    }

    static boolean absent(DayResult d) {
        return d.status() == Status.ABSENT && !d.mission();
    }

    private Map<Long, Integer> correctionRequests(Period p) {
        Map<Long, Integer> out = new HashMap<>();
        jdbc.sql("select user_id, count(*) from correction_request where work_date between ? and ? group by user_id")
                .params(p.from(), p.to()).query((rs, i) -> out.put(rs.getLong(1), rs.getInt(2))).list();
        return out;
    }

    /** Approved and refused overtime minutes by person. */
    private Map<Long, Integer[]> overtimeDecisions(LocalDate from, LocalDate to) {
        Map<Long, Integer[]> out = new HashMap<>();
        jdbc.sql("""
                select user_id, sum(approved_minutes), sum(computed_minutes - approved_minutes)
                from overtime_decision where work_date between ? and ? group by user_id""")
                .params(from, to).query((rs, i) -> out.put(rs.getLong(1), new Integer[] {rs.getInt(2), rs.getInt(3)})).list();
        return out;
    }

    /** Overtime on the payslips of the months that start in the period, in the local currency. */
    private Map<Long, BigDecimal> overtimePay(LocalDate from, LocalDate to) {
        String local = company.localCurrency();
        Map<Long, BigDecimal> out = new HashMap<>();
        jdbc.sql("""
                select s.user_id, s.currency, r.usd_cdf, sum(l.amount)
                from payslip_line l join payslip s on s.id = l.payslip_id join payroll_run r on r.id = s.run_id
                where l.element_code = 'OVERTIME' and r.month between ? and ?
                group by s.user_id, s.currency, r.usd_cdf""")
                .params(from.withDayOfMonth(1), to).query((rs, i) -> {
                    BigDecimal amount = rs.getBigDecimal(4);
                    BigDecimal rate = rs.getBigDecimal(3);
                    if (!local.equals(rs.getString(2))) {
                        if (rate == null || !"USD".equals(rs.getString(2))) {
                            return null;
                        }
                        amount = amount.multiply(rate);
                    }
                    out.merge(rs.getLong(1), amount.setScale(2, RoundingMode.HALF_UP), BigDecimal::add);
                    return null;
                }).list();
        return out;
    }

    private List<Row> sick(Period p, Long unitId, List<Column> cols) {
        cols.add(new Column("spells", Texts.t("Spells"), "NUMBER"));
        cols.add(new Column("days", Texts.t("Days"), "NUMBER"));
        cols.add(new Column("lastSpell", Texts.t("Last spell"), "DATE"));
        record Spell(long userId, LocalDate start, BigDecimal days) {}
        List<Spell> spells = jdbc.sql("""
                select r.user_id, r.start_date, r.working_days from leave_request r join leave_type t on t.id = r.leave_type_id
                where t.code = 'SICK' and r.status = 'APPROVED' and r.start_date <= ? and r.end_date >= ?""")
                .params(p.to(), p.previousFrom()).query(Spell.class).list();
        List<Row> out = new ArrayList<>();
        for (Person person : people(Kind.SICK, p.from(), p.to(), unitId)) {
            long id = person.user().getId();
            List<Spell> mine = spells.stream().filter(s -> s.userId() == id && !s.start().isBefore(p.from())).toList();
            List<Spell> earlier = spells.stream().filter(s -> s.userId() == id && s.start().isBefore(p.from())).toList();
            BigDecimal days = mine.stream().map(Spell::days).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal daysBefore = earlier.stream().map(Spell::days).reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("spells", mine.size());
            v.put("days", days.stripTrailingZeros());
            v.put("lastSpell", mine.stream().map(Spell::start).max(Comparator.naturalOrder()).orElse(null));
            out.add(row(person, v, bradford(mine.size(), days), bradford(earlier.size(), daysBefore)));
        }
        return out;
    }

    static BigDecimal bradford(int spells, BigDecimal days) {
        return days.multiply(BigDecimal.valueOf((long) spells * spells)).setScale(0, RoundingMode.HALF_UP);
    }

    private List<Row> leaveNotTaken(Long unitId, List<Column> cols) {
        cols.add(new Column("carried", Texts.t("Carried over"), "NUMBER"));
        cols.add(new Column("granted", Texts.t("Granted this year"), "NUMBER"));
        cols.add(new Column("taken", Texts.t("Taken this year"), "NUMBER"));
        cols.add(new Column("pending", Texts.t("Requested"), "NUMBER"));
        LocalDate today = org.today();
        List<Row> out = new ArrayList<>();
        for (Person person : people(Kind.LEAVE, today, today, unitId)) {
            LeaveService.AnnualSummary s = leave.annualSummary(person.user().getId());
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("carried", s.carriedOver());
            v.put("granted", s.grantedThisYear());
            v.put("taken", s.takenThisYear());
            v.put("pending", s.pending());
            out.add(row(person, v, s.available(), null));
        }
        return out;
    }

    private List<Row> missions(Period p, Long unitId, List<Column> cols) {
        cols.add(new Column("travels", Texts.t("Travel missions"), "NUMBER"));
        cols.add(new Column("short", Texts.t("Short missions"), "NUMBER"));
        cols.add(new Column("perDiem", Texts.t("Per diem"), "TEXT"));
        cols.add(new Column("expenses", Texts.t("Accepted expenses"), "TEXT"));
        record M(long id, long userId, String kind, LocalDate start, LocalDate end, BigDecimal rate, String currency) {}
        List<M> all = jdbc.sql("""
                select id, user_id, kind, work_date, coalesce(end_date, work_date), per_diem_rate, per_diem_currency from mission
                where status in ('APPROVED', 'CLOSED') and work_date <= ? and coalesce(end_date, work_date) >= ?""")
                .params(p.to(), p.previousFrom()).query(M.class).list();
        Map<Long, Map<String, BigDecimal>> expenses = new HashMap<>();
        jdbc.sql("""
                select m.user_id, e.currency, sum(e.amount) from mission_expense e join mission m on m.id = e.mission_id
                where e.status = 'ACCEPTED' and m.status in ('APPROVED', 'CLOSED') and m.work_date between ? and ?
                group by m.user_id, e.currency""").params(p.from(), p.to())
                .query((rs, i) -> expenses.computeIfAbsent(rs.getLong(1), k -> new TreeMap<>())
                        .put(rs.getString(2), rs.getBigDecimal(3))).list();
        List<Row> out = new ArrayList<>();
        for (Person person : people(Kind.MISSIONS, p.from(), p.to(), unitId)) {
            long id = person.user().getId();
            long away = 0;
            long awayBefore = 0;
            int travels = 0;
            int shortOnes = 0;
            Map<String, BigDecimal> perDiem = new TreeMap<>();
            for (M m : all) {
                if (m.userId() != id) {
                    continue;
                }
                long now = overlap(m.start(), m.end(), p.from(), p.to());
                long before = overlap(m.start(), m.end(), p.previousFrom(), p.previousTo());
                if ("TRAVEL".equals(m.kind())) {
                    away += now;
                    awayBefore += before;
                    if (now > 0) {
                        travels++;
                        if (m.rate() != null && m.currency() != null) {
                            perDiem.merge(m.currency(), m.rate().multiply(BigDecimal.valueOf(now)), BigDecimal::add);
                        }
                    }
                } else if (now > 0) {
                    shortOnes++;
                }
            }
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("travels", travels);
            v.put("short", shortOnes);
            v.put("perDiem", amounts(perDiem));
            v.put("expenses", amounts(expenses.getOrDefault(id, Map.of())));
            out.add(row(person, v, n(away + shortOnes), n(awayBefore)));
        }
        return out;
    }

    static long overlap(LocalDate start, LocalDate end, LocalDate from, LocalDate to) {
        LocalDate a = start.isAfter(from) ? start : from;
        LocalDate b = end.isBefore(to) ? end : to;
        return b.isBefore(a) ? 0 : ChronoUnit.DAYS.between(a, b) + 1;
    }

    private static String amounts(Map<String, BigDecimal> byCurrency) {
        return byCurrency.entrySet().stream().filter(e -> e.getValue().signum() != 0)
                .map(e -> e.getKey() + " " + e.getValue().setScale(2, RoundingMode.HALF_UP).toPlainString())
                .collect(Collectors.joining(" · "));
    }

    private record Sanction(long userId, String code, String name, int weight, LocalDate decidedOn) {}

    /** Sanctions that count: decided (and not cancelled) on or after the oldest day still counted. */
    private List<Sanction> sanctionsBetween(LocalDate from, LocalDate to) {
        LocalDate oldest = org.today().minusMonths(rankingMonths());
        LocalDate start = from.isBefore(oldest) ? oldest : from;
        return jdbc.sql("""
                select c.user_id, t.code, t.name, t.weight, c.decided_on from discipline_case c
                join sanction_type t on t.id = c.sanction_type_id
                where c.status in ('DECIDED', 'NOTIFIED') and c.decided_on between ? and ?""")
                .params(start, to).query(Sanction.class).list();
    }

    private List<Row> sanctions(Period p, Long unitId, List<Column> cols) {
        record T(String code, String name) {}
        List<T> types = jdbc.sql("select code, name from sanction_type where active order by position, id").query(T.class).list();
        for (T t : types) {
            cols.add(new Column("type_" + t.code(), t.name(), "NUMBER"));
        }
        cols.add(new Column("count", Texts.t("Sanctions"), "NUMBER"));
        cols.add(new Column("open", Texts.t("Cases in progress"), "NUMBER"));
        cols.add(new Column("last", Texts.t("Last sanction"), "DATE"));
        List<Sanction> now = sanctionsBetween(p.from(), p.to());
        List<Sanction> before = sanctionsBetween(p.previousFrom(), p.previousTo());
        Map<Long, Integer> open = new HashMap<>();
        jdbc.sql("""
                select user_id, count(*) from discipline_case where status in ('PROPOSED', 'OPEN', 'HEARING', 'HEARD')
                group by user_id""").query((rs, i) -> open.put(rs.getLong(1), rs.getInt(2))).list();
        List<Row> out = new ArrayList<>();
        for (Person person : people(Kind.SANCTIONS, p.from(), p.to(), unitId)) {
            long id = person.user().getId();
            List<Sanction> mine = now.stream().filter(s -> s.userId() == id).toList();
            Map<String, Object> v = new LinkedHashMap<>();
            for (T t : types) {
                v.put("type_" + t.code(), mine.stream().filter(s -> s.code().equals(t.code())).count());
            }
            v.put("count", mine.size());
            v.put("open", open.getOrDefault(id, 0));
            v.put("last", mine.stream().map(Sanction::decidedOn).max(Comparator.naturalOrder()).orElse(null));
            int score = mine.stream().mapToInt(Sanction::weight).sum();
            int earlier = before.stream().filter(s -> s.userId() == id).mapToInt(Sanction::weight).sum();
            // Someone with an open case and no sanction still shows, at the bottom.
            BigDecimal shown = score == 0 && open.getOrDefault(id, 0) > 0 ? new BigDecimal("0.1") : n(score);
            out.add(row(person, v, shown, n(earlier)));
        }
        return out;
    }

    /** The months of the period in which a person was late often enough to call for HR's follow-up. */
    public record Month(YearMonth month, int lateDays) {}

    private List<Row> followUp(Period p, Long unitId, List<Column> cols) {
        cols.add(new Column("months", Texts.t("Months over the limit"), "TEXT"));
        cols.add(new Column("lastCase", Texts.t("Last case opened"), "DATE"));
        int limit = followUpDays();
        record Opened(long userId, LocalDate on) {}
        List<Opened> cases = jdbc.sql("""
                select user_id, (opened_at at time zone 'Africa/Kinshasa')::date from discipline_case
                where status not in ('REJECTED')""").query(Opened.class).list();
        List<Row> out = new ArrayList<>();
        for (Person person : people(Kind.FOLLOW_UP, p.from(), p.to(), unitId)) {
            long id = person.user().getId();
            List<AttendanceDays.Graced> days = attendance.withGrace(person.user(), p.from(), p.to());
            Map<YearMonth, Long> lateByMonth = days.stream().filter(HrRankings::late)
                    .collect(Collectors.groupingBy(g -> YearMonth.from(g.day().date()), TreeMap::new, Collectors.counting()));
            LocalDate lastCase = cases.stream().filter(c -> c.userId() == id).map(Opened::on).max(Comparator.naturalOrder())
                    .orElse(null);
            List<Month> over = lateByMonth.entrySet().stream().filter(e -> e.getValue() >= limit)
                    .filter(e -> lastCase == null || lastCase.isBefore(e.getKey().atDay(1)))
                    .map(e -> new Month(e.getKey(), e.getValue().intValue())).toList();
            if (over.isEmpty()) {
                continue;
            }
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("months", over.stream().map(m -> Texts.t("%s: %d days", m.month().toString(), m.lateDays()))
                    .collect(Collectors.joining(" · ")));
            v.put("lastCase", lastCase);
            v.put("month", over.getLast().month().toString());
            out.add(row(person, v, n(over.stream().mapToInt(Month::lateDays).max().orElse(0)), null));
        }
        return out;
    }

    /** The rows of a unit ranking: by Direction, or by unit. */
    private record Group(long id, String name, String direction) {}

    private static Group group(OrgStructure.Placement where, boolean byUnit) {
        if (where == null) {
            return new Group(0, Texts.t("No unit"), null);
        }
        return byUnit ? new Group(where.unitId(), where.unitName(), where.directionName())
                : new Group(where.directionId(), where.directionName(), null);
    }

    private static Row unitRow(Group g, Map<String, Object> values, BigDecimal score, BigDecimal previous) {
        return new Row(null, g.id(), g.name(), null, g.direction(), null, values, score, previous);
    }

    private List<Row> turnover(Period p, Long unitId, boolean byUnit, List<Column> cols) {
        cols.add(new Column("start", Texts.t("Headcount at the start"), "NUMBER"));
        cols.add(new Column("end", Texts.t("Headcount at the end"), "NUMBER"));
        cols.add(new Column("departures", Texts.t("Departures"), "NUMBER"));
        cols.add(new Column("firstYear", Texts.t("Within the first year"), "NUMBER"));
        cols.add(new Column("reasons", Texts.t("Reasons"), "TEXT"));
        Set<Long> branch = unitId == null ? null : org.branch(unitId);
        Function<LocalDate, Map<Group, Integer>> headcount = day -> {
            Map<Long, OrgStructure.Placement> where = org.placementsOn(day);
            Map<Group, Integer> out = new HashMap<>();
            for (Long id : contracts.underContract(day)) {
                OrgStructure.Placement pl = where.get(id);
                if (branch == null || (pl != null && branch.contains(pl.unitId()))) {
                    out.merge(group(pl, byUnit), 1, Integer::sum);
                }
            }
            return out;
        };
        Map<Group, Integer> atStart = headcount.apply(p.from());
        Map<Group, Integer> atEnd = headcount.apply(p.to());
        record D(long userId, LocalDate lastDay, String reason, LocalDate firstStart) {}
        List<D> departures = jdbc.sql("""
                select d.user_id, d.last_day, d.reason,
                       (select min(c.start_date) from employee_contract c where c.user_id = d.user_id) from departure d
                where d.status = 'CONFIRMED' and d.last_day between ? and ?""")
                .params(p.previousFrom(), p.to()).query(D.class).list();
        Map<Group, List<D>> now = new HashMap<>();
        Map<Group, Integer> before = new HashMap<>();
        for (D d : departures) {
            OrgStructure.Placement pl = org.placementsOn(d.lastDay()).get(d.userId());
            if (branch != null && (pl == null || !branch.contains(pl.unitId()))) {
                continue;
            }
            Group g = group(pl, byUnit);
            if (d.lastDay().isBefore(p.from())) {
                before.merge(g, 1, Integer::sum);
            } else {
                now.computeIfAbsent(g, k -> new ArrayList<>()).add(d);
            }
        }
        Set<Group> groups = new java.util.HashSet<>(atStart.keySet());
        groups.addAll(atEnd.keySet());
        groups.addAll(now.keySet());
        List<Row> out = new ArrayList<>();
        for (Group g : groups) {
            List<D> left = now.getOrDefault(g, List.of());
            int start = atStart.getOrDefault(g, 0);
            int end = atEnd.getOrDefault(g, 0);
            double average = (start + end) / 2.0;
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("start", start);
            v.put("end", end);
            v.put("departures", left.size());
            v.put("firstYear", left.stream().filter(d -> d.firstStart() != null
                    && !d.firstStart().isBefore(d.lastDay().minusYears(1))).count());
            v.put("reasons", left.stream().collect(Collectors.groupingBy(D::reason, TreeMap::new, Collectors.counting()))
                    .entrySet().stream().map(e -> reason(e.getKey()) + " " + e.getValue()).collect(Collectors.joining(" · ")));
            BigDecimal rate = average == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(left.size() * 100.0 / average)
                    .setScale(1, RoundingMode.HALF_UP);
            BigDecimal earlier = average == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(before.getOrDefault(g, 0) * 100.0 / average)
                    .setScale(1, RoundingMode.HALF_UP);
            out.add(unitRow(g, v, rate, earlier));
        }
        return out;
    }

    private static String reason(String code) {
        return switch (code) {
            case "RESIGNATION" -> Texts.t("Resignation");
            case "DISMISSAL" -> Texts.t("Dismissal");
            case "GROSS_MISCONDUCT" -> Texts.t("Serious misconduct");
            case "REDUNDANCY" -> Texts.t("Redundancy");
            case "END_OF_CONTRACT" -> Texts.t("End of contract");
            case "END_OF_TRIAL" -> Texts.t("End of trial");
            case "MUTUAL_AGREEMENT" -> Texts.t("Mutual agreement");
            case "RETIREMENT" -> Texts.t("Retirement");
            case "DEATH" -> Texts.t("Death");
            default -> code;
        };
    }

    private List<Row> units(Period p, Long unitId, boolean byUnit, List<Column> cols) {
        cols.add(new Column("people", Texts.t("People"), "NUMBER"));
        cols.add(new Column("workingDays", Texts.t("Working days"), "NUMBER"));
        cols.add(new Column("lateDays", Texts.t("Late days"), "NUMBER"));
        cols.add(new Column("absentDays", Texts.t("Absent days"), "NUMBER"));
        cols.add(new Column("absenceRate", Texts.t("Absent days %"), "PERCENT"));
        cols.add(new Column("overtimeHours", Texts.t("Overtime hours"), "HOURS"));
        record Acc(int[] now, int[] before, long[] overtime) {}
        Map<Group, Acc> acc = new HashMap<>();
        for (Person person : people(Kind.UNITS, p.previousFrom(), p.to(), unitId)) {
            Acc a = acc.computeIfAbsent(group(person.where(), byUnit), k -> new Acc(new int[4], new int[4], new long[1]));
            boolean counted = false;
            for (AttendanceDays.Graced g : attendance.withGrace(person.user(), p.previousFrom(), p.to())) {
                DayResult d = g.day();
                boolean current = !d.date().isBefore(p.from());
                int[] t = current ? a.now() : a.before();
                if (current) {
                    a.overtime()[0] += d.overtimeMinutes();
                }
                boolean expected = d.scheduledMinutes() > 0 && d.status() != Status.LEAVE && d.status() != Status.FUTURE
                        && d.status() != Status.NOT_STARTED;
                if (!expected) {
                    continue;
                }
                if (current && !counted) {
                    a.now()[3]++;
                    counted = true;
                }
                t[0]++;
                if (late(g)) {
                    t[1]++;
                }
                if (absent(d)) {
                    t[2]++;
                }
            }
        }
        List<Row> out = new ArrayList<>();
        for (Map.Entry<Group, Acc> e : acc.entrySet()) {
            int[] now = e.getValue().now();
            int[] before = e.getValue().before();
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("people", now[3]);
            v.put("workingDays", now[0]);
            v.put("lateDays", now[1]);
            v.put("absentDays", now[2]);
            v.put("absenceRate", percent(now[2], now[0]));
            v.put("overtimeHours", hours(e.getValue().overtime()[0]));
            if (now[0] == 0 && before[0] == 0) {
                continue;
            }
            out.add(unitRow(e.getKey(), v, percent(now[1], now[0]), percent(before[1], before[0])));
        }
        return out;
    }

    // ---- what is behind one person's number --------------------------------------------------------------------

    @Transactional
    public List<Line> lines(Kind kind, long userId, LocalDate from, LocalDate to) {
        if (!allowed(kind) || kind == Kind.TURNOVER || kind == Kind.UNITS) {
            throw new org.springframework.security.access.AccessDeniedException("Not allowed");
        }
        Period p = kind == Kind.LEAVE ? new Period(org.today(), org.today(), null, null) : period(from, to);
        if (!companyWide(kind) && !org.teamOf(CurrentUser.id()).contains(userId)) {
            throw new org.springframework.security.access.AccessDeniedException("Not in your team");
        }
        AppUser user = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found."));
        List<Line> out = new ArrayList<>();
        switch (kind) {
            case LATENESS, FOLLOW_UP -> attendance.withGrace(user, p.from(), p.to()).stream().filter(HrRankings::late)
                    .forEach(g -> out.add(new Line(g.day().date(), Texts.t("Late"), Texts.t("%d min", g.day().lateMinutes()),
                            g.day().lateDeductedMinutes() > 0 ? Texts.t("%d min deducted", g.day().lateDeductedMinutes()) : null)));
            case ABSENCE -> attendance.withGrace(user, p.from(), p.to()).stream().filter(g -> absent(g.day()))
                    .forEach(g -> out.add(new Line(g.day().date(), Texts.t("Absent"), null, Texts.t("No check-in on a working day"))));
            case EARLY -> attendance.withGrace(user, p.from(), p.to()).stream().filter(HrRankings::early)
                    .forEach(g -> out.add(new Line(g.day().date(), Texts.t("Left early"), Texts.t("%d min", g.day().earlyMinutes()), null)));
            case CLOCKING -> {
                for (AttendanceDays.Graced g : attendance.withGrace(user, p.from(), p.to())) {
                    DayResult d = g.day();
                    if (d.missingCheckOut()) {
                        out.add(new Line(d.date(), Texts.t("No check-out"), null, null));
                    }
                    if (d.autoClosed()) {
                        out.add(new Line(d.date(), Texts.t("Closed automatically"), null, null));
                    }
                    if (d.corrected()) {
                        out.add(new Line(d.date(), Texts.t("Corrected by HR"), null, null));
                    }
                }
                jdbc.sql("select work_date, status from correction_request where user_id = ? and work_date between ? and ?")
                        .params(userId, p.from(), p.to())
                        .query((rs, i) -> out.add(new Line(rs.getObject(1, LocalDate.class), Texts.t("Correction request"),
                                null, rs.getString(2)))).list();
            }
            case OVERTIME -> {
                Map<LocalDate, String> decided = new HashMap<>();
                jdbc.sql("select work_date, status, approved_minutes from overtime_decision where user_id = ? and work_date between ? and ?")
                        .params(userId, p.from(), p.to()).query((rs, i) -> decided.put(rs.getObject(1, LocalDate.class),
                                ("APPROVED".equals(rs.getString(2)) ? Texts.t("Approved") : Texts.t("Refused"))
                                        + " · " + hours(rs.getInt(3)).toPlainString() + " h")).list();
                attendance.withGrace(user, p.from(), p.to()).stream().filter(g -> g.day().overtimeMinutes() > 0)
                        .forEach(g -> out.add(new Line(g.day().date(), Texts.t("Overtime"),
                                hours(g.day().overtimeMinutes()).toPlainString() + " h", decided.get(g.day().date()))));
            }
            case SICK -> jdbc.sql("""
                    select r.start_date, r.end_date, r.working_days from leave_request r join leave_type t on t.id = r.leave_type_id
                    where t.code = 'SICK' and r.status = 'APPROVED' and r.user_id = ? and r.start_date between ? and ?
                    order by r.start_date""").params(userId, p.from(), p.to())
                    .query((rs, i) -> out.add(new Line(rs.getObject(1, LocalDate.class), Texts.t("Sick leave"),
                            Texts.t("%s days", rs.getBigDecimal(3).stripTrailingZeros().toPlainString()),
                            Texts.t("Until %s", Texts.day(rs.getObject(2, LocalDate.class)))))).list();
            case LEAVE -> leave.ledger(userId).stream()
                    .filter(e -> e.entryDate() != null && e.entryDate().getYear() >= org.today().getYear() - 1)
                    .forEach(e -> out.add(new Line(e.entryDate(), e.kind(), e.days().stripTrailingZeros().toPlainString(), e.note())));
            case MISSIONS -> jdbc.sql("""
                    select work_date, coalesce(end_date, work_date), kind, coalesce(city, purpose), number from mission
                    where user_id = ? and status in ('APPROVED', 'CLOSED') and work_date <= ? and coalesce(end_date, work_date) >= ?
                    order by work_date""").params(userId, p.to(), p.from())
                    .query((rs, i) -> out.add(new Line(rs.getObject(1, LocalDate.class),
                            "TRAVEL".equals(rs.getString(3)) ? Texts.t("Travel mission") : Texts.t("Short mission"),
                            Texts.t("Until %s", Texts.day(rs.getObject(2, LocalDate.class))),
                            rs.getString(5) == null ? rs.getString(4) : rs.getString(5) + " · " + rs.getString(4)))).list();
            case SANCTIONS -> jdbc.sql("""
                    select c.decided_on, coalesce(t.name, c.status), c.number, c.facts, c.status from discipline_case c
                    left join sanction_type t on t.id = c.sanction_type_id
                    where c.user_id = ? order by c.opened_at desc""").param(userId)
                    .query((rs, i) -> out.add(new Line(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getString(3),
                            rs.getString(5) + " · " + rs.getString(4)))).list();
            default -> throw new IllegalArgumentException(kind.name());
        }
        audit.record("report.ranking.detail", "user", userId, Map.of("kind", kind.name()));
        out.sort(Comparator.comparing(Line::date, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    void viewed(Kind kind, LocalDate from, LocalDate to, Long unitId, String format) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", kind.name());
        details.put("from", String.valueOf(from));
        details.put("to", String.valueOf(to));
        details.put("unitId", unitId == null ? "" : unitId);
        details.put("format", format);
        audit.record("report.ranking", "report", kind.name(), details);
    }
}
