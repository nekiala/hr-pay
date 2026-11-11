package cd.hrpay.career;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.lifecycle.Letters;
import cd.hrpay.notify.Notifications;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.payment.Company;
import cd.hrpay.payroll.Departures;
import cd.hrpay.payroll.EmployeeFile;
import cd.hrpay.salary.SalaryService;
import cd.hrpay.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The follow-up of trials and fixed-term contracts: who comes to the end of a trial or of a contract, the reminders
 * before it, and HR's decision (confirm, end the trial, renew, make permanent, let the contract end).
 *
 * <p>DRC Code du travail art. 43: work continued after the trial confirms the contract, so a trial that ends with no
 * decision is recorded as confirmed by law. Art. 71: either party ends a trial with 3 working days' notice, none in the
 * first 3 days. Art. 41 (checked when the contract is written): a fixed-term contract lasts at most two years and is
 * renewed once; work continued after its end makes the contract permanent. Art. 40: a day worker hired again after
 * 22 days of work in two months is under a permanent contract.
 */
@Service
public class ContractFollowup {

    public static final Set<String> KINDS = Set.of("TRIAL", "FIXED_TERM");
    public static final String CONFIRMATION_LETTER = "CONFIRMATION";

    /** One trial or fixed-term contract coming to its end, and what was decided. */
    public record Item(long userId, String name, String registrationNo, String unit, long contractId, String kind,
                       String contractType, LocalDate startDate, LocalDate dueOn, Integer trialMonths, long daysLeft,
                       String decision, LocalDate decidedOn, String decidedBy, String note, Long letterId,
                       Long newContractId, String departureStatus, LocalDate departureLastDay, boolean workedAfter) {}

    /** A day worker and the days they worked in the window (art. 40). */
    public record DayWorker(long userId, String name, String registrationNo, LocalDate contractStart, int daysWorked,
                            int warnAt, int maxDays, int windowMonths) {}

    public record Overview(List<Item> items, List<DayWorker> dayWorkers, int horizonDays) {}

    /** What a decision did, and what HR should do next. */
    public record Result(Item item, List<String> warnings, Long departureUserId, Long contractId, Long letterId) {}

    private final JdbcClient jdbc;
    private final EmployeeFile files;
    private final SalaryService salaries;
    private final Departures departures;
    private final Letters letters;
    private final Notifications notifications;
    private final OrgStructure org;
    private final Company company;
    private final AuditService audit;
    private final Clock clock;

