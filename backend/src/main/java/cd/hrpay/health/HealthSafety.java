package cd.hrpay.health;

import cd.hrpay.i18n.Texts;
import cd.hrpay.payment.Company;
import cd.hrpay.payroll.report.Report;
import cd.hrpay.payroll.report.Report.Column;
import cd.hrpay.payroll.report.Report.Row;
import cd.hrpay.payroll.report.Report.Table;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The health and safety page as a whole: the deadlines it puts on the declarations calendar and the committee's yearly
 * report (arrêté 043/2008), built from the meetings, inspection walks, actions, accidents and medical visits.
 */
@Service
public class HealthSafety {

    /** What was saved, and what HR should know about it. */
    public record Saved<T>(T item, List<String> warnings) {}

    /** A dated obligation: an accident to declare, a yearly report to send. */
    public record Deadline(String kind, String title, LocalDate due, long daysLeft, String legalBasis, boolean verified) {}

    private final MedicalVisits visits;
    private final WorkAccidents accidents;
    private final SafetyCommittee committee;
    private final Company company;
    private final Clock clock;

    public HealthSafety(MedicalVisits visits, WorkAccidents accidents, SafetyCommittee committee, Company company, Clock clock) {
        this.visits = visits;
        this.accidents = accidents;
        this.committee = committee;
        this.company = company;
        this.clock = clock;
    }

    /** The open health and safety deadlines, the soonest first. */
    public List<Deadline> deadlines() {
        LocalDate today = LocalDate.now(clock);
        boolean cd = "CD".equals(company.country());
        List<Deadline> out = new ArrayList<>();
        for (WorkAccidents.Accident a : accidents.undeclared()) {
            out.add(new Deadline("ACCIDENT", Texts.t("%s: %s of %s", WorkAccidents.kindName(a.kind()), a.name(),
                    Texts.day(a.occurredAt().atZoneSameInstant(WorkAccidents.ZONE).toLocalDate())), a.declareBy(), a.daysLeft(),
                    cd ? Texts.t("Declaration to the CNSS, copy to the labour inspection and the victim (Loi 16/009 art. 65)")
                            : Texts.t("Declaration to the CNSS and the labour inspector within 48 hours (to verify)"), cd));
        }
        int lastYear = today.getYear() - 1;
        LocalDate due = LocalDate.of(today.getYear(), 3, 31);
        if (cd && committee.required() && !committee.reportSent("REPORT", lastYear)) {
            out.add(new Deadline("SAFETY_REPORT", Texts.t("Yearly report of the health and safety committee %d", lastYear), due,
                    ChronoUnit.DAYS.between(today, due), Texts.t("To the Minister through the labour inspection (arrêté 043/2008)"), true));
        }
        if (cd && !committee.reportSent("MEDICAL_REPORT", lastYear)) {
            out.add(new Deadline("MEDICAL_REPORT", Texts.t("Yearly activity report of the medical service %d", lastYear), due,
                    ChronoUnit.DAYS.between(today, due), Texts.t("Written by the doctor in the first quarter (arrêté 01/76, to verify)"), false));
        }
        out.sort(Comparator.comparing(Deadline::due));
        return out;
    }

