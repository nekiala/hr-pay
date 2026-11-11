package cd.hrpay.filing;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.payment.Company;
import cd.hrpay.payroll.EmployeeFile;
import cd.hrpay.payroll.report.Report;
import cd.hrpay.payroll.report.Report.Column;
import cd.hrpay.payroll.report.Report.Row;
import cd.hrpay.payroll.report.Report.Table;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The legal workforce filings: in the DRC, the declaration of each hiring and each departure (Code du travail art.
 * 217) and, once a year, the national and foreign workforce and the bilan social (art. 218); in the Republic of the
 * Congo, the personnel register kept for the labour inspector (art. 182). Each is made from the employee files,
 * contracts, departures and payslips; only what was filed is recorded. No official model was found for any of them,
 * so the forms carry what the texts ask for in our own layout.
 */
@Service
public class LegalFilings {

    public static final List<String> EVENT_KINDS = List.of("HIRE", "DEPARTURE");
    public static final List<String> ANNUAL_KINDS = List.of("WORKFORCE", "BILAN_SOCIAL", "TAX_ANNUAL");

    /** TO_FILE: still to send; UPCOMING: its period has not started or ended yet. */
    public enum Status { UPCOMING, TO_FILE, FILED }

    public record Rule(String kind, String authority, Integer dueDays, Integer remindAfterDays, Integer dueMonth,
                       Integer dueDay, int yearOffset, String legalBasis, boolean verified) {}

    public record Filed(LocalDate filedOn, String reference, String note, String filedBy, OffsetDateTime filedAt) {}

    /** A hiring or a departure to declare. */
    public record Movement(String kind, long userId, String registrationNo, String name, LocalDate eventDate,
                           String detail, LocalDate due, LocalDate remindFrom, Status status, boolean late,
                           long daysLeft, Filed filed) {}

    /** A yearly filing: the workforce at 1 January of the year, or the bilan social of the year. */
    public record Annual(String kind, int year, LocalDate due, Status status, boolean late, long daysLeft, Filed filed,
                         String authority, String legalBasis, boolean verified) {}

    public record Settings(String country, LocalDate filingsFrom, boolean personnelRegister, boolean registerByDefault,
                           boolean movements, boolean annual) {}

    public record Page(Settings settings, List<Movement> movements, List<Annual> annual, List<Rule> rules) {}

    public record Visit(long id, LocalDate visitedOn, String inspector, String observations, String enteredBy,
                        OffsetDateTime enteredAt) {}

    /** One person under contract on a day, with what the forms and counts need. */
    record Person(long userId, String registrationNo, String name, String contractType, LocalDate hireDate,
                  LocalDate contractStart, LocalDate contractEnd, boolean expatriate, String gradeCode, String gradeName,
                  String categoryCode, String categoryName, String occupation, String siteName, String unitName,
                  String gender, LocalDate birthDate, String nationalityCode) {}

    private static final String PERSON = """
            select u.id as user_id, u.registration_no, person_name(u.first_name, u.middle_name, u.last_name) as name,
                   c.contract_type, coalesce(u.hire_date, c.start_date) as hire_date, c.start_date as contract_start,
                   c.end_date as contract_end, c.expatriate, g.code as grade_code, g.name as grade_name,
                   gc.code as category_code, gc.name as category_name, o.name as occupation, s.name as site_name,
                   (select ou.name from org_assignment a join org_unit ou on ou.id = a.unit_id
                    where a.user_id = u.id and a.valid_from <= :d and (a.valid_to is null or a.valid_to >= :d)
                    order by a.valid_from desc limit 1) as unit_name,
                   p.gender, p.birth_date, p.nationality_code
            from app_user u
            join lateral (select * from employee_contract c where c.user_id = u.id and c.start_date <= :d
                          and (c.end_date is null or c.end_date >= :d) order by c.start_date desc limit 1) c on true
            join grade g on g.id = c.grade_id join grade_category gc on gc.id = g.category_id
            left join occupation o on o.id = c.occupation_id left join site s on s.id = u.site_id
            left join employee_profile p on p.user_id = u.id
            where not u.not_employee and c.contract_type <> 'CONSULTANT'
              and not exists (select 1 from departure x where x.user_id = u.id and x.status = 'CONFIRMED'
                              and x.last_day < :d and x.last_day >= c.start_date)""";

    private final JdbcClient jdbc;
    private final Company company;
    private final EmployeeFile files;
    private final AuditService audit;
    private final Clock clock;

