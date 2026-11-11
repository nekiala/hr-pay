package cd.hrpay.mission;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.Notifications;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.Contracts;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Travel missions: whole days away, in the country or abroad. Requested by the employee or created by HR, approved by
 * the unit head and, for international missions (or as the company sets), by HR. Once approved the mission carries a
 * numbered ordre de mission, its days are worked and paid without a check-in ({@link MissionDays}), and the money is
 * handled outside the payroll: an advance before departure, the report and receipts after the return, then the
 * balance either way. Only a per diem above the company's scale reaches the payslip (MISSION_EXCESS), as pay.
 */
@Service
public class TravelMissions {

    public static final String EXCESS_ELEMENT = "MISSION_EXCESS";
    static final Set<String> TRANSPORTS = Set.of("PLANE", "ROAD", "BOAT", "TRAIN", "COMPANY_CAR", "OTHER");
    static final Set<String> COSTS_BY = Set.of("COMPANY", "HOST", "SPONSOR", "SHARED");
    static final Set<String> ZONES = Set.of("DOMESTIC", "AFRICA", "WORLD");
    static final Set<String> EXPENSE_CATEGORIES = Set.of("TRANSPORT", "LODGING", "MEALS", "VISA", "OTHER");
    static final Set<String> CURRENCIES = Set.of("USD", "CDF", "XAF");
    /** African countries (ISO 3166-1), for the per diem zone of an international mission. */
    static final Set<String> AFRICA = Set.of("DZ", "AO", "BJ", "BW", "BF", "BI", "CV", "CM", "CF", "TD", "KM", "CG", "CD",
            "CI", "DJ", "EG", "GQ", "ER", "SZ", "ET", "GA", "GM", "GH", "GN", "GW", "KE", "LS", "LR", "LY", "MG", "MW", "ML",
            "MR", "MU", "MA", "MZ", "NA", "NE", "NG", "RW", "ST", "SN", "SC", "SL", "SO", "ZA", "SS", "SD", "TZ", "TG", "TN",
            "UG", "ZM", "ZW", "EH", "RE", "YT", "SH");

    public record Input(List<Long> userIds, String countryCode, String city, LocalDate startDate, LocalDate endDate,
                        String purpose, String transport, String costsBy, String costsNote, Boolean lodgingByCompany,
                        BigDecimal perDiemRate) {}

    public record Amendment(String countryCode, String city, LocalDate startDate, LocalDate endDate, String purpose,
                            String transport, String costsBy, String costsNote, Boolean lodgingByCompany,
                            BigDecimal perDiemRate, String reason) {}

    public record View(long id, long userId, String employeeName, String registrationNo, Long unitId, String status,
                       String step, String scope, String countryCode, String countryName, String city,
                       LocalDate startDate, LocalDate endDate, int days, String purpose, String transport,
                       String costsBy, String costsNote, boolean lodgingByCompany, String number, int amendment,
                       String amendmentReason, String requestedBy, OffsetDateTime requestedAt, String firstApprovedBy,
                       OffsetDateTime firstApprovedAt, String decidedBy, OffsetDateTime decidedAt, String decisionNote,
                       String zone, BigDecimal perDiemScale, BigDecimal perDiemRate, BigDecimal lodgingRate,
                       String currency, boolean reportFiled, OffsetDateTime reportAt, LocalDate reportDue,
                       boolean reportOverdue, String closedBy, OffsetDateTime closedAt, boolean canDecide,
                       boolean canManage, boolean mine) {}

    public record Money(String currency, int perDiemDays, BigDecimal perDiem, int nights, BigDecimal lodging,
                        BigDecimal expensesAccepted, BigDecimal expensesWaiting, BigDecimal due, BigDecimal paid,
                        BigDecimal returned, BigDecimal balance, BigDecimal excess) {}

    public record Expense(long id, LocalDate spentOn, String category, String description, BigDecimal amount,
                          String currency, boolean hasReceipt, String receiptName, String status, String decidedBy,
                          String decisionNote, String createdBy) {}

    public record Payment(long id, String kind, String number, BigDecimal amount, String currency, String status,
                          String note, OffsetDateTime createdAt, String createdBy) {}

    public record Detail(View mission, Money money, List<Expense> expenses, List<Payment> payments, String report) {}

    public record Scale(Long id, Long categoryId, String categoryName, String zone, BigDecimal amount,
                        BigDecimal lodgingAmount, String currency) {}

    public record Settings(String transportOnTravel, String secondApproval, int reportDays) {}

    public record Receipt(String name, String type, byte[] data) {}

    /** What the public check page shows: enough to confirm the order, nothing more. */
    public record Check(String status, String number, int amendment, String company, String employeeName,
                        String destination, LocalDate startDate, LocalDate endDate) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final Clock clock;
    private final OrgStructure org;
    private final Contracts contracts;
    private final Notifications notifications;