    /** The committee's yearly report: members, meetings, inspection walks, actions, accidents and medical visits. */
    public Report yearlyReport(int year) {
        LocalDate from = LocalDate.of(year, 1, 1);
        LocalDate to = LocalDate.of(year, 12, 31);
        List<Table> tables = new ArrayList<>();
        SafetyCommittee.Overview o = committee.overview();
        List<Row> members = new ArrayList<>();
        for (SafetyCommittee.Member m : o.members()) {
            if (!m.fromOn().isAfter(to) && !(m.endedOn() != null ? m.endedOn() : m.termEnd()).isBefore(from)) {
                members.add(Row.of(List.of(m.name(), roleName(m.role()), Texts.day(m.fromOn()),
                        Texts.day(m.endedOn() != null ? m.endedOn() : m.termEnd()))));
            }
        }
        tables.add(new Table(Texts.t("Members"), List.of(Column.text(Texts.t("Name")), Column.text(Texts.t("Role")),
                Column.text(Texts.t("From")), Column.text(Texts.t("Until"))), members));
        List<Row> events = new ArrayList<>();
        int meetings = 0;
        int walks = 0;
        for (SafetyCommittee.Event e : o.events().reversed()) {
            if (e.heldOn().isBefore(from) || e.heldOn().isAfter(to) || e.kind().endsWith("REPORT")) {
                continue;
            }
            if ("MEETING".equals(e.kind())) {
                meetings++;
            } else {
                walks++;
            }
            events.add(Row.of(List.of(Texts.day(e.heldOn()), "MEETING".equals(e.kind()) ? Texts.t("Meeting") : Texts.t("Inspection walk"),
                    e.note() == null ? e.title() : e.title() + " — " + e.note())));
        }
        tables.add(new Table(Texts.t("Meetings and inspection walks"), List.of(Column.text(Texts.t("Date")),
                Column.text(Texts.t("What")), Column.text(Texts.t("Subjects and findings"))), events));
        List<Row> actions = new ArrayList<>();
        for (SafetyCommittee.Action a : o.actions()) {
            boolean inYear = a.doneOn() == null || (!a.doneOn().isBefore(from) && !a.doneOn().isAfter(to));
            if (inYear) {
                actions.add(Row.of(List.of(a.risk(), a.action(), a.ownerName() == null ? "" : a.ownerName(), Texts.day(a.dueOn()),
                        a.doneOn() == null ? Texts.t("Open") : Texts.t("Done on %s", Texts.day(a.doneOn())))));
            }
        }
        tables.add(new Table(Texts.t("Actions"), List.of(Column.text(Texts.t("Risk found")), Column.text(Texts.t("Action")),
                Column.text(Texts.t("Who")), Column.text(Texts.t("Due")), Column.text(Texts.t("Status"))), actions));
        WorkAccidents.Figures f = accidents.figures(year);
        List<Row> acc = new ArrayList<>();
        for (WorkAccidents.Accident a : accidents.list(year).reversed()) {
            acc.add(new Row(List.of(Texts.day(a.occurredAt().atZoneSameInstant(WorkAccidents.ZONE).toLocalDate()), a.name(),
                    a.unit() == null ? "" : a.unit(), WorkAccidents.kindName(a.kind()), a.daysOff(),
                    a.declaredOn() == null ? Texts.t("Not declared") : Texts.day(a.declaredOn())), "COUNT", 0, null));
        }
        acc.add(new Row(List.of(Texts.t("Total"), "", "", Texts.t("%d accident(s)", f.accidents()), f.daysLost(), ""), "TOTAL", 0, null));
        tables.add(new Table(Texts.t("Work accidents"), List.of(Column.text(Texts.t("Date")), Column.text(Texts.t("Name")),
                Column.text(Texts.t("Unit")), Column.text(Texts.t("Kind")), Column.number(Texts.t("Days off")),
                Column.text(Texts.t("Declared to the CNSS"))), acc));
        List<Row> rates = new ArrayList<>();
        rates.add(Row.of(List.of(Texts.t("Accidents with time off"), String.valueOf(f.withTimeOff()))));
        rates.add(Row.of(List.of(Texts.t("Days lost"), String.valueOf(f.daysLost()))));
        rates.add(Row.of(List.of(Texts.t("Hours worked (estimate)"), f.hoursWorked().toPlainString())));
        rates.add(Row.of(List.of(Texts.t("Frequency rate (per million hours)"), f.frequencyRate() == null ? "—" : f.frequencyRate().toPlainString())));
        rates.add(Row.of(List.of(Texts.t("Severity rate (days per thousand hours)"), f.severityRate() == null ? "—" : f.severityRate().toPlainString())));
        int done = visits.held(year);
        rates.add(Row.of(List.of(Texts.t("Medical visits held"), String.valueOf(done))));
        tables.add(new Table(Texts.t("Figures"), List.of(Column.text(Texts.t("Item")), Column.text(Texts.t("Value"))), rates));
        List<String> notes = new ArrayList<>();
        notes.add(Texts.t("%d meeting(s) and %d inspection walk(s) in the year; the committee meets at least every two months and inspects the workplace every month (arrêté 043/2008).",
                meetings, walks));
        notes.add(Texts.t("Sent to the Minister through the labour inspection by 31 March of the next year."));
        notes.add(Texts.t("The president of the committee: name, date and signature."));
        return new Report("health.cshe_report", Texts.t("Yearly report of the health and safety committee %d", year),
                Texts.t("%d workers on %s", f.headcount(), Texts.day(to.isAfter(LocalDate.now(clock)) ? LocalDate.now(clock) : to)),
                to.isAfter(LocalDate.now(clock)), tables, notes);
    }

    static String roleName(String role) {
        return switch (role) {
            case "PRESIDENT" -> Texts.t("President (employer)");
            case "SECRETARY" -> Texts.t("Secretary (safety head)");
            case "DOCTOR" -> Texts.t("Doctor");
            case "TRAINING" -> Texts.t("Training head");
            default -> Texts.t("Worker member");
        };
    }
}