    public LegalFilings(JdbcClient jdbc, Company company, EmployeeFile files, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.company = company;
        this.files = files;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- settings and rules ------------------------------------------------------------------------------------------

    public List<Rule> rules() {
        return jdbc.sql("""
                select kind, authority, due_days, remind_after_days, due_month, due_day, year_offset, legal_basis, verified
                from filing_rule where country_code = ? order by id""").param(company.country()).query(Rule.class).list();
    }

    private Optional<Rule> rule(String kind) {
        return rules().stream().filter(r -> r.kind().equals(kind)).findFirst();
    }

    public Settings settings() {
        String country = company.country();
        Map<String, Object> row = jdbc.sql("select filings_from, personnel_register from company where id = current_company_id()")
                .query().singleRow();
        boolean byDefault = "CG".equals(country);
        Boolean set = (Boolean) row.get("personnel_register");
        List<Rule> rules = rules();
        return new Settings(country, ((java.sql.Date) row.get("filings_from")).toLocalDate(), set == null ? byDefault : set,
                byDefault, rules.stream().anyMatch(r -> EVENT_KINDS.contains(r.kind())),
                rules.stream().anyMatch(r -> ANNUAL_KINDS.contains(r.kind())));
    }

    @Transactional
    public Settings saveSettings(LocalDate filingsFrom, Boolean personnelRegister) {
        if (filingsFrom == null) {
            throw new BusinessRuleException("Give the date from which hirings and departures are declared here.");
        }
        jdbc.sql("update company set filings_from = ?, personnel_register = ? where id = current_company_id()")
                .params(filingsFrom, personnelRegister).update();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("filingsFrom", filingsFrom.toString());
        details.put("personnelRegister", personnelRegister);
        audit.record("filing.settings_saved", "company", null, details);
        return settings();
    }

    // ---- the page ------------------------------------------------------------------------------------------------------

    public Page page() {
        return new Page(settings(), movements(), annual(), rules());
    }

    /** Hirings and departures from the company's start date, newest first. */
    public List<Movement> movements() {
        Optional<Rule> hire = rule("HIRE");
        Optional<Rule> departure = rule("DEPARTURE");
        if (hire.isEmpty() && departure.isEmpty()) {
            return List.of();
        }
        LocalDate from = settings().filingsFrom();
        LocalDate today = LocalDate.now(clock);
        Map<String, Filed> filed = new LinkedHashMap<>();
        jdbc.sql("select kind, user_id, event_date, filed_on, reference, note, filed_by, filed_at from legal_filing where user_id is not null")
                .query((rs, i) -> {
                    filed.put(rs.getString("kind") + " " + rs.getLong("user_id") + " " + rs.getDate("event_date").toLocalDate(),
                            new Filed(rs.getDate("filed_on").toLocalDate(), rs.getString("reference"), rs.getString("note"),
                                    rs.getString("filed_by"), rs.getObject("filed_at", OffsetDateTime.class)));
                    return null;
                }).list();
        List<Movement> out = new ArrayList<>();
        hire.ifPresent(rule -> jdbc.sql("""
                select c.user_id, u.registration_no, person_name(u.first_name, u.middle_name, u.last_name) as name, c.start_date,
                       c.contract_type
                from employee_contract c join app_user u on u.id = c.user_id
                where not u.not_employee and c.contract_type <> 'CONSULTANT' and c.start_date >= ?
                  and not exists (select 1 from employee_contract p where p.user_id = c.user_id and p.start_date < c.start_date
                                  and not exists (select 1 from departure x where x.user_id = c.user_id and x.status = 'CONFIRMED'
                                                  and x.last_day >= p.start_date and x.last_day < c.start_date))""")
                .param(from).query((rs, i) -> movement(rule, "HIRE", rs.getLong("user_id"), rs.getString("registration_no"),
                        rs.getString("name"), rs.getDate("start_date").toLocalDate(), contractType(rs.getString("contract_type")), filed, today))
                .list().forEach(out::add));
        departure.ifPresent(rule -> jdbc.sql("""
                select x.user_id, u.registration_no, person_name(u.first_name, u.middle_name, u.last_name) as name, x.last_day, x.reason
                from departure x join app_user u on u.id = x.user_id
                where x.status = 'CONFIRMED' and x.last_day >= ? and not u.not_employee""")
                .param(from).query((rs, i) -> movement(rule, "DEPARTURE", rs.getLong("user_id"), rs.getString("registration_no"),
                        rs.getString("name"), rs.getDate("last_day").toLocalDate(), reason(rs.getString("reason")), filed, today))
                .list().forEach(out::add));
        out.sort(Comparator.comparing(Movement::eventDate).reversed().thenComparing(Movement::name));
        return out;
    }

    private static Movement movement(Rule rule, String kind, long userId, String registrationNo, String name, LocalDate event,
                                     String detail, Map<String, Filed> filed, LocalDate today) {
        LocalDate due = event.plusDays(rule.dueDays());
        LocalDate remind = event.plusDays(rule.remindAfterDays() == null ? 0 : rule.remindAfterDays());
        Filed f = filed.get(kind + " " + userId + " " + event);
        Status status = f != null ? Status.FILED : event.isAfter(today) ? Status.UPCOMING : Status.TO_FILE;
        boolean late = f != null ? f.filedOn().isAfter(due) : today.isAfter(due);
        return new Movement(kind, userId, registrationNo, name, event, detail, due, remind, status, late,
                ChronoUnit.DAYS.between(today, due), f);
    }

    /** The yearly filings due from the company's start date until three months ahead. */
    public List<Annual> annual() {
        Settings s = settings();
        LocalDate today = LocalDate.now(clock);
        Map<String, Filed> filed = new LinkedHashMap<>();
        jdbc.sql("select kind, year, filed_on, reference, note, filed_by, filed_at from legal_filing where year is not null")
                .query((rs, i) -> {
                    filed.put(rs.getString("kind") + " " + rs.getInt("year"), new Filed(rs.getDate("filed_on").toLocalDate(),
                            rs.getString("reference"), rs.getString("note"), rs.getString("filed_by"),
                            rs.getObject("filed_at", OffsetDateTime.class)));
                    return null;
                }).list();
        List<Annual> out = new ArrayList<>();
        for (Rule rule : rules()) {
            if (!ANNUAL_KINDS.contains(rule.kind())) {
                continue;
            }
            for (int year = s.filingsFrom().getYear() - 1; year <= today.getYear() + 1; year++) {
                LocalDate due = annualDue(rule, year);
                Filed f = filed.get(rule.kind() + " " + year);
                if (f == null && (due.isBefore(s.filingsFrom()) || due.isAfter(today.plusMonths(3)))) {
                    continue;
                }
                // The workforce is counted on 1 January of its year; the bilan social and the tax summary once the year is over.
                LocalDate ready = rule.kind().equals("WORKFORCE") ? LocalDate.of(year, 1, 1) : LocalDate.of(year, 12, 31);
                Status status = f != null ? Status.FILED : today.isBefore(ready) ? Status.UPCOMING : Status.TO_FILE;
                boolean late = f != null ? f.filedOn().isAfter(due) : today.isAfter(due);
                out.add(new Annual(rule.kind(), year, due, status, late, ChronoUnit.DAYS.between(today, due), f,
                        rule.authority(), rule.legalBasis(), rule.verified()));
            }
        }
        out.sort(Comparator.comparing(Annual::due).reversed().thenComparing(Annual::kind));
        return out;
    }

    /** A yearly filing's name: the tax summary as the country calls it. */
    public static String annualName(String kind) {
        return switch (kind) {
            case "WORKFORCE" -> Texts.t("Annual declaration of the workforce");
            case "BILAN_SOCIAL" -> Texts.t("Bilan social");
            case "TAX_ANNUAL" -> Texts.t("Annual tax summary");
            default -> kind;
        };
    }

    static LocalDate annualDue(Rule rule, int year) {
        int y = year + rule.yearOffset();
        java.time.YearMonth m = java.time.YearMonth.of(y, rule.dueMonth());
        return m.atDay(Math.min(rule.dueDay(), m.lengthOfMonth()));
    }

    // ---- recording what was filed ------------------------------------------------------------------------------------

    @Transactional
    public void fileMovement(String kind, long userId, LocalDate eventDate, LocalDate filedOn, String reference, String note) {
        if (!EVENT_KINDS.contains(kind)) {
            throw new NotFoundException("Unknown filing " + kind + ".");
        }
        Movement m = movements().stream().filter(x -> x.kind().equals(kind) && x.userId() == userId && x.eventDate().equals(eventDate))
                .findFirst().orElseThrow(() -> new NotFoundException("No such hiring or departure to declare."));
        if (m.filed() != null) {
            throw new ConflictException("This declaration is already recorded as filed.");
        }
        checkFiledOn(filedOn);
        jdbc.sql("insert into legal_filing (kind, user_id, event_date, filed_on, reference, note, filed_by) values (?, ?, ?, ?, ?, ?, ?)")
                .params(kind, userId, eventDate, filedOn, blank(reference), blank(note), CurrentUser.email()).update();
        audit.record("filing.filed", "user", userId, Map.of("kind", kind, "eventDate", eventDate.toString(),
                "filedOn", filedOn.toString(), "reference", reference == null ? "" : reference));
    }

    @Transactional
    public void fileAnnual(String kind, int year, LocalDate filedOn, String reference, String note) {
        if (!ANNUAL_KINDS.contains(kind) || rule(kind).isEmpty()) {
            throw new NotFoundException("Unknown filing " + kind + ".");
        }
        checkFiledOn(filedOn);
        int done = jdbc.sql("""
                insert into legal_filing (kind, year, filed_on, reference, note, filed_by) values (?, ?, ?, ?, ?, ?)
                on conflict do nothing""").params(kind, year, filedOn, blank(reference), blank(note), CurrentUser.email()).update();
        if (done == 0) {
            throw new ConflictException("This declaration is already recorded as filed.");
        }
        audit.record("filing.filed", "company", null, Map.of("kind", kind, "year", year, "filedOn", filedOn.toString(),
                "reference", reference == null ? "" : reference));
    }

    /** Undoes a filing recorded by mistake. */
    @Transactional
    public void unfile(String kind, Long userId, LocalDate eventDate, Integer year) {
        int done = userId != null && eventDate != null
                ? jdbc.sql("delete from legal_filing where kind = ? and user_id = ? and event_date = ?").params(kind, userId, eventDate).update()
                : year != null ? jdbc.sql("delete from legal_filing where kind = ? and year = ?").params(kind, year).update() : 0;
        if (done == 0) {
            throw new NotFoundException("Nothing was recorded for this declaration.");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", kind);
        details.put("eventDate", eventDate == null ? null : eventDate.toString());
        details.put("year", year);
        audit.record("filing.unfiled", userId == null ? "company" : "user", userId, details);
    }

    private void checkFiledOn(LocalDate filedOn) {
        if (filedOn == null || filedOn.isAfter(LocalDate.now(clock))) {
            throw new BusinessRuleException("Give the date it was filed, today or before.");
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ---- A1, A2: the hiring and departure declarations -----------------------------------------------------------------

    /** The declaration of one hiring or departure, as a form: the employer, the worker, the job, the event. */
    public Report movementForm(String kind, long userId, LocalDate eventDate) {
        Movement m = movements().stream().filter(x -> x.kind().equals(kind) && x.userId() == userId && x.eventDate().equals(eventDate))
                .findFirst().orElseThrow(() -> new NotFoundException("No such hiring or departure to declare."));
        boolean hire = kind.equals("HIRE");
        // A departure describes the job held on the last day; a hiring the job it starts in.
        Person p = people(eventDate).stream().filter(x -> x.userId() == userId).findFirst()
                .orElseThrow(() -> new NotFoundException("No contract on that day for this person."));
        EmployeeFile.Profile profile = files.profile(userId);
        List<Row> worker = new ArrayList<>();
        field(worker, Texts.t("Registration no."), p.registrationNo());
        field(worker, Texts.t("Name (NOM POSTNOM First name)"), p.name());
        field(worker, Texts.t("Sex"), sex(p.gender()));
        field(worker, Texts.t("Date and place of birth"), join(Texts.day(p.birthDate()), files.birthPlace(profile)));
        field(worker, Texts.t("Nationality"), files.nationality(profile));
        field(worker, Texts.t("Identity document"), join(idType(profile.idDocumentType()), profile.idDocumentNumber()));
        field(worker, Texts.t("Address"), files.address(profile));
        field(worker, Texts.t("Marital status"), marital(profile.maritalStatus()));
        field(worker, Texts.t("Social security no. (CNSS)"), profile.cnssNumber());
        List<Row> job = new ArrayList<>();
        field(job, Texts.t("Job"), p.occupation());
        field(job, Texts.t("Grade category"), p.categoryName());
        field(job, Texts.t("Grade"), p.gradeCode() + " · " + p.gradeName());
        field(job, Texts.t("Contract type"), contractType(p.contractType()));
        field(job, Texts.t("Workplace"), join(p.siteName(), p.unitName()));
        field(job, Texts.t("Foreign worker"), isForeign(p, company.country()) ? Texts.t("Yes") : Texts.t("No"));
        if (hire) {
            field(job, Texts.t("Hiring date"), Texts.day(eventDate));
            field(job, Texts.t("Contract end"), p.contractEnd() == null ? Texts.t("Open-ended") : Texts.day(p.contractEnd()));
            field(job, Texts.t("Agreed monthly pay"), agreedPay(userId, eventDate));
        } else {
            Map<String, Object> d = jdbc.sql("""
                    select reason, notice, service_years, note from departure where user_id = ? and last_day = ? and status = 'CONFIRMED'
                    order by id desc limit 1""").params(userId, eventDate).query().singleRow();
            field(job, Texts.t("Hiring date"), Texts.day(p.hireDate()));
            field(job, Texts.t("Last day of work"), Texts.day(eventDate));
            field(job, Texts.t("Reason for leaving"), reason((String) d.get("reason")));
            field(job, Texts.t("Seniority"), Texts.t("%s years", ((BigDecimal) d.get("service_years")).stripTrailingZeros().toPlainString()));
        }
        List<Table> tables = List.of(new Table(Texts.t("Employer"), fieldColumns(), employer()),
                new Table(Texts.t("Worker"), fieldColumns(), worker),
                new Table(hire ? Texts.t("Employment") : Texts.t("Departure"), fieldColumns(), job));
        List<String> notes = new ArrayList<>();
        Optional<Rule> rule = rule(kind);
        rule.ifPresent(r -> notes.add(r.legalBasis()));
        notes.add(Texts.t("Due by %s, to: %s.", Texts.day(m.due()), rule.map(Rule::authority).orElse("")));
        notes.add(Texts.t("Four copies: two for the labour inspectorate, one for the ONEM, one kept by the employer."));
        notes.add(signature());
        return new Report(hire ? "legal.hire" : "legal.departure",
                hire ? Texts.t("Declaration of hiring") : Texts.t("Declaration of departure"),
                p.name() + " · " + Texts.day(eventDate), false, tables, notes);
    }

    /** Every hiring and departure of a period, for the employer's own file. */
    public Report movementList(LocalDate from, LocalDate to) {
        List<Row> rows = new ArrayList<>();
        for (Movement m : movements()) {
            if (m.eventDate().isBefore(from) || m.eventDate().isAfter(to)) {
                continue;
            }
            rows.add(Row.of(List.of(m.kind().equals("HIRE") ? Texts.t("Hiring") : Texts.t("Departure"), Texts.day(m.eventDate()),
                    m.registrationNo(), m.name(), m.detail(), Texts.day(m.due()),
                    m.filed() == null ? "" : Texts.day(m.filed().filedOn()), m.filed() == null || m.filed().reference() == null ? "" : m.filed().reference())));
        }
        rows.sort(Comparator.comparing(r -> (String) r.cells().get(1)));
        Table t = new Table(Texts.t("Hirings and departures"), List.of(Column.text(Texts.t("Event")), Column.text(Texts.t("Date")),
                Column.text(Texts.t("Registration no.")), Column.text(Texts.t("Name")), Column.text(Texts.t("Detail")),
                Column.text(Texts.t("Due by")), Column.text(Texts.t("Filed on")), Column.text(Texts.t("Reference"))), rows);
        return new Report("legal.movements", Texts.t("Hirings and departures declared"),
                Texts.t("From %s to %s", Texts.day(from), Texts.day(to)), false, List.of(t), List.of());
    }

    // ---- A3: the annual workforce declaration ------------------------------------------------------------------------

    /** The workforce on 1 January of the year, national and foreign, by category, contract and site, and the foreigners by name. */
    public Report workforce(int year) {
        LocalDate day = LocalDate.of(year, 1, 1);
        String country = company.country();
        List<Person> people = people(day);
        List<Table> tables = new ArrayList<>();
        tables.add(new Table(Texts.t("Employer"), fieldColumns(), employer()));
        tables.add(counts(Texts.t("By grade category"), Texts.t("Grade category"), people, Person::categoryName, country));
        tables.add(counts(Texts.t("By contract type"), Texts.t("Contract type"), people, p -> contractType(p.contractType()), country));
        tables.add(counts(Texts.t("By site"), Texts.t("Site"), people, p -> p.siteName() == null ? Texts.t("No site") : p.siteName(), country));
        Map<Long, LocalDate> permits = workPermits();
        List<Row> foreign = new ArrayList<>();
        for (Person p : people) {
            if (isForeign(p, country)) {
                EmployeeFile.Profile profile = files.profile(p.userId());
                LocalDate permit = permits.get(p.userId());
                foreign.add(Row.of(List.of(p.registrationNo(), p.name(), sex(p.gender()), nz(files.nationality(profile)),
                        nz(p.occupation()), p.categoryName(), Texts.day(p.hireDate()), permit == null ? Texts.t("Not on file") : Texts.day(permit))));
            }
        }
        tables.add(new Table(Texts.t("Foreign workers"), List.of(Column.text(Texts.t("Registration no.")), Column.text(Texts.t("Name")),
                Column.text(Texts.t("Sex")), Column.text(Texts.t("Nationality")), Column.text(Texts.t("Job")),
                Column.text(Texts.t("Grade category")), Column.text(Texts.t("Hiring date")), Column.text(Texts.t("Work card expires"))), foreign));
        List<String> notes = new ArrayList<>(warnings(people, country, permits));
        rule("WORKFORCE").ifPresent(r -> notes.add(r.legalBasis()));
        notes.add(Texts.t("Four copies: two for the labour inspectorate, one for the ONEM, one kept by the employer."));
        notes.add(signature());
        return new Report("legal.workforce", Texts.t("Annual declaration of the workforce %d", year),
                Texts.t("National and foreign workers employed on %s: %d people", Texts.day(day), people.size()), false, tables, notes);
    }

    /** National and foreign workers, women and men, per group, with a total row. */
    private static Table counts(String title, String label, List<Person> people, Function<Person, String> key, String country) {
        Map<String, int[]> by = new TreeMap<>();
        int[] total = new int[6];
        for (Person p : people) {
            int col = (isForeign(p, country) ? 2 : 0) + ("FEMALE".equals(p.gender()) ? 1 : 0);
            int[] c = by.computeIfAbsent(key.apply(p) == null ? "—" : key.apply(p), k -> new int[6]);
            if (p.gender() == null) {
                c[4]++;
                total[4]++;
            } else {
                c[col]++;
                total[col]++;
            }
            c[5]++;
            total[5]++;
        }
        List<Row> rows = new ArrayList<>();
        by.forEach((k, c) -> rows.add(new Row(List.of(k, c[0], c[1], c[2], c[3], c[4], c[5]), "COUNT", 0, null)));
        rows.add(new Row(List.of(Texts.t("Total"), total[0], total[1], total[2], total[3], total[4], total[5]), "TOTAL", 0, null));
        return new Table(title, List.of(Column.text(label), Column.number(Texts.t("Nationals, men")), Column.number(Texts.t("Nationals, women")),
                Column.number(Texts.t("Foreigners, men")), Column.number(Texts.t("Foreigners, women")), Column.number(Texts.t("Sex not given")),
                Column.number(Texts.t("Total"))), rows);
    }

    // ---- A4: the bilan social ----------------------------------------------------------------------------------------

    /**
     * The year's social report. No text found fixes its content; it gathers what such a report usually holds: the
     * headcount and its changes, the structure of the staff, pay, working time and absences.
     */
    public Report bilanSocial(int year) {
        String country = company.country();
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        List<Person> atStart = people(start);
        List<Person> atEnd = people(end);
        List<Table> tables = new ArrayList<>();
        tables.add(new Table(Texts.t("Employer"), fieldColumns(), employer()));

        // 1. Headcount
        BigDecimal average = BigDecimal.ZERO;
        for (int m = 1; m <= 12; m++) {
            average = average.add(BigDecimal.valueOf(people(java.time.YearMonth.of(year, m).atEndOfMonth()).size()));
        }
        average = average.divide(BigDecimal.valueOf(12), 1, RoundingMode.HALF_UP);
        int hires = jdbc.sql("""
                select count(*) from employee_contract c join app_user u on u.id = c.user_id
                where not u.not_employee and c.contract_type <> 'CONSULTANT' and c.start_date between ? and ?
                  and not exists (select 1 from employee_contract p where p.user_id = c.user_id and p.start_date < c.start_date
                                  and not exists (select 1 from departure x where x.user_id = c.user_id and x.status = 'CONFIRMED'
                                                  and x.last_day >= p.start_date and x.last_day < c.start_date))""")
                .params(start, end).query(Integer.class).single();
        Map<String, Integer> reasons = new TreeMap<>();
        jdbc.sql("""
                select x.reason, count(*) as n from departure x join app_user u on u.id = x.user_id
                where x.status = 'CONFIRMED' and x.last_day between ? and ? and not u.not_employee group by x.reason""")
                .params(start, end).query((rs, i) -> reasons.put(reason(rs.getString("reason")), rs.getInt("n"))).list();
        int departures = reasons.values().stream().mapToInt(Integer::intValue).sum();
        List<Row> head = new ArrayList<>();
        head.add(count(Texts.t("Headcount on 1 January"), atStart.size()));
        head.add(count(Texts.t("Headcount on 31 December"), atEnd.size()));
        head.add(Row.of(List.of(Texts.t("Average headcount (12 month ends)"), average)));
        head.add(count(Texts.t("Hirings"), hires));
        head.add(count(Texts.t("Departures"), departures));
        reasons.forEach((r, n) -> head.add(new Row(List.of(r, n), "COUNT", 1, null)));
        head.add(Row.of(List.of(Texts.t("Turnover (departures / average headcount, %)"), average.signum() == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(departures * 100L).divide(average, 1, RoundingMode.HALF_UP))));
        tables.add(new Table(Texts.t("1. Headcount and movements"), List.of(Column.text(Texts.t("Item")), Column.number(Texts.t("Value"))), head));

        // 2. Structure on 31 December
        tables.add(counts(Texts.t("2. Staff on 31 December by grade category"), Texts.t("Grade category"), atEnd, Person::categoryName, country));
        tables.add(counts(Texts.t("3. Staff on 31 December by contract type"), Texts.t("Contract type"), atEnd, p -> contractType(p.contractType()), country));
        tables.add(counts(Texts.t("4. Staff on 31 December by age"), Texts.t("Age"), atEnd, p -> ageBand(p.birthDate(), end), country));
        tables.add(counts(Texts.t("5. Staff on 31 December by seniority"), Texts.t("Seniority"), atEnd, p -> seniorityBand(p.hireDate(), end), country));

        // 3. Pay: closed payslips of the year, per currency and grade category.
        List<Row> pay = new ArrayList<>();
        jdbc.sql("""
                select coalesce(gc.name, '—') as category, trim(p.currency) as currency, count(distinct p.user_id) as people,
                       count(*) filter (where p.kind = 'REGULAR') as payslips, sum(p.gross) as gross,
                       sum(p.employer_charges) as employer, sum(p.net) as net
                from payslip p join payroll_run r on r.id = p.run_id left join grade_category gc on gc.id = p.category_id
                where r.status = 'CLOSED' and extract(year from r.month) = ? and p.number is not null
                group by 1, 2 order by 1, 2""").param(year).query((rs, i) -> {
                    int payslips = rs.getInt("payslips");
                    BigDecimal gross = rs.getBigDecimal("gross");
                    pay.add(Row.of(List.of(rs.getString("category"), rs.getString("currency"), rs.getInt("people"), gross,
                            rs.getBigDecimal("employer"), rs.getBigDecimal("net"),
                            payslips == 0 ? BigDecimal.ZERO : gross.divide(BigDecimal.valueOf(payslips), 2, RoundingMode.HALF_UP))));
                    return null;
                }).list();
        tables.add(new Table(Texts.t("6. Pay (closed months)"), List.of(Column.text(Texts.t("Grade category")), Column.text(Texts.t("Currency")),
                Column.number(Texts.t("People paid")), Column.money(Texts.t("Gross pay")), Column.money(Texts.t("Employer charges")),
                Column.money(Texts.t("Net pay")), Column.money(Texts.t("Average monthly gross"))), pay));

        // 4. Working time and absences.
        List<Row> time = new ArrayList<>();
        BigDecimal overtime = jdbc.sql("""
                select coalesce(sum(approved_minutes), 0) / 60.0 from overtime_decision
                where status = 'APPROVED' and work_date between ? and ?""").params(start, end).query(BigDecimal.class).single()
                .setScale(1, RoundingMode.HALF_UP);
        time.add(Row.of(List.of(Texts.t("Overtime hours approved"), overtime)));
        jdbc.sql("""
                select t.name, sum(r.working_days) as days from leave_request r join leave_type t on t.id = r.leave_type_id
                where r.status = 'APPROVED' and r.start_date between ? and ? group by t.name order by t.name""")
                .params(start, end).query((rs, i) -> time.add(Row.of(List.of(Texts.t("Days of %s", rs.getString("name")),
                        rs.getBigDecimal("days")))))
                .list();
        int evaluations = jdbc.sql("select count(*) from evaluation where status = 'APPROVED' and period_end between ? and ?")
                .params(start, end).query(Integer.class).single();
        time.add(count(Texts.t("Evaluations approved"), evaluations));
        tables.add(new Table(Texts.t("7. Working time, absences and evaluations"), List.of(Column.text(Texts.t("Item")),
                Column.number(Texts.t("Value"))), time));

        List<String> notes = new ArrayList<>(warnings(atEnd, country, Map.of()));
        rule("BILAN_SOCIAL").ifPresent(r -> notes.add(r.legalBasis()));
        notes.add(Texts.t("No official model was found for the bilan social; this content is HR Pay's proposal."));
        notes.add(signature());
        return new Report("legal.bilan", Texts.t("Bilan social %d", year),
                Texts.t("From %s to %s", Texts.day(start), Texts.day(end)), false, tables, notes);
    }

    static String ageBand(LocalDate birth, LocalDate on) {
        if (birth == null) {
            return Texts.t("Not given");
        }
        int age = Period.between(birth, on).getYears();
        return age < 25 ? Texts.t("Under 25") : age < 35 ? "25–34" : age < 45 ? "35–44" : age < 55 ? "45–54" : Texts.t("55 and over");
    }

    static String seniorityBand(LocalDate hired, LocalDate on) {
        if (hired == null) {
            return Texts.t("Not given");
        }
        int years = Period.between(hired, on).getYears();
        return years < 1 ? Texts.t("Under 1 year") : years < 5 ? Texts.t("1 to 4 years") : years < 10 ? Texts.t("5 to 9 years")
                : years < 20 ? Texts.t("10 to 19 years") : Texts.t("20 years and over");
    }

    // ---- A5: the personnel register ----------------------------------------------------------------------------------

    /**
     * The registre d'employeur: part 1 the people and their contracts, numbered in the order they were hired; part 2
     * each month's work, pay and leave; part 3 the labour inspector's visits.
     */
    public Report register(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new BusinessRuleException("The end of the period comes before its start.");
        }
        List<Row> part1 = new ArrayList<>();
        int[] n = {0};
        jdbc.sql("""
                select u.id, u.registration_no, person_name(u.first_name, u.middle_name, u.last_name) as name,
                       min(c.start_date) as first_start, coalesce(u.hire_date, min(c.start_date)) as hire_date
                from app_user u join employee_contract c on c.user_id = u.id
                where not u.not_employee and c.contract_type <> 'CONSULTANT'
                group by u.id having min(c.start_date) <= ?
                order by min(c.start_date), u.id""").param(to).query((rs, i) -> {
                    long userId = rs.getLong("id");
                    n[0]++;
                    Map<String, Object> last = jdbc.sql("""
                            select c.contract_type, c.start_date, c.end_date, g.code as grade, gc.name as category, o.name as occupation
                            from employee_contract c join grade g on g.id = c.grade_id join grade_category gc on gc.id = g.category_id
                            left join occupation o on o.id = c.occupation_id
                            where c.user_id = ? and c.start_date <= ? order by c.start_date desc limit 1""").params(userId, to)
                            .query().singleRow();
                    Optional<Map<String, Object>> gone = jdbc.sql("""
                            select last_day, reason from departure where user_id = ? and status = 'CONFIRMED' and last_day <= ?
                            order by last_day desc limit 1""").params(userId, to).query().listOfRows().stream().findFirst();
                    if (gone.isPresent() && ((java.sql.Date) gone.get().get("last_day")).toLocalDate().isBefore(from)) {
                        return null;  // left before the period: not on this extract, its number kept
                    }
                    EmployeeFile.Profile p = files.profile(userId);
                    part1.add(Row.of(List.of(n[0], rs.getString("registration_no"), rs.getString("name"), sex(p.gender()),
                            join(Texts.day(p.birthDate()), files.birthPlace(p)), nz(files.nationality(p)), nz(files.address(p)),
                            join(idType(p.idDocumentType()), p.idDocumentNumber()), nz((String) last.get("occupation")),
                            last.get("category") + " · " + last.get("grade"), contractType((String) last.get("contract_type")),
                            Texts.day(rs.getDate("hire_date").toLocalDate()),
                            last.get("end_date") == null ? "" : Texts.day(((java.sql.Date) last.get("end_date")).toLocalDate()),
                            gone.map(g -> Texts.day(((java.sql.Date) g.get("last_day")).toLocalDate()) + " · " + reason((String) g.get("reason"))).orElse(""))));
                    return null;
                }).list();
        Table t1 = new Table(Texts.t("Part 1: workers and their contracts"), List.of(Column.number(Texts.t("No.")),
                Column.text(Texts.t("Registration no.")), Column.text(Texts.t("Name")), Column.text(Texts.t("Sex")),
                Column.text(Texts.t("Date and place of birth")), Column.text(Texts.t("Nationality")), Column.text(Texts.t("Address")),
                Column.text(Texts.t("Identity document")), Column.text(Texts.t("Job")), Column.text(Texts.t("Category and grade")),
                Column.text(Texts.t("Contract type")), Column.text(Texts.t("Hiring date")), Column.text(Texts.t("Contract end")),
                Column.text(Texts.t("Departure"))), part1, 0, true);

        List<Row> part2 = new ArrayList<>();
        jdbc.sql("""
                select to_char(r.month, 'YYYY-MM') as month, u.registration_no, person_name(u.first_name, u.middle_name, u.last_name) as name,
                       trim(p.currency) as currency, p.days_worked, p.gross, p.net,
                       coalesce((select sum(lr.working_days) from leave_request lr where lr.user_id = p.user_id and lr.status = 'APPROVED'
                                 and lr.start_date >= r.month and lr.start_date < r.month + interval '1 month'), 0) as leave_days
                from payslip p join payroll_run r on r.id = p.run_id join app_user u on u.id = p.user_id
                where r.status = 'CLOSED' and p.kind = 'REGULAR' and r.month between ? and ?
                order by r.month, u.last_name, u.first_name""").params(from.withDayOfMonth(1), to)
                .query((rs, i) -> part2.add(Row.of(List.of(rs.getString("month"), rs.getString("registration_no"), rs.getString("name"),
                        rs.getInt("days_worked"), rs.getBigDecimal("leave_days"), rs.getString("currency"), rs.getBigDecimal("gross"),
                        rs.getBigDecimal("net"))))).list();
        Table t2 = new Table(Texts.t("Part 2: work, pay and leave"), List.of(Column.text(Texts.t("Month")),
                Column.text(Texts.t("Registration no.")), Column.text(Texts.t("Name")), Column.number(Texts.t("Days worked")),
                Column.number(Texts.t("Leave days")), Column.text(Texts.t("Currency")), Column.money(Texts.t("Gross pay")),
                Column.money(Texts.t("Net pay"))), part2);

        List<Row> part3 = new ArrayList<>();
        for (Visit v : visits()) {
            if (!v.visitedOn().isBefore(from) && !v.visitedOn().isAfter(to)) {
                part3.add(Row.of(List.of(Texts.day(v.visitedOn()), v.inspector(), nz(v.observations()), v.enteredBy())));
            }
        }
        Table t3 = new Table(Texts.t("Part 3: visits of the labour inspector"), List.of(Column.text(Texts.t("Date")),
                Column.text(Texts.t("Inspector")), Column.text(Texts.t("Observations and formal notices")), Column.text(Texts.t("Entered by"))), part3);
        List<String> notes = List.of(
                Texts.t("Kept at the workplace for the labour inspector (Code du travail of the Republic of the Congo, art. 182). Kept five years after the last entry."),
                Texts.t("Lines come from the employee files, contracts, departures and closed payslips; nothing is deleted, a correction is a new entry."));
        return new Report("legal.register", Texts.t("Personnel register (registre d'employeur)"),
                Texts.t("From %s to %s", Texts.day(from), Texts.day(to)), false, List.of(t1, t2, t3), notes);
    }

    public List<Visit> visits() {
        return jdbc.sql("select id, visited_on, inspector, observations, entered_by, entered_at from register_visit order by visited_on desc, id desc")
                .query(Visit.class).list();
    }

    @Transactional
    public Visit addVisit(LocalDate visitedOn, String inspector, String observations) {
        if (visitedOn == null || visitedOn.isAfter(LocalDate.now(clock))) {
            throw new BusinessRuleException("Give the date of the visit, today or before.");
        }
        if (inspector == null || inspector.isBlank()) {
            throw new BusinessRuleException("Give the inspector's name.");
        }
        long id = jdbc.sql("insert into register_visit (visited_on, inspector, observations, entered_by) values (?, ?, ?, ?) returning id")
                .params(visitedOn, inspector.trim(), blank(observations), CurrentUser.email()).query(Long.class).single();
        audit.record("register.visit_added", "register_visit", id, Map.of("visitedOn", visitedOn.toString(), "inspector", inspector.trim()));
        return visits().stream().filter(v -> v.id() == id).findFirst().orElseThrow();
    }

    // ---- shared pieces -----------------------------------------------------------------------------------------------

    /** The people under contract on a day (consultants and people who are not employees left out). */
    List<Person> people(LocalDate day) {
        return jdbc.sql(PERSON + " order by u.last_name, u.first_name").param("d", day).query(Person.class).list();
    }

    /** The latest work card expiry on file for each foreign worker. */
    private Map<Long, LocalDate> workPermits() {
        Map<Long, LocalDate> out = new LinkedHashMap<>();
        jdbc.sql("""
                select d.user_id, max(d.expires_on) as expires from employee_document d join document_type t on t.id = d.type_id
                where t.code = 'WORK_PERMIT' group by d.user_id""")
                .query((rs, i) -> out.put(rs.getLong("user_id"), rs.getDate("expires") == null ? null : rs.getDate("expires").toLocalDate()))
                .list();
        return out;
    }

    private static List<String> warnings(List<Person> people, String country, Map<Long, LocalDate> permits) {
        List<String> out = new ArrayList<>();
        long noSex = people.stream().filter(p -> p.gender() == null).count();
        long noNationality = people.stream().filter(p -> p.nationalityCode() == null).count();
        if (noSex > 0) {
            out.add(Texts.t("To complete: %d people have no sex in their employee file.", noSex));
        }
        if (noNationality > 0) {
            out.add(Texts.t("To complete: %d people have no nationality in their employee file; they are counted as nationals unless their contract says expatriate.", noNationality));
        }
        if (!permits.isEmpty() || people.stream().anyMatch(p -> isForeign(p, country))) {
            long missing = people.stream().filter(p -> isForeign(p, country) && permits.get(p.userId()) == null).count();
            if (missing > 0) {
                out.add(Texts.t("To complete: %d foreign workers have no work card on file.", missing));
            }
        }
        return out;
    }

    static boolean isForeign(Person p, String country) {
        return p.nationalityCode() != null ? !p.nationalityCode().equals(country) : p.expatriate();
    }

    List<Row> employer() {
        Company.Details d = company.get();
        List<Row> rows = new ArrayList<>();
        field(rows, Texts.t("Employer"), d.name());
        field(rows, Texts.t("Address"), d.address());
        field(rows, Texts.t("Branch of activity"), jdbc.sql("select activity from company where id = current_company_id()")
                .query(String.class).optional().orElse(null));
        field(rows, Texts.t("Employer's social security no."), d.cnssNumber());
        field(rows, Texts.t("Tax number"), d.taxNumber());
        field(rows, Texts.t("RCCM"), d.rccm());
        if ("CD".equals(company.country())) {
            field(rows, Texts.t("ONEM number"), d.onemNumber());
            field(rows, Texts.t("INPP number"), d.inppNumber());
        }
        return rows;
    }

    String signature() {
        Company.Representative r = company.representative();
        String place = r == null || r.signingPlace() == null ? "……………" : r.signingPlace();
        String who = r == null || r.name() == null ? "" : r.name() + (r.title() == null ? "" : ", " + r.title());
        return Texts.t("Done at %s, on %s. For the employer: %s (signature and stamp).", place, Texts.day(LocalDate.now(clock)), who);
    }

    private String agreedPay(long userId, LocalDate on) {
        return jdbc.sql("""
                select to_char(gross_monthly, 'FM999G999G999D00') || ' ' || currency from employee_salary
                where user_id = ? and effective_from <= ? order by effective_from desc limit 1""")
                .params(userId, on).query(String.class).optional().orElse(null);
    }

    static List<Column> fieldColumns() {
        return List.of(Column.text(Texts.t("Item")), Column.text(Texts.t("Value")));
    }

    static void field(List<Row> rows, String label, String value) {
        rows.add(Row.of(List.of(label, value == null || value.isBlank() ? "—" : value)));
    }

    private static Row count(String label, int n) {
        return new Row(List.of(label, n), "COUNT", 0, null);
    }

    private static String join(String a, String b) {
        boolean ha = a != null && !a.isBlank();
        boolean hb = b != null && !b.isBlank();
        return ha && hb ? a + ", " + b : ha ? a : hb ? b : "";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static String sex(String gender) {
        return gender == null ? "" : gender.equals("FEMALE") ? Texts.t("Female") : Texts.t("Male");
    }

    static String contractType(String type) {
        return switch (type == null ? "" : type) {
            case "PERMANENT" -> Texts.t("Permanent");
            case "FIXED_TERM" -> Texts.t("Fixed term");
            case "INTERN" -> Texts.t("Intern");
            case "CONSULTANT" -> Texts.t("Consultant");
            default -> type == null ? "" : type;
        };
    }

    static String reason(String reason) {
        return switch (reason == null ? "" : reason) {
            case "RESIGNATION" -> Texts.t("Resignation");
            case "DISMISSAL" -> Texts.t("Dismissal");
            case "GROSS_MISCONDUCT" -> Texts.t("Dismissal for gross misconduct");
            case "REDUNDANCY" -> Texts.t("Redundancy");
            case "END_OF_CONTRACT" -> Texts.t("End of contract");
            case "MUTUAL_AGREEMENT" -> Texts.t("Mutual agreement");
            case "RETIREMENT" -> Texts.t("Retirement");
            case "DEATH" -> Texts.t("Death");
            default -> reason == null ? "" : reason;
        };
    }

    private static String marital(String s) {
        return switch (s == null ? "" : s) {
            case "SINGLE" -> Texts.t("Single");
            case "MARRIED" -> Texts.t("Married");
            case "DIVORCED" -> Texts.t("Divorced");
            case "WIDOWED" -> Texts.t("Widowed");
            default -> s;
        };
    }

    private static String idType(String s) {
        return switch (s == null ? "" : s) {
            case "NATIONAL_ID" -> Texts.t("National identity card");
            case "ELECTOR_CARD" -> Texts.t("Voter's card");
            case "PASSPORT" -> Texts.t("Passport");
            case "RESIDENCE_PERMIT" -> Texts.t("Residence permit");
            case "DRIVING_LICENCE" -> Texts.t("Driving licence");
            case "OTHER" -> Texts.t("Other document");
            case "" -> null;
            default -> s;
        };
    }
}