    public ContractFollowup(JdbcClient jdbc, EmployeeFile files, SalaryService salaries, Departures departures,
                            Letters letters, Notifications notifications, OrgStructure org, Company company,
                            AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.files = files;
        this.salaries = salaries;
        this.departures = departures;
        this.letters = letters;
        this.notifications = notifications;
        this.org = org;
        this.company = company;
        this.audit = audit;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private String setting(String key, String fallback) {
        return jdbc.sql("select value from payroll_setting where key = ?").param(key).query(String.class).optional()
                .filter(v -> !v.isBlank()).orElse(fallback);
    }

    private int intSetting(String key, int fallback) {
        try {
            return Integer.parseInt(setting(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Days before the end at which reminders go out, largest first. */
    List<Integer> remindDays(String key, String fallback) {
        TreeSet<Integer> days = new TreeSet<>(java.util.Comparator.reverseOrder());
        for (String p : setting(key, fallback).split(",")) {
            try {
                int d = Integer.parseInt(p.trim());
                if (d > 0 && d <= 365) {
                    days.add(d);
                }
            } catch (NumberFormatException ignored) {
                // a setting typed wrong is skipped
            }
        }
        return new ArrayList<>(days);
    }

    // ---- the list ------------------------------------------------------------------------------------------------

    /**
     * Trials (the first contract's trial) and fixed-term contracts (the person's latest contract, avenants included)
     * of people still with the company. The end of a trial is its start plus the trial months, less one day.
     */
    private static final String ITEMS = """
            with base as (
                select c.user_id, c.id as contract_id, 'TRIAL' as kind, c.contract_type, c.start_date,
                       (c.start_date + make_interval(months => t.trial_months) - interval '1 day')::date as due_on,
                       t.trial_months
                from employee_contract c join contract_terms t on t.contract_id = c.id
                where t.trial_months > 0
                union all
                select c.user_id, c.id, 'FIXED_TERM', c.contract_type, c.start_date, c.end_date, null
                from employee_contract c
                where c.contract_type = 'FIXED_TERM' and c.end_date is not null
                  and c.start_date = (select max(x.start_date) from employee_contract x where x.user_id = c.user_id)
            )
            select b.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no,
                   (select ou.name from org_assignment a join org_unit ou on ou.id = a.unit_id
                    where a.user_id = b.user_id and a.valid_to is null order by a.valid_from desc limit 1) as unit,
                   b.contract_id, b.kind, b.contract_type, b.start_date, b.due_on, b.trial_months,
                   (b.due_on - cast(:today as date)) as days_left,
                   f.decision, f.decided_on, f.decided_by, f.note, f.letter_id, f.new_contract_id,
                   d.status as departure_status, d.last_day as departure_last_day,
                   exists (select 1 from clock_event e where e.user_id = b.user_id and e.work_date > b.due_on) as worked_after
            from base b
            join app_user u on u.id = b.user_id
            left join contract_followup f on f.user_id = b.user_id and f.kind = b.kind and f.due_on = b.due_on
            left join departure d on d.user_id = b.user_id
            where not u.not_employee
            """;

    public Overview overview(int horizonDays) {
        int horizon = Math.max(1, Math.min(horizonDays, 366));
        LocalDate today = today();
        List<Item> items = jdbc.sql(ITEMS + """
                  and (u.status = 'ACTIVE' or f.id is not null)
                  and (
                       (f.id is null and d.id is null and b.due_on <= cast(:today as date) + :horizon)
                    or (f.id is null and d.id is not null and d.last_day >= b.due_on and b.due_on >= cast(:today as date) - 60)
                    or (f.decided_on >= cast(:today as date) - 60)
                  )
                order by (f.id is not null), b.due_on, name""")
                .param("today", today).param("horizon", horizon).query(Item.class).list();
        return new Overview(items, dayWorkers(today), horizon);
    }

    /** The trials and fixed-term contracts of one person, decided or not. */
    public List<Item> of(long userId) {
        return jdbc.sql(ITEMS + " and b.user_id = :user order by b.due_on desc")
                .param("today", today()).param("user", userId).query(Item.class).list();
    }

    private Item item(long userId, String kind, long contractId) {
        return jdbc.sql(ITEMS + " and b.user_id = :user and b.kind = :kind and b.contract_id = :contract")
                .param("today", today()).param("user", userId).param("kind", kind).param("contract", contractId)
                .query(Item.class).optional()
                .orElseThrow(() -> new NotFoundException(kind.equals("TRIAL")
                        ? "This contract has no trial period." : "This is not the person's current fixed-term contract."));
    }

    private Item undecided(long userId, String kind, long contractId) {
        Item i = item(userId, kind, contractId);
        if (i.decision() != null) {
            throw new ConflictException(Texts.t("A decision was already recorded on %s.", Texts.day(i.decidedOn())));
        }
        return i;
    }

    // ---- day workers (art. 40) -----------------------------------------------------------------------------------

    /** Days with a check-in in the window before a day (the window's months, up to the day). */
    public int daysWorked(long userId, LocalDate upTo) {
        int months = intSetting("DAILY_WORKER_WINDOW_MONTHS", 2);
        return jdbc.sql("select count(distinct work_date) from clock_event where user_id = ? and work_date > ? and work_date <= ?")
                .params(userId, upTo.minusMonths(months), upTo).query(Integer.class).single();
    }

    List<DayWorker> dayWorkers(LocalDate today) {
        int warn = intSetting("DAILY_WORKER_WARN_DAYS", 18);
        int max = intSetting("DAILY_WORKER_MAX_DAYS", 22);
        int months = intSetting("DAILY_WORKER_WINDOW_MONTHS", 2);
        return jdbc.sql("""
                select c.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no,
                       c.start_date as contract_start,
                       (select count(distinct e.work_date) from clock_event e where e.user_id = c.user_id
                          and e.work_date > cast(:today as date) - make_interval(months => :months) and e.work_date <= :today)::int as days_worked,
                       :warn as warn_at, :max as max_days, :months as window_months
                from employee_contract c join app_user u on u.id = c.user_id
                where c.contract_type = 'DAILY' and u.status = 'ACTIVE' and c.start_date <= :today
                  and (c.end_date is null or c.end_date >= cast(:today as date) - make_interval(months => :months))
                  and c.start_date = (select max(x.start_date) from employee_contract x where x.user_id = c.user_id)
                order by days_worked desc, name""")
                .param("today", today).param("months", months).param("warn", warn).param("max", max)
                .query(DayWorker.class).list();
    }

    // ---- decisions -----------------------------------------------------------------------------------------------

    private void record(Item i, String decision, String note, Long letterId, Long newContractId) {
        jdbc.sql("""
                insert into contract_followup (user_id, contract_id, kind, due_on, decision, decided_on, decided_by, note,
                                               letter_id, new_contract_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(i.userId(), i.contractId(), i.kind(), i.dueOn(), decision, today(), CurrentUser.email(),
                        clean(note), letterId, newContractId)
                .update();
        audit.record("contract.followup", "user", i.userId(), Map.of("kind", i.kind(), "decision", decision,
                "due", i.dueOn().toString()));
    }

    /** The trial was conclusive: the contract goes on, and the confirmation letter is ready as a draft. */
    @Transactional
    public Result confirm(long userId, long contractId, String note) {
        Item i = undecided(userId, "TRIAL", contractId);
        notSelf(userId);
        Long letterId = null;
        List<String> warnings = new ArrayList<>();
        Letters.Template t = letters.templates().stream()
                .filter(x -> x.code().equals(CONFIRMATION_LETTER) && x.active()).findFirst().orElse(null);
        if (t != null) {
            Letters.Draft d = letters.preview(userId, t.id(), contractId);
            letterId = letters.create(userId, t.id(), d.title(), d.body(), t.kind(), t.requiresSignature()).id();
            warnings.add(Texts.t("The confirmation letter is ready as a draft: read it and send it from Documents and letters."));
        } else {
            warnings.add(Texts.t("No confirmation letter model is active: write the letter on Documents and letters."));
        }
        record(i, "CONFIRMED", note, letterId, null);
        return new Result(item(userId, "TRIAL", contractId), warnings, null, null, letterId);
    }

    /**
     * Ends the trial: a departure "end of trial" is saved as a draft for HR to check and confirm. The last day falls
     * within the trial; notice is due after the first 3 days (DRC art. 71, 3 working days).
     */
    @Transactional
    public Result endTrial(long userId, long contractId, LocalDate lastDay, String givenBy, String note) {
        Item i = undecided(userId, "TRIAL", contractId);
        notSelf(userId);
        if (lastDay == null) {
            throw new BusinessRuleException("Give the last day.");
        }
        if (lastDay.isBefore(i.startDate()) || lastDay.isAfter(i.dueOn())) {
            throw new BusinessRuleException(Texts.t("The trial runs from %s to %s: the last day falls within it. After it, the contract is confirmed (Labour Code art. 43).",
                    Texts.day(i.startDate()), Texts.day(i.dueOn())));
        }
        if (givenBy != null && !Set.of("EMPLOYER", "EMPLOYEE").contains(givenBy)) {
            throw new BusinessRuleException("Say who ends the trial.");
        }
        departures.save(userId, new Departures.Input(lastDay, "END_OF_TRIAL", today().isAfter(lastDay) ? lastDay : today(),
                givenBy == null ? "EMPLOYER" : givenBy, null, null, null, null, null, clean(note)));
        record(i, "ENDED", note, null, null);
        return new Result(item(userId, "TRIAL", contractId),
                List.of(Texts.t("The departure is saved as a draft: check the settlement and confirm it on the Departure page.")),
                userId, null, null);
    }

    /** Renews the fixed-term contract from the day after it ends, with the same grade, occupation and basic salary. */
    @Transactional
    public Result renew(long userId, long contractId, LocalDate newEnd, String note) {
        Item i = undecided(userId, "FIXED_TERM", contractId);
        if (newEnd == null || !newEnd.isAfter(i.dueOn())) {
            throw new BusinessRuleException("Give the new end date, after the current one.");
        }
        if (i.departureStatus() != null) {
            throw new ConflictException("A departure is recorded for this person: cancel it first.");
        }
        EmployeeFile.Contract current = contract(userId, contractId);
        LocalDate start = i.dueOn().plusDays(1);
        LocalDate first = files.contracts(userId).stream()
                .filter(c -> "FIXED_TERM".equals(c.contractType()))
                .map(EmployeeFile.Contract::startDate).min(LocalDate::compareTo).orElse(current.startDate());
        List<String> warnings = new ArrayList<>();
        if (newEnd.isAfter(first.plusYears(2).minusDays(1)) && "CD".equals(company.country())) {
            warnings.add(Texts.t("The fixed-term contracts with this person would run beyond two years since %s: unless the work is one of the exceptions of arrêté 063/2011, the contract becomes permanent (Labour Code art. 41).",
                    Texts.day(first)));
        }
        BigDecimal basic = salaries.on(userId, i.dueOn()).map(SalaryService.Salary::grossMonthly).orElse(null);
        EmployeeFile.Contract c = files.startContract(userId, "FIXED_TERM", start, newEnd, current.gradeId(),
                current.occupationId(), current.expatriate(), basic, note == null || note.isBlank() ? Texts.t("Renewal") : note.strip());
        copyTerms(current.id(), c.id());
        record(i, "RENEWED", note, null, c.id());
        warnings.add(Texts.t("Check the new contract's terms (reason, place) and send the renewal to sign. A second renewal is told to the ONEM and the labour inspectorate within 15 days (arrêté 063/2011 art. 3)."));
        return new Result(decided(i, "RENEWED", note, null, c.id()), warnings, null, c.id(), null);
    }

    /** Turns the fixed-term contract into a permanent one, from a day (the day after its end by default). */
    @Transactional
    public Result makePermanent(long userId, long contractId, LocalDate from, String note) {
        Item i = undecided(userId, "FIXED_TERM", contractId);
        if (i.departureStatus() != null) {
            throw new ConflictException("A departure is recorded for this person: cancel it first.");
        }
        EmployeeFile.Contract current = contract(userId, contractId);
        LocalDate start = from == null ? i.dueOn().plusDays(1) : from;
        if (!start.isAfter(current.startDate()) || start.isAfter(i.dueOn().plusDays(1))) {
            throw new BusinessRuleException(Texts.t("The permanent contract starts after %s and no later than %s.",
                    Texts.day(current.startDate()), Texts.day(i.dueOn().plusDays(1))));
        }
        BigDecimal basic = salaries.on(userId, start.minusDays(1)).map(SalaryService.Salary::grossMonthly).orElse(null);
        EmployeeFile.Contract c = files.startContract(userId, "PERMANENT", start, null, current.gradeId(),
                current.occupationId(), current.expatriate(), basic,
                note == null || note.isBlank() ? Texts.t("Fixed-term contract made permanent") : note.strip());
        copyTerms(current.id(), c.id());
        jdbc.sql("""
                update contract_terms set fixed_term_reason = null, fixed_term_detail = null, permanent_job = false,
                    trial_months = 0 where contract_id = ?""").param(c.id()).update();
        record(i, "MADE_PERMANENT", note, null, c.id());
        return new Result(decided(i, "MADE_PERMANENT", note, null, c.id()),
                List.of(Texts.t("Seniority runs from the first contract. Write the permanent contract or an avenant and send it to sign.")),
                null, c.id(), null);
    }

    /** Lets the fixed-term contract end on its date: a departure "end of contract" is saved as a draft. */
    @Transactional
    public Result letEnd(long userId, long contractId, String note) {
        Item i = undecided(userId, "FIXED_TERM", contractId);
        notSelf(userId);
        if (i.departureStatus() == null) {
            departures.save(userId, new Departures.Input(i.dueOn(), "END_OF_CONTRACT", null, null, "NONE", null, null,
                    null, null, clean(note)));
        }
        record(i, "ENDED", note, null, null);
        return new Result(item(userId, "FIXED_TERM", contractId),
                List.of(Texts.t("The departure is saved as a draft: check the settlement and confirm it on the Departure page.")),
                userId, null, null);
    }

    /** Takes a decision back while nothing was built on it (no confirmed departure, no new contract). */
    @Transactional
    public void undo(long userId, String kind, long contractId) {
        Item i = item(userId, kind, contractId);
        if (i.decision() == null) {
            throw new NotFoundException("There is no decision to take back.");
        }
        if (i.newContractId() != null) {
            throw new ConflictException("A new contract was started from this decision: correct the contracts on the employee file.");
        }
        if ("CONFIRMED".equals(i.departureStatus()) && "ENDED".equals(i.decision())) {
            throw new ConflictException("The departure is confirmed: cancel it first.");
        }
        jdbc.sql("delete from contract_followup where user_id = ? and kind = ? and due_on = ?")
                .params(userId, kind, i.dueOn()).update();
        audit.record("contract.followup_undone", "user", userId, Map.of("kind", kind, "decision", i.decision()));
    }

    /** The item as decided: after a renewal the person's current contract is another one, so it is built here. */
    private Item decided(Item i, String decision, String note, Long letterId, Long newContractId) {
        return new Item(i.userId(), i.name(), i.registrationNo(), i.unit(), i.contractId(), i.kind(), i.contractType(),
                i.startDate(), i.dueOn(), i.trialMonths(), i.daysLeft(), decision, today(), CurrentUser.email(), clean(note),
                letterId, newContractId, i.departureStatus(), i.departureLastDay(), i.workedAfter());
    }

    private EmployeeFile.Contract contract(long userId, long contractId) {
        return files.contracts(userId).stream().filter(c -> c.id() == contractId).findFirst()
                .orElseThrow(() -> new NotFoundException("Contract not found."));
    }

    private void copyTerms(long from, long to) {
        jdbc.sql("""
                insert into contract_terms (contract_id, trial_months, unskilled_labourer, fixed_term_reason, fixed_term_detail,
                    permanent_job, work_place, supervisor, signed_place, medical_fit_on, medical_doctor, updated_by)
                select ?, 0, unskilled_labourer, fixed_term_reason, fixed_term_detail, permanent_job, work_place, supervisor,
                       signed_place, medical_fit_on, medical_doctor, ?
                from contract_terms where contract_id = ?
                on conflict do nothing""").params(to, CurrentUser.email(), from).update();
    }

    private static void notSelf(long userId) {
        if (Objects.equals(CurrentUser.id(), userId)) {
            throw new org.springframework.security.access.AccessDeniedException("You cannot decide on your own contract.");
        }
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : (s.strip().length() > 300 ? s.strip().substring(0, 300) : s.strip());
    }

    // ---- the daily job -------------------------------------------------------------------------------------------

    /** Reminders, trials confirmed by law, fixed-term contracts that ran out, day workers near the limit. */
    @Transactional
    public int run() {
        LocalDate today = today();
        int done = 0;
        List<Item> open = jdbc.sql(ITEMS + """
                  and u.status = 'ACTIVE' and f.id is null
                  and b.due_on between cast(:today as date) - 400 and cast(:today as date) + 400""")
                .param("today", today).query(Item.class).list();
        List<Integer> trialDays = remindDays("TRIAL_REMIND_DAYS", "30,15,5");
        List<Integer> cddDays = remindDays("CDD_REMIND_DAYS", "60,30,15");
        for (Item i : open) {
            boolean trial = i.kind().equals("TRIAL");
            boolean leaving = i.departureStatus() != null && i.departureLastDay() != null && !i.departureLastDay().isAfter(i.dueOn());
            if (leaving) {
                continue;
            }
            if (i.daysLeft() < 0) {
                if (trial) {
                    jdbc.sql("""
                            insert into contract_followup (user_id, contract_id, kind, due_on, decision, decided_on, decided_by, note)
                            values (?, ?, 'TRIAL', ?, 'CONFIRMED_BY_LAW', ?, 'system', ?) on conflict do nothing""")
                            .params(i.userId(), i.contractId(), i.dueOn(), today,
                                    Texts.in(company.language(), () -> Texts.t("The trial ended with no decision: the contract is confirmed (Labour Code art. 43).")))
                            .update();
                    audit.record("contract.followup", "user", i.userId(), Map.of("kind", "TRIAL", "decision", "CONFIRMED_BY_LAW",
                            "due", i.dueOn().toString()));
                    if (i.daysLeft() < -30) {
                        continue;   // long past (first run on old files): recorded without a message
                    }
                    tellHr(i, "followup.trial.law", today, () -> Texts.t("Trial confirmed by law: %s", i.name()),
                            () -> Texts.t("The trial of %s ended on %s with no decision: the contract is confirmed (Labour Code art. 43).",
                                    i.name(), Texts.day(i.dueOn())));
                    done++;
                } else if (once(i.userId(), "followup.cdd.over", i.dueOn())) {
                    // Once: the contract ran out and nobody decided; work continued after it makes it permanent.
                    tellHr(i, null, null, () -> Texts.t("Fixed-term contract ended: %s", i.name()),
                            () -> i.workedAfter()
                                    ? Texts.t("The contract of %s ended on %s and the person still works: the contract is now permanent. Record it (Make permanent) or end the contract.",
                                            i.name(), Texts.day(i.dueOn()))
                                    : Texts.t("The contract of %s ended on %s with no decision. Renew it, make it permanent or let it end.",
                                            i.name(), Texts.day(i.dueOn())));
                    done++;
                }
                continue;
            }
            for (int d : trial ? trialDays : cddDays) {
                if (i.daysLeft() <= d && i.daysLeft() > nextBelow(trial ? trialDays : cddDays, d)) {
                    String kind = (trial ? "followup.trial." : "followup.cdd.") + d;
                    if (once(i.userId(), kind, i.dueOn())) {
                        tellHr(i, null, null,
                                () -> trial ? Texts.t("Trial ends on %s: %s", Texts.day(i.dueOn()), i.name())
                                        : Texts.t("Fixed-term contract ends on %s: %s", Texts.day(i.dueOn()), i.name()),
                                () -> trial ? Texts.t("Confirm the contract or end the trial before %s; with no decision the contract is confirmed (Labour Code art. 43).",
                                        Texts.day(i.dueOn()))
                                        : Texts.t("Renew the contract, make it permanent or let it end; work continued after %s makes it permanent.",
                                        Texts.day(i.dueOn())));
                        org.managerOf(i.userId()).ifPresent(b -> notifications.send(b.userId(), "followup.head",
                                () -> trial ? Texts.t("Trial of %s ends on %s", i.name(), Texts.day(i.dueOn()))
                                        : Texts.t("Contract of %s ends on %s", i.name(), Texts.day(i.dueOn())),
                                () -> Texts.t("Tell HR whether the work is satisfactory."), null));
                        done++;
                    }
                    break;
                }
            }
        }
        for (DayWorker w : dayWorkers(today)) {
            if (w.daysWorked() >= w.warnAt() && once(w.userId(), "followup.daily", today.withDayOfMonth(1))) {
                notifications.sendToHolders("lifecycle.manage", "followup.daily",
                        () -> Texts.t("Day worker near the limit: %s", w.name()),
                        () -> Texts.t("%s worked %d days in the last %d months. From %d days, hiring them again means a permanent contract (Labour Code art. 40).",
                                w.name(), w.daysWorked(), w.windowMonths(), w.maxDays()), "/lifecycle");
                done++;
            }
        }
        return done;
    }

    private static int nextBelow(List<Integer> days, int d) {
        return days.stream().filter(x -> x < d).max(Integer::compare).orElse(-1);
    }

    /** Records a reminder; false when it was already sent. */
    private boolean once(long userId, String kind, LocalDate day) {
        return jdbc.sql("insert into reminder_sent (user_id, kind, on_day) values (?, ?, ?) on conflict do nothing")
                .params(userId, kind, day).update() > 0;
    }

    private void tellHr(Item i, String kind, LocalDate day, java.util.function.Supplier<String> title,
                        java.util.function.Supplier<String> body) {
        if (kind != null && !once(i.userId(), kind, day)) {
            return;
        }
        notifications.sendToHolders("lifecycle.manage", "followup", title, body, "/lifecycle");
    }
}