    public TravelMissions(JdbcClient jdbc, AuditService audit, Clock clock, OrgStructure org, Contracts contracts,
                          Notifications notifications) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.org = org;
        this.contracts = contracts;
        this.notifications = notifications;
    }

    // ---------------------------------------------------------------- settings and per diem scale

    private String setting(String key, String fallback) {
        return jdbc.sql("select value from payroll_setting where key = ?").param(key).query(String.class).optional()
                .orElse(fallback);
    }

    public Settings settings() {
        int days;
        try {
            days = Integer.parseInt(setting("MISSION_REPORT_DAYS", "10"));
        } catch (NumberFormatException e) {
            days = 10;
        }
        return new Settings(setting("MISSION_TRANSPORT_ON_TRAVEL", "STOP"), setting("MISSION_SECOND_APPROVAL", "INTERNATIONAL"), days);
    }

    @Transactional
    public Settings saveSettings(Settings s) {
        if (!Set.of("STOP", "PAY").contains(s.transportOnTravel())) {
            throw new BusinessRuleException("Choose whether the transport allowance is paid on mission days.");
        }
        if (!Set.of("INTERNATIONAL", "ALL", "NONE").contains(s.secondApproval())) {
            throw new BusinessRuleException("Choose which missions HR approves after the unit head.");
        }
        if (s.reportDays() < 1 || s.reportDays() > 60) {
            throw new BusinessRuleException("The report is due 1 to 60 days after the return.");
        }
        Map<String, String> values = Map.of("MISSION_TRANSPORT_ON_TRAVEL", s.transportOnTravel(),
                "MISSION_SECOND_APPROVAL", s.secondApproval(), "MISSION_REPORT_DAYS", String.valueOf(s.reportDays()));
        values.forEach((k, v) -> jdbc.sql("""
                insert into payroll_setting (key, value) values (?, ?)
                on conflict (company_id, key) do update set value = excluded.value""").params(k, v).update());
        audit.record("mission.settings", "payroll_setting", null, values);
        return settings();
    }

    /** Whether the transport allowance stops on travel mission days. */
    public boolean transportStops() {
        return "STOP".equals(settings().transportOnTravel());
    }

    public List<Scale> scales() {
        return jdbc.sql("""
                select s.id, s.category_id, c.name as category_name, s.zone, s.amount, s.lodging_amount, trim(s.currency) as currency
                from mission_per_diem s left join grade_category c on c.id = s.category_id
                order by c.name nulls first, case s.zone when 'DOMESTIC' then 1 when 'AFRICA' then 2 else 3 end""")
                .query(Scale.class).list();
    }

    @Transactional
    public Scale saveScale(Long id, Long categoryId, String zone, BigDecimal amount, BigDecimal lodging, String currency) {
        if (!ZONES.contains(zone)) {
            throw new BusinessRuleException("Choose the zone: in the country, Africa or the rest of the world.");
        }
        if (!CURRENCIES.contains(currency)) {
            throw new BusinessRuleException("Choose the currency: USD, CDF or XAF.");
        }
        if (amount == null || amount.signum() < 0 || (lodging != null && lodging.signum() < 0)) {
            throw new BusinessRuleException("Enter amounts of zero or more.");
        }
        BigDecimal night = lodging == null ? BigDecimal.ZERO : lodging;
        long saved;
        try {
            saved = id == null
                    ? jdbc.sql("""
                            insert into mission_per_diem (category_id, zone, amount, lodging_amount, currency, updated_by)
                            values (?, ?, ?, ?, ?, ?) returning id""")
                            .params(categoryId, zone, amount, night, currency, CurrentUser.email()).query(Long.class).single()
                    : jdbc.sql("""
                            update mission_per_diem set category_id = ?, zone = ?, amount = ?, lodging_amount = ?, currency = ?,
                                   updated_by = ?, updated_at = now() where id = ? returning id""")
                            .params(categoryId, zone, amount, night, currency, CurrentUser.email(), id).query(Long.class)
                            .optional().orElseThrow(() -> new NotFoundException("Per diem rate not found."));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ConflictException("There is already a rate for this category and zone.");
        }
        audit.record("mission.per_diem", "mission_per_diem", saved, Map.of("zone", zone, "amount", amount,
                "lodging", night, "currency", currency, "categoryId", categoryId == null ? "all" : categoryId));
        return scales().stream().filter(s -> s.id() == saved).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteScale(long id) {
        if (jdbc.sql("delete from mission_per_diem where id = ?").param(id).update() == 0) {
            throw new NotFoundException("Per diem rate not found.");
        }
        audit.record("mission.per_diem_deleted", "mission_per_diem", id, Map.of());
    }

    static String zoneOf(String country, String home) {
        return country.equals(home) ? "DOMESTIC" : AFRICA.contains(country) ? "AFRICA" : "WORLD";
    }

    private Long categoryOf(long userId, LocalDate on) {
        return jdbc.sql("""
                select coalesce((select g.category_id from employee_contract c join grade g on g.id = c.grade_id
                                 where c.user_id = ? and c.start_date <= ? and (c.end_date is null or c.end_date >= ?)
                                 order by c.start_date desc limit 1),
                                (select grade_category_id from app_user where id = ?))""")
                .params(userId, on, on, userId).query(Long.class).optional().orElse(null);
    }

    private record Rate(BigDecimal amount, BigDecimal lodging, String currency) {}

    private Rate rateFor(long userId, LocalDate on, String zone) {
        Long category = categoryOf(userId, on);
        return jdbc.sql("""
                select amount, lodging_amount as lodging, trim(currency) as currency from mission_per_diem
                where zone = ? and (category_id = ? or category_id is null)
                order by category_id nulls last limit 1""").params(zone, category).query(Rate.class).optional().orElse(null);
    }

    private String home() {
        return jdbc.sql("select country_code from company where id = current_company_id()").query(String.class)
                .optional().orElse("CD");
    }

    private String homeCurrency() {
        return "CG".equals(home()) || "CM".equals(home()) || "GA".equals(home()) ? "XAF" : "USD";
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneId.of("Africa/Kinshasa")));
    }

    // ---------------------------------------------------------------- reading

    private static final String SELECT = """
            select m.id, m.user_id, person_name(u.first_name, u.middle_name, u.last_name) as employee_name, u.registration_no,
                   m.status, m.scope, m.country_code, case when c.language = 'en' then k.name_en else k.name_fr end as country_name,
                   m.city, m.work_date as start_date, m.end_date, m.purpose, m.transport, m.costs_by, m.costs_note,
                   m.lodging_by_company, m.number, m.amendment, m.amendment_reason, m.requested_by, m.requested_at,
                   m.first_approved_by, m.first_approved_at, m.decided_by, m.decided_at, m.decision_note, m.zone,
                   m.per_diem_scale, m.per_diem_rate, m.lodging_rate, trim(m.per_diem_currency) as currency,
                   m.report is not null as report_filed, m.report_at, m.closed_by, m.closed_at
            from mission m join app_user u on u.id = m.user_id join country k on k.code = m.country_code
            join company c on c.id = m.company_id
            where m.kind = 'TRAVEL'""";

    public record Row(long id, long userId, String employeeName, String registrationNo, String status, String scope,
                       String countryCode, String countryName, String city, LocalDate startDate, LocalDate endDate,
                       String purpose, String transport, String costsBy, String costsNote, boolean lodgingByCompany,
                       String number, int amendment, String amendmentReason, String requestedBy,
                       OffsetDateTime requestedAt, String firstApprovedBy, OffsetDateTime firstApprovedAt,
                       String decidedBy, OffsetDateTime decidedAt, String decisionNote, String zone,
                       BigDecimal perDiemScale, BigDecimal perDiemRate, BigDecimal lodgingRate, String currency,
                       boolean reportFiled, OffsetDateTime reportAt, String closedBy, OffsetDateTime closedAt) {}

    private boolean needsSecond(Row r) {
        String rule = settings().secondApproval();
        return "ALL".equals(rule) || ("INTERNATIONAL".equals(rule) && "INTERNATIONAL".equals(r.scope()));
    }

    /** Who decides next: MANAGER (the unit head or HR), HR (the second approval), or nobody. */
    private String step(Row r) {
        if (!"PENDING".equals(r.status())) {
            return null;
        }
        return r.firstApprovedBy() == null ? "MANAGER" : "HR";
    }

    private boolean canDecide(Row r) {
        Long me = CurrentUser.id();
        if (me == null || r.userId() == me || !"PENDING".equals(r.status())) {
            return false;
        }
        if ("HR".equals(step(r))) {
            return CurrentUser.has("mission.approve.all") && !CurrentUser.email().equalsIgnoreCase(r.firstApprovedBy());
        }
        return CurrentUser.has("mission.approve.all")
                || (CurrentUser.has("mission.approve.team") && org.inTeam(me, r.userId()));
    }

    private boolean canSee(Row r) {
        Long me = CurrentUser.id();
        return (me != null && r.userId() == me) || CurrentUser.has("mission.manage") || CurrentUser.has("mission.approve.all")
                || (CurrentUser.has("mission.approve.team") && me != null && org.inTeam(me, r.userId()));
    }

    private View view(Row r, Map<Long, Long> units) {
        int reportDays = settings().reportDays();
        LocalDate due = r.endDate().plusDays(reportDays);
        boolean active = "APPROVED".equals(r.status());
        Long me = CurrentUser.id();
        return new View(r.id(), r.userId(), r.employeeName(), r.registrationNo(), units.get(r.userId()), r.status(),
                step(r), r.scope(), r.countryCode(), r.countryName(), r.city(), r.startDate(), r.endDate(),
                (int) ChronoUnit.DAYS.between(r.startDate(), r.endDate()) + 1, r.purpose(), r.transport(), r.costsBy(),
                r.costsNote(), r.lodgingByCompany(), r.number(), r.amendment(), r.amendmentReason(), r.requestedBy(),
                r.requestedAt(), r.firstApprovedBy(), r.firstApprovedAt(), r.decidedBy(), r.decidedAt(), r.decisionNote(),
                r.zone(), r.perDiemScale(), r.perDiemRate(), r.lodgingRate(), r.currency(), r.reportFiled(), r.reportAt(),
                due, active && !r.reportFiled() && today().isAfter(due), r.closedBy(), r.closedAt(), canDecide(r),
                CurrentUser.has("mission.manage"), me != null && r.userId() == me);
    }

    private Row row(long id) {
        return jdbc.sql(SELECT + " and m.id = ?").param(id).query(Row.class).optional()
                .orElseThrow(() -> new NotFoundException("Mission not found."));
    }

    private Row visible(long id) {
        Row r = row(id);
        if (!canSee(r)) {
            throw new AccessDeniedException("You cannot see this mission.");
        }
        return r;
    }

    private List<View> views(List<Row> rows) {
        Map<Long, Long> units = org.unitsOn(today());
        return rows.stream().map(r -> view(r, units)).toList();
    }

    public List<View> mine() {
        return views(jdbc.sql(SELECT + " and m.user_id = ? order by m.work_date desc, m.id desc").param(CurrentUser.id())
                .query(Row.class).list());
    }

    /** Waiting for the current user's decision. */
    public List<View> toDecide() {
        return views(jdbc.sql(SELECT + " and m.status = 'PENDING' order by m.work_date, m.id").query(Row.class).list()
                .stream().filter(this::canDecide).toList());
    }

    /** Every travel mission overlapping the period (HR, approvers of all), or the team's for a unit head. */
    public List<View> all(LocalDate from, LocalDate to) {
        boolean everyone = CurrentUser.has("mission.manage") || CurrentUser.has("mission.approve.all");
        if (!everyone && !CurrentUser.has("mission.approve.team")) {
            throw new AccessDeniedException("You cannot see other people's missions.");
        }
        Set<Long> team = everyone ? null : org.teamOf(CurrentUser.id());
        return views(jdbc.sql(SELECT + " and m.work_date <= ? and m.end_date >= ? order by m.work_date desc, m.id desc")
                .params(to, from).query(Row.class).list().stream()
                .filter(r -> team == null || team.contains(r.userId())).toList());
    }

    public Detail detail(long id) {
        Row r = visible(id);
        Map<Long, Long> units = org.unitsOn(today());
        String report = jdbc.sql("select report from mission where id = ?").param(id).query(String.class).optional().orElse(null);
        return new Detail(view(r, units), money(r), expenses(id), payments(id), report);
    }

    private List<Expense> expenses(long missionId) {
        return jdbc.sql("""
                select id, spent_on, category, description, amount, trim(currency) as currency, receipt is not null as has_receipt,
                       receipt_name, status, decided_by, decision_note, created_by
                from mission_expense where mission_id = ? order by spent_on, id""").param(missionId).query(Expense.class).list();
    }

    private List<Payment> payments(long missionId) {
        return jdbc.sql("""
                select id, kind, number, amount, trim(currency) as currency, status, note, created_at, created_by
                from mission_payment where mission_id = ? order by id""").param(missionId).query(Payment.class).list();
    }

    Money money(Row r) {
        String cur = r.currency();
        int days = (int) ChronoUnit.DAYS.between(r.startDate(), r.endDate()) + 1;
        int nights = days - 1;
        BigDecimal rate = r.perDiemRate() == null ? BigDecimal.ZERO : r.perDiemRate();
        BigDecimal perDiem = rate.multiply(BigDecimal.valueOf(days));
        BigDecimal lodging = r.lodgingByCompany() || r.lodgingRate() == null ? BigDecimal.ZERO
                : r.lodgingRate().multiply(BigDecimal.valueOf(nights));
        BigDecimal accepted = sum("select coalesce(sum(amount), 0) from mission_expense where mission_id = ? and status = 'ACCEPTED'", r.id());
        BigDecimal waiting = sum("select coalesce(sum(amount), 0) from mission_expense where mission_id = ? and status = 'SUBMITTED'", r.id());
        BigDecimal paid = sum("select coalesce(sum(amount), 0) from mission_payment where mission_id = ? and kind in ('ADVANCE', 'BALANCE') and status in ('APPROVED', 'PAID')", r.id());
        BigDecimal returned = sum("select coalesce(sum(amount), 0) from mission_payment where mission_id = ? and kind = 'REFUND' and status = 'RECEIVED'", r.id());
        BigDecimal due = perDiem.add(lodging).add(accepted);
        BigDecimal excess = r.perDiemScale() == null || rate.compareTo(r.perDiemScale()) <= 0 ? BigDecimal.ZERO
                : rate.subtract(r.perDiemScale()).multiply(BigDecimal.valueOf(days));
        return new Money(cur, days, money(perDiem), nights, money(lodging), money(accepted), money(waiting), money(due),
                money(paid), money(returned), money(due.subtract(paid).add(returned)), money(excess));
    }

    private BigDecimal sum(String sql, long id) {
        return jdbc.sql(sql).param(id).query(BigDecimal.class).single();
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------- request, approval, changes

    private record Place(String country, String city, String scope, String zone) {}

    private Place place(String countryCode, String city) {
        if (countryCode == null || countryCode.isBlank()) {
            throw new BusinessRuleException("Choose the country of the mission.");
        }
        String code = countryCode.trim().toUpperCase(Locale.ROOT);
        if (jdbc.sql("select count(*) from country where code = ?").param(code).query(Integer.class).single() == 0) {
            throw new BusinessRuleException("Unknown country: " + code + ".");
        }
        if (city == null || city.isBlank()) {
            throw new BusinessRuleException("Enter the city of the mission.");
        }
        String home = home();
        return new Place(code, city.trim(), code.equals(home) ? "DOMESTIC" : "INTERNATIONAL", zoneOf(code, home));
    }

    private void checkDates(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new BusinessRuleException("Enter the departure and return dates.");
        }
        if (end.isBefore(start)) {
            throw new BusinessRuleException("The return comes before the departure.");
        }
        if (ChronoUnit.DAYS.between(start, end) >= 366) {
            throw new BusinessRuleException("A mission lasts at most one year.");
        }
        if (start.isBefore(today().minusDays(60)) || start.isAfter(today().plusDays(365))) {
            throw new BusinessRuleException("Choose a departure from 60 days ago to one year ahead.");
        }
    }

    private void checkFree(long userId, LocalDate start, LocalDate end, Long except) {
        String name = jdbc.sql("select person_name(first_name, middle_name, last_name) from app_user where id = ?")
                .param(userId).query(String.class).optional().orElseThrow(() -> new NotFoundException("User not found."));
        int missions = jdbc.sql("""
                select count(*) from mission where user_id = ? and status in ('PENDING', 'APPROVED') and id <> ?
                and ((kind = 'TRAVEL' and work_date <= ? and end_date >= ?) or (kind = 'SHORT' and work_date between ? and ?))""")
                .params(userId, except == null ? -1 : except, end, start, start, end).query(Integer.class).single();
        if (missions > 0) {
            throw new ConflictException(Texts.t("%s already has a mission in these dates.", name));
        }
        int leave = jdbc.sql("""
                select count(*) from leave_request where user_id = ? and status in ('PENDING', 'AWAITING_HR', 'APPROVED')
                and start_date <= ? and end_date >= ?""").params(userId, end, start).query(Integer.class).single();
        if (leave > 0) {
            throw new ConflictException(Texts.t("%s has leave in these dates: change the leave or the mission first.", name));
        }
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String clean(String s, int max, String what) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        if (t.length() > max) {
            throw new BusinessRuleException(what + " is too long (" + max + " characters at most).");
        }
        return t;
    }

    @Transactional
    public List<View> create(Input in) {
        boolean manage = CurrentUser.has("mission.manage");
        List<Long> people = in.userIds() == null || in.userIds().isEmpty() ? List.of(CurrentUser.id()) : in.userIds().stream().distinct().toList();
        if (!manage && (people.size() != 1 || !people.getFirst().equals(CurrentUser.id()))) {
            throw new AccessDeniedException("Only HR creates missions for other people.");
        }
        if (!manage && !CurrentUser.has("mission.request")) {
            throw new AccessDeniedException("You cannot request missions.");
        }
        if (in.perDiemRate() != null && !manage) {
            throw new AccessDeniedException("Only HR sets the daily rate of a mission.");
        }
        if (in.perDiemRate() != null && in.perDiemRate().signum() < 0) {
            throw new BusinessRuleException("The daily rate cannot be negative.");
        }
        checkDates(in.startDate(), in.endDate());
        Place p = place(in.countryCode(), in.city());
        String purpose = clean(in.purpose(), 500, "The purpose");
        if (purpose == null) {
            throw new BusinessRuleException("Say what the mission is for.");
        }
        String transport = in.transport() == null ? "PLANE" : in.transport();
        String costsBy = in.costsBy() == null ? "COMPANY" : in.costsBy();
        if (!TRANSPORTS.contains(transport) || !COSTS_BY.contains(costsBy)) {
            throw new BusinessRuleException("Choose the transport and who pays the costs.");
        }
        boolean lodgingByCompany = in.lodgingByCompany() == null || in.lodgingByCompany();
        String city = clean(p.city(), 120, "The city");
        String countryName = jdbc.sql("select name_fr from country where code = ?").param(p.country()).query(String.class).single();
        List<Long> ids = new ArrayList<>();
        for (Long userId : people) {
            contracts.require(userId, in.startDate());
            checkFree(userId, in.startDate(), in.endDate(), null);
            Rate rate = rateFor(userId, in.startDate(), p.zone());
            BigDecimal scale = rate == null ? null : rate.amount();
            BigDecimal daily = in.perDiemRate() != null ? in.perDiemRate() : scale;
            String currency = rate != null ? rate.currency() : homeCurrency();
            long id = jdbc.sql("""
                    insert into mission (user_id, work_date, start_time, end_time, location, purpose, status, requested_by,
                                         kind, scope, country_code, city, end_date, transport, costs_by, costs_note,
                                         lodging_by_company, zone, per_diem_scale, per_diem_rate, lodging_rate, per_diem_currency,
                                         created_by)
                    values (?, ?, '00:00', '23:59', ?, ?, 'PENDING', ?, 'TRAVEL', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    returning id""")
                    .params(userId, in.startDate(), cut(city + ", " + countryName, 200),
                            purpose, CurrentUser.email(), p.scope(), p.country(), city, in.endDate(), transport, costsBy,
                            clean(in.costsNote(), 300, "The note on costs"), lodgingByCompany, p.zone(), scale, daily,
                            rate == null || lodgingByCompany ? null : rate.lodging(), currency, CurrentUser.email())
                    .query(Long.class).single();
            ids.add(id);
            audit.record("mission.travel_requested", "mission", id, Map.of("userId", userId, "country", p.country(),
                    "city", city, "from", in.startDate().toString(), "to", in.endDate().toString()));
            Row r = row(id);
            org.managerOf(userId).ifPresent(b -> {
                if (!Long.valueOf(b.userId()).equals(CurrentUser.id())) {
                    notifications.send(b.userId(), "mission.requested", () -> Texts.t("Mission to approve"),
                            () -> Texts.t("%s: %s, %s to %s.", r.employeeName(), r.city(), Texts.day(r.startDate()), Texts.day(r.endDate())),
                            "/missions");
                }
            });
        }
        return views(ids.stream().map(this::row).toList());
    }

    @Transactional
    public View decide(long id, boolean approve, String note) {
        jdbc.sql("select id from mission where id = ? for update").param(id).query(Long.class).optional()
                .orElseThrow(() -> new NotFoundException("Mission not found."));
        Row r = row(id);
        if (!"PENDING".equals(r.status())) {
            throw new ConflictException("This mission was already decided.");
        }
        if (!canDecide(r)) {
            throw new AccessDeniedException("You cannot decide on this mission.");
        }
        String text = clean(note, 300, "The note");
        if (!approve) {
            if (text == null) {
                throw new BusinessRuleException("Give a reason for the rejection.");
            }
            jdbc.sql("update mission set status = 'REJECTED', decided_by = ?, decided_at = now(), decision_note = ? where id = ?")
                    .params(CurrentUser.email(), text, id).update();
            audit.record("mission.rejected", "mission", id, Map.of("note", text));
            notifications.send(r.userId(), "mission.rejected", () -> Texts.t("Mission refused"),
                    () -> Texts.t("Your mission to %s was refused: %s", r.city(), text), "/missions");
            return view(row(id), org.unitsOn(today()));
        }
        boolean first = r.firstApprovedBy() == null;
        // HR approving first covers both steps; a unit head's approval of a mission that needs HR waits for HR.
        if (first && needsSecond(r) && !CurrentUser.has("mission.approve.all")) {
            jdbc.sql("update mission set first_approved_by = ?, first_approved_at = now(), decision_note = ? where id = ?")
                    .params(CurrentUser.email(), text, id).update();
            audit.record("mission.first_approved", "mission", id, text == null ? Map.of() : Map.of("note", text));
            notifications.sendToHolders("mission.approve.all", "mission.requested", () -> Texts.t("Mission to approve"),
                    () -> Texts.t("%s: %s, %s to %s, approved by the unit head.", r.employeeName(), r.city(),
                            Texts.day(r.startDate()), Texts.day(r.endDate())), "/missions");
            return view(row(id), org.unitsOn(today()));
        }
        String number = nextNumber(r.startDate().getYear());
        jdbc.sql("""
                update mission set status = 'APPROVED', number = ?, verify_token = ?, decided_by = ?, decided_at = now(),
                       decision_note = coalesce(?, decision_note),
                       first_approved_by = coalesce(first_approved_by, ?), first_approved_at = coalesce(first_approved_at, now())
                where id = ?""").params(number, UUID.randomUUID(), CurrentUser.email(), text, CurrentUser.email(), id).update();
        audit.record("mission.approved", "mission", id, Map.of("number", number));
        notifications.send(r.userId(), "mission.approved", () -> Texts.t("Mission approved"),
                () -> Texts.t("Your mission to %s is approved. Your ordre de mission %s is ready.", r.city(), number), "/missions");
        return view(row(id), org.unitsOn(today()));
    }

    private String nextNumber(int year) {
        int n = jdbc.sql("select count(*) from mission where number like ?").param("OM-" + year + "-%").query(Integer.class).single();
        String number;
        do {
            n++;
            number = "OM-%d-%04d".formatted(year, n);
        } while (jdbc.sql("select count(*) from mission where number = ?").param(number).query(Integer.class).single() > 0);
        return number;
    }

    @Transactional
    public View cancel(long id, String reason) {
        Row r = visible(id);
        boolean manage = CurrentUser.has("mission.manage");
        boolean own = r.userId() == CurrentUser.id();
        if (!Set.of("PENDING", "APPROVED").contains(r.status())) {
            throw new ConflictException("Only a pending or approved mission can be cancelled.");
        }
        if (!manage && !(own && "PENDING".equals(r.status()))) {
            throw new AccessDeniedException("Ask HR to cancel an approved mission.");
        }
        String text = clean(reason, 300, "The reason");
        if ("APPROVED".equals(r.status()) && text == null) {
            throw new BusinessRuleException("Give the reason for cancelling an approved mission.");
        }
        int money = jdbc.sql("select count(*) from mission_payment where mission_id = ? and status <> 'CANCELLED'")
                .param(id).query(Integer.class).single();
        if (money > 0) {
            throw new ConflictException("Money was paid for this mission: settle it instead (the employee gives the advance back).");
        }
        jdbc.sql("update mission set status = 'CANCELLED', decided_by = ?, decided_at = now(), decision_note = coalesce(?, decision_note) where id = ?")
                .params(CurrentUser.email(), text, id).update();
        audit.record("mission.cancelled", "mission", id, text == null ? Map.of() : Map.of("reason", text));
        if (!own) {
            notifications.send(r.userId(), "mission.cancelled", () -> Texts.t("Mission cancelled"),
                    () -> Texts.t("Your mission to %s was cancelled.", r.city()), "/missions");
        }
        return view(row(id), org.unitsOn(today()));
    }

    /** HR changes a mission (extension, early return, another city, the daily rate): a new version of the order. */
    @Transactional
    public View amend(long id, Amendment a) {
        if (!CurrentUser.has("mission.manage")) {
            throw new AccessDeniedException("Only HR changes a mission.");
        }
        Row r = row(id);
        if (!Set.of("PENDING", "APPROVED").contains(r.status())) {
            throw new ConflictException("Only a pending or approved mission can be changed.");
        }
        String reason = clean(a.reason(), 300, "The reason");
        if (reason == null) {
            throw new BusinessRuleException("Say why the mission changes.");
        }
        LocalDate start = a.startDate() == null ? r.startDate() : a.startDate();
        LocalDate end = a.endDate() == null ? r.endDate() : a.endDate();
        if (end.isBefore(start)) {
            throw new BusinessRuleException("The return comes before the departure.");
        }
        if (!start.equals(r.startDate()) || !end.equals(r.endDate())) {
            checkFree(r.userId(), start, end, id);
        }
        Place p = place(a.countryCode() == null ? r.countryCode() : a.countryCode(), a.city() == null ? r.city() : a.city());
        String transport = a.transport() == null ? r.transport() : a.transport();
        String costsBy = a.costsBy() == null ? r.costsBy() : a.costsBy();
        if (!TRANSPORTS.contains(transport) || !COSTS_BY.contains(costsBy)) {
            throw new BusinessRuleException("Choose the transport and who pays the costs.");
        }
        boolean lodgingByCompany = a.lodgingByCompany() == null ? r.lodgingByCompany() : a.lodgingByCompany();
        Rate rate = rateFor(r.userId(), start, p.zone());
        BigDecimal scale = rate == null ? null : rate.amount();
        BigDecimal daily = a.perDiemRate() != null ? a.perDiemRate()
                : r.perDiemRate() != null && r.perDiemScale() != null && r.perDiemRate().compareTo(r.perDiemScale()) != 0 ? r.perDiemRate() : scale;
        if (daily != null && daily.signum() < 0) {
            throw new BusinessRuleException("The daily rate cannot be negative.");
        }
        String purpose = a.purpose() == null ? r.purpose() : clean(a.purpose(), 500, "The purpose");
        String countryName = jdbc.sql("select name_fr from country where code = ?").param(p.country()).query(String.class).single();
        String location = p.city() + ", " + countryName;
        boolean approved = "APPROVED".equals(r.status());
        jdbc.sql("""
                update mission set work_date = ?, end_date = ?, scope = ?, country_code = ?, city = ?, zone = ?, location = ?,
                       purpose = ?, transport = ?, costs_by = ?, costs_note = ?, lodging_by_company = ?, per_diem_scale = ?,
                       per_diem_rate = ?, lodging_rate = ?, per_diem_currency = coalesce(?, per_diem_currency),
                       amendment = amendment + ?, amendment_reason = ?
                where id = ?""")
                .params(start, end, p.scope(), p.country(), p.city(), p.zone(), location.substring(0, Math.min(200, location.length())),
                        purpose, transport, costsBy, a.costsNote() == null ? r.costsNote() : clean(a.costsNote(), 300, "The note on costs"),
                        lodgingByCompany, scale, daily, rate == null || lodgingByCompany ? null : rate.lodging(),
                        rate == null ? null : rate.currency(), approved ? 1 : 0, reason, id).update();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        details.put("from", start.toString());
        details.put("to", end.toString());
        details.put("city", p.city());
        details.put("rate", daily == null ? "" : daily);
        audit.record("mission.amended", "mission", id, details);
        if (approved) {
            notifications.send(r.userId(), "mission.amended", () -> Texts.t("Mission changed"),
                    () -> Texts.t("Your mission to %s changed: %s. Print the new ordre de mission.", p.city(), reason), "/missions");
        }
        return view(row(id), org.unitsOn(today()));
    }

    // ---------------------------------------------------------------- money, expenses, report, settlement

    private Row managed(long id) {
        if (!CurrentUser.has("mission.manage")) {
            throw new AccessDeniedException("Only HR handles the money of missions.");
        }
        return row(id);
    }

    private long payRun(YearMonth month) {
        jdbc.sql("insert into mission_pay_run (month) values (?) on conflict do nothing").param(month.atDay(1)).update();
        return jdbc.sql("select id from mission_pay_run where month = ?").param(month.atDay(1)).query(Long.class).single();
    }

    private String paymentNumber(String prefix) {
        String ym = YearMonth.from(today()).toString().replace("-", "");
        int n = jdbc.sql("select count(*) from mission_payment where number like ?").param(prefix + "-" + ym + "-%").query(Integer.class).single();
        String number;
        do {
            n++;
            number = "%s-%s-%04d".formatted(prefix, ym, n);
        } while (jdbc.sql("select count(*) from mission_payment where number = ?").param(number).query(Integer.class).single() > 0);
        return number;
    }

    private void addPayment(Row r, String kind, BigDecimal amount, String note) {
        Long unit = org.unitsOn(today()).get(r.userId());
        Long run = "REFUND".equals(kind) ? null : payRun(YearMonth.from(today()));
        String number = paymentNumber("REFUND".equals(kind) ? "MR" : "MP");
        long pid = jdbc.sql("""
                insert into mission_payment (run_id, mission_id, user_id, kind, number, amount, currency, status, note, unit_id, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(run, r.id(), r.userId(), kind, number, money(amount), r.currency(),
                        "REFUND".equals(kind) ? "RECEIVED" : "APPROVED", note, unit, CurrentUser.email())
                .query(Long.class).single();
        audit.record("mission.payment", "mission_payment", pid, Map.of("missionId", r.id(), "kind", kind,
                "amount", money(amount), "currency", r.currency(), "number", number));
    }

    /** The advance before departure, paid through a payment file (Payments, kind MISSION). */
    @Transactional
    public Detail advance(long id, BigDecimal amount, String note) {
        Row r = managed(id);
        if (!"APPROVED".equals(r.status())) {
            throw new ConflictException("Only an approved mission gets an advance.");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessRuleException("Enter the amount of the advance.");
        }
        Money m = money(r);
        BigDecimal estimate = m.perDiem().add(m.lodging());
        if (m.paid().add(amount).compareTo(estimate.add(m.expensesAccepted())) > 0) {
            throw new BusinessRuleException(Texts.t("The advances would pass what the mission pays (%s %s).",
                    estimate.add(m.expensesAccepted()).toPlainString(), r.currency()));
        }
        addPayment(r, "ADVANCE", amount, clean(note, 300, "The note"));
        notifications.send(r.userId(), "mission.advance", () -> Texts.t("Mission advance"),
                () -> Texts.t("An advance of %s %s for your mission to %s is on its way.", money(amount).toPlainString(), r.currency(), r.city()),
                "/missions");
        return detail(id);
    }

    /** Withdraws an advance or balance not yet in a payment file. */
    @Transactional
    public Detail cancelPayment(long id, long paymentId) {
        managed(id);
        String status = jdbc.sql("select status from mission_payment where id = ? and mission_id = ?").params(paymentId, id)
                .query(String.class).optional().orElseThrow(() -> new NotFoundException("Payment not found."));
        boolean inFile = jdbc.sql("""
                select count(*) from payment_line l join payment_batch b on b.id = l.batch_id
                where l.mission_payment_id = ? and b.status <> 'CANCELLED'""").param(paymentId).query(Integer.class).single() > 0;
        if (inFile || "PAID".equals(status) || "CANCELLED".equals(status)) {
            throw new ConflictException("This payment is in a payment file or already paid: cancel the file first.");
        }
        if ("CLOSED".equals(row(id).status())) {
            throw new ConflictException("The mission is closed.");
        }
        jdbc.sql("update mission_payment set status = 'CANCELLED' where id = ?").param(paymentId).update();
        audit.record("mission.payment_cancelled", "mission_payment", paymentId, Map.of("missionId", id));
        return detail(id);
    }

    private Row ownOrManaged(long id) {
        Row r = visible(id);
        if (r.userId() != CurrentUser.id() && !CurrentUser.has("mission.manage")) {
            throw new AccessDeniedException("Only the employee or HR does this.");
        }
        if (!"APPROVED".equals(r.status())) {
            throw new ConflictException("Only an approved mission that is not closed takes this.");
        }
        return r;
    }

    @Transactional
    public Detail addExpense(long id, LocalDate spentOn, String category, String description, BigDecimal amount,
                             Receipt receipt) {
        Row r = ownOrManaged(id);
        if (spentOn == null || spentOn.isBefore(r.startDate().minusDays(30)) || spentOn.isAfter(r.endDate().plusDays(7))) {
            throw new BusinessRuleException("The date must be around the mission's dates.");
        }
        if (!EXPENSE_CATEGORIES.contains(category)) {
            throw new BusinessRuleException("Choose what the cost is for.");
        }
        String what = clean(description, 200, "The description");
        if (what == null) {
            throw new BusinessRuleException("Describe the cost.");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessRuleException("Enter the amount, in " + r.currency() + ".");
        }
        if (receipt != null) {
            if (receipt.data().length > 5 * 1024 * 1024) {
                throw new BusinessRuleException("The receipt must be 5 MB at most.");
            }
            String type = receipt.type() == null ? "" : receipt.type();
            if (!type.startsWith("image/") && !type.equals("application/pdf")) {
                throw new BusinessRuleException("The receipt must be a photo or a PDF.");
            }
        }
        long eid = jdbc.sql("""
                insert into mission_expense (mission_id, spent_on, category, description, amount, currency, receipt, receipt_name,
                                             receipt_type, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(id, spentOn, category, what, money(amount), r.currency(), receipt == null ? null : receipt.data(),
                        receipt == null ? null : clean(receipt.name(), 200, "The file name"), receipt == null ? null : receipt.type(),
                        CurrentUser.email()).query(Long.class).single();
        audit.record("mission.expense", "mission_expense", eid, Map.of("missionId", id, "amount", money(amount), "category", category));
        return detail(id);
    }

    @Transactional
    public Detail deleteExpense(long id, long expenseId) {
        ownOrManaged(id);
        int n = jdbc.sql("delete from mission_expense where id = ? and mission_id = ? and status = 'SUBMITTED'")
                .params(expenseId, id).update();
        if (n == 0) {
            throw new ConflictException("Only a cost not yet checked by HR can be removed.");
        }
        audit.record("mission.expense_deleted", "mission_expense", expenseId, Map.of("missionId", id));
        return detail(id);
    }

    @Transactional
    public Detail decideExpense(long id, long expenseId, boolean accept, String note) {
        Row r = managed(id);
        if (!"APPROVED".equals(r.status())) {
            throw new ConflictException("The mission is closed.");
        }
        String text = clean(note, 300, "The note");
        if (!accept && text == null) {
            throw new BusinessRuleException("Say why the cost is refused.");
        }
        int n = jdbc.sql("update mission_expense set status = ?, decided_by = ?, decision_note = ? where id = ? and mission_id = ?")
                .params(accept ? "ACCEPTED" : "REFUSED", CurrentUser.email(), text, expenseId, id).update();
        if (n == 0) {
            throw new NotFoundException("Cost not found.");
        }
        audit.record(accept ? "mission.expense_accepted" : "mission.expense_refused", "mission_expense", expenseId, Map.of("missionId", id));
        return detail(id);
    }

    public Receipt receipt(long id, long expenseId) {
        visible(id);
        return jdbc.sql("select receipt_name as name, receipt_type as type, receipt as data from mission_expense where id = ? and mission_id = ? and receipt is not null")
                .params(expenseId, id).query(Receipt.class).optional().orElseThrow(() -> new NotFoundException("No receipt."));
    }

    @Transactional
    public Detail fileReport(long id, String report) {
        Row r = ownOrManaged(id);
        String text = report == null ? null : report.trim();
        if (text == null || text.length() < 20) {
            throw new BusinessRuleException("Write a short report of the mission (20 characters at least).");
        }
        if (text.length() > 10000) {
            throw new BusinessRuleException("The report is too long (10,000 characters at most).");
        }
        if (today().isBefore(r.startDate())) {
            throw new BusinessRuleException("The report is filed once the mission has started.");
        }
        jdbc.sql("update mission set report = ?, report_at = now() where id = ?").params(text, id).update();
        audit.record("mission.report", "mission", id, Map.of("length", text.length()));
        if (r.userId() == CurrentUser.id()) {
            notifications.sendToHolders("mission.manage", "mission.report", () -> Texts.t("Mission report filed"),
                    () -> Texts.t("%s filed the report of the mission to %s.", r.employeeName(), r.city()), "/missions");
        }
        return detail(id);
    }

    /** What the employee gave back (cash or transfer) when the advance was more than the mission cost. */
    @Transactional
    public Detail refund(long id, BigDecimal amount, String note) {
        Row r = managed(id);
        if (!"APPROVED".equals(r.status())) {
            throw new ConflictException("The mission is closed.");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessRuleException("Enter the amount given back.");
        }
        Money m = money(r);
        if (amount.compareTo(m.balance().negate()) > 0) {
            throw new BusinessRuleException(Texts.t("The employee owes %s %s at most.", m.balance().negate().max(BigDecimal.ZERO).toPlainString(), r.currency()));
        }
        addPayment(r, "REFUND", amount, clean(note, 300, "The note"));
        return detail(id);
    }

    /**
     * Settles and closes a mission after the return: the report is filed and every cost checked. A balance owed to the
     * employee is put to pay; what the employee owes must be given back first. A daily rate above the scale puts the
     * excess on the payslip of the open payroll month, as pay.
     */
    @Transactional
    public Detail close(long id) {
        Row r = managed(id);
        if (!"APPROVED".equals(r.status())) {
            throw new ConflictException("Only an approved mission can be closed.");
        }
        if (today().isBefore(r.endDate())) {
            throw new BusinessRuleException("The mission ends on " + r.endDate() + ": close it after the return.");
        }
        if (!r.reportFiled()) {
            throw new BusinessRuleException("The mission report is not filed yet.");
        }
        Money m = money(r);
        if (m.expensesWaiting().signum() > 0) {
            throw new BusinessRuleException("Accept or refuse every cost first.");
        }
        if (m.balance().signum() < 0) {
            throw new BusinessRuleException(Texts.t("The employee owes %s %s: record what was given back first.",
                    m.balance().negate().toPlainString(), r.currency()));
        }
        if (m.balance().signum() > 0) {
            addPayment(r, "BALANCE", m.balance(), Texts.t("Balance of mission %s", r.number()));
        }
        LocalDate month = null;
        if (m.excess().signum() > 0) {
            month = openPayrollMonth();
            jdbc.sql("update mission set excess_month = ? where id = ?").params(month, id).update();
            writeExcess(r.userId(), month);
        }
        jdbc.sql("update mission set status = 'CLOSED', closed_by = ?, closed_at = now() where id = ?")
                .params(CurrentUser.email(), id).update();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("due", m.due());
        details.put("balance", m.balance());
        details.put("excess", m.excess());
        if (month != null) {
            details.put("excessMonth", month.toString());
        }
        audit.record("mission.closed", "mission", id, details);
        notifications.send(r.userId(), "mission.closed", () -> Texts.t("Mission settled"),
                () -> m.balance().signum() > 0
                        ? Texts.t("Your mission to %s is settled: %s %s will be paid to you.", r.city(), m.balance().toPlainString(), r.currency())
                        : Texts.t("Your mission to %s is settled.", r.city()), "/missions");
        return detail(id);
    }

    /** The first month whose payroll is not approved or closed yet. */
    private LocalDate openPayrollMonth() {
        LocalDate month = YearMonth.from(today()).atDay(1);
        while (jdbc.sql("select count(*) from payroll_run where month = ? and status in ('APPROVED', 'CLOSED')")
                .param(month).query(Integer.class).single() > 0) {
            month = month.plusMonths(1);
        }
        return month;
    }

    private void writeExcess(long userId, LocalDate month) {
        long element = ensureExcessElement();
        record Part(BigDecimal amount, String currency, String number) {}
        List<Part> parts = jdbc.sql("""
                select (per_diem_rate - per_diem_scale) * (end_date - work_date + 1) as amount, trim(per_diem_currency) as currency, number
                from mission where user_id = ? and excess_month = ? and per_diem_rate > per_diem_scale""")
                .params(userId, month).query(Part.class).list();
        Map<String, BigDecimal> byCurrency = new HashMap<>();
        Map<String, List<String>> numbers = new HashMap<>();
        for (Part p : parts) {
            byCurrency.merge(p.currency(), p.amount(), BigDecimal::add);
            numbers.computeIfAbsent(p.currency(), k -> new ArrayList<>()).add(p.number());
        }
        jdbc.sql("delete from pay_entry where user_id = ? and element_id = ? and month = ?").params(userId, element, month).update();
        // One entry per element and month: the first currency carries it (missions of one person share the scale's currency).
        byCurrency.entrySet().stream().findFirst().ifPresent(e -> jdbc.sql("""
                insert into pay_entry (user_id, element_id, month, amount, currency, note, created_by)
                values (?, ?, ?, ?, ?, ?, ?)""")
                .params(userId, element, month, money(e.getValue()), e.getKey(), String.join(", ", numbers.get(e.getKey())),
                        CurrentUser.email()).update());
    }

    private long ensureExcessElement() {
        return jdbc.sql("select id from pay_element where code = ?").param(EXCESS_ELEMENT).query(Long.class).optional()
                .orElseGet(() -> jdbc.sql("""
                        insert into pay_element (code, name, kind, method, subject_to_cnss, taxable_ipr, prorated, default_for_all,
                                                 system, display_order)
                        values (?, ?, 'EARNING', 'COMPUTED', true, true, false, false, true, 75) returning id""")
                        .params(EXCESS_ELEMENT, Texts.t("Mission allowance (taxable excess)")).query(Long.class).single());
    }

    // ---------------------------------------------------------------- the order and its public check

    /** The data of the ordre de mission, for the PDF. Only once approved. */
    public Row order(long id) {
        Row r = visible(id);
        if (r.number() == null || !Set.of("APPROVED", "CLOSED").contains(r.status())) {
            throw new ConflictException("The ordre de mission is ready once the mission is approved.");
        }
        return r;
    }

    public UUID tokenOf(long id) {
        return jdbc.sql("select verify_token from mission where id = ?").param(id).query(UUID.class).single();
    }

    /** Reads an order by its QR code token, in the current company (the caller sets it). */
    public Check check(UUID token) {
        record C(String status, String number, int amendment, String company, String employeeName, String city,
                 String country, LocalDate startDate, LocalDate endDate) {}
        C c = jdbc.sql("""
                select m.status, m.number, m.amendment, co.name as company,
                       person_name(u.first_name, u.middle_name, u.last_name) as employee_name, m.city, k.name_fr as country,
                       m.work_date as start_date, m.end_date
                from mission m join app_user u on u.id = m.user_id join country k on k.code = m.country_code
                join company co on co.id = m.company_id where m.verify_token = ?""").param(token).query(C.class).optional()
                .orElseThrow(() -> new NotFoundException("This ordre de mission is not known."));
        String status = switch (c.status()) {
            case "APPROVED" -> today().isAfter(c.endDate()) ? "ENDED" : "VALID";
            case "CLOSED" -> "ENDED";
            default -> "CANCELLED";
        };
        return new Check(status, c.number(), c.amendment(), c.company(), c.employeeName(), c.city() + ", " + c.country(),
                c.startDate(), c.endDate());
    }

    /** The company of a token, for the public check page (read across companies). */
    public Long companyOf(UUID token) {
        return jdbc.sql("select company_id from mission where verify_token = ?").param(token).query(Long.class).optional().orElse(null);
    }

    /** Extra lines of the order: the traveller's job, unit and passport. */
    public record OrderPerson(String occupation, String grade, String unit, String idType, String idNumber,
                              String nationality) {}

    public OrderPerson person(long userId, LocalDate on, cd.hrpay.security.DataCrypto crypto) {
        record P(String occupation, String grade, String idType, String idNumber, String nationality) {}
        P p = jdbc.sql("""
                select (select o.name from employee_contract c left join occupation o on o.id = c.occupation_id
                        where c.user_id = u.id and c.start_date <= ? order by c.start_date desc limit 1) as occupation,
                       (select g.code || ' · ' || g.name from employee_contract c join grade g on g.id = c.grade_id
                        where c.user_id = u.id and c.start_date <= ? order by c.start_date desc limit 1) as grade,
                       f.id_document_type as id_type, f.id_document_number as id_number, f.nationality
                from app_user u left join employee_profile f on f.user_id = u.id where u.id = ?""")
                .params(on, on, userId).query(P.class).single();
        String unit = org.currentAssignment(userId).map(OrgStructure.Assignment::unitName).orElse(null);
        String id = null;
        try {
            id = p.idNumber() == null ? null : crypto.decrypt(p.idNumber());
        } catch (RuntimeException e) {
            id = null; // an unreadable number is left off the order
        }
        return new OrderPerson(p.occupation(), p.grade(), unit, p.idType(), id, p.nationality());
    }
}
