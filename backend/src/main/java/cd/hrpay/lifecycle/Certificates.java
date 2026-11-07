package cd.hrpay.lifecycle;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.Notifications;
import cd.hrpay.payment.Company;
import cd.hrpay.payroll.EmployeeFile;
import cd.hrpay.salary.SalaryService;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Certificates an employee asks for: the attestation de travail (they work here, since when, as what) and the
 * salary certificate (their gross salary and the net paid in the last months). The employee asks, HR issues it in
 * one click (or refuses with a reason), and the numbered PDF lands in the employee's documents.
 */
@Service
public class Certificates {

    public static final Set<String> KINDS = Set.of("WORK", "SALARY");

    public record Request(long id, long userId, String employeeName, String registrationNo, String kind, String purpose,
                          String status, String number, Long documentId, String requestedBy, OffsetDateTime requestedAt,
                          String decidedBy, OffsetDateTime decidedAt, String reason) {}

    private static final String SELECT = """
            select c.id, c.user_id, u.first_name || ' ' || u.last_name as employee_name, u.registration_no, c.kind, c.purpose,
                   c.status, c.number, c.document_id, c.requested_by, c.requested_at, c.decided_by, c.decided_at, c.reason
            from certificate_request c join app_user u on u.id = c.user_id""";

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final EmployeeFile files;
    private final SalaryService salaries;
    private final Company company;
    private final Documents documents;
    private final LifecyclePdf pdf;
    private final Notifications notifications;
    private final AuditService audit;
    private final Clock clock;

    public Certificates(JdbcClient jdbc, UserRepository users, EmployeeFile files, SalaryService salaries, Company company,
                        Documents documents, LifecyclePdf pdf, Notifications notifications, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.files = files;
        this.salaries = salaries;
        this.company = company;
        this.documents = documents;
        this.pdf = pdf;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    public List<Request> of(long userId) {
        return jdbc.sql(SELECT + " where c.user_id = ? order by c.requested_at desc").param(userId).query(Request.class).list();
    }

    public List<Request> pending() {
        return jdbc.sql(SELECT + " where c.status = 'PENDING' order by c.requested_at").query(Request.class).list();
    }

    public Request find(long id) {
        return jdbc.sql(SELECT + " where c.id = ?").param(id).query(Request.class).optional()
                .orElseThrow(() -> new NotFoundException("Request not found."));
    }

    /** The employee asks; one open request of each kind at a time. */
    @Transactional
    public Request request(AppUser user, String kind, String purpose) {
        check(kind);
        int open = jdbc.sql("select count(*) from certificate_request where user_id = ? and kind = ? and status = 'PENDING'")
                .params(user.getId(), kind).query(Integer.class).single();
        if (open > 0) {
            throw new ConflictException("You already asked for this certificate; HR has not answered yet.");
        }
        long id = jdbc.sql("insert into certificate_request (user_id, kind, purpose, requested_by) values (?, ?, ?, ?) returning id")
                .params(user.getId(), kind, clean(purpose), user.getEmail()).query(Long.class).single();
        audit.record("certificate.requested", "user", user.getId(), Map.of("request", id, "kind", kind));
        String who = user.getFirstName() + " " + user.getLastName();
        notifications.sendToHolders("lifecycle.manage", "certificate.requested", () -> Texts.t("Certificate requested"),
                () -> Texts.t("%s asks for a %s.", who, kindName(kind)), "/lifecycle");
        return find(id);
    }

    @Transactional
    public Request cancel(long id, AppUser user) {
        Request r = find(id);
        if (r.userId() != user.getId() || !r.status().equals("PENDING")) {
            throw new BusinessRuleException("This request cannot be withdrawn.");
        }
        jdbc.sql("update certificate_request set status = 'CANCELLED', decided_at = now() where id = ?").param(id).update();
        return find(id);
    }

    /** HR issues the certificate asked for. */
    @Transactional
    public Request issue(long requestId) {
        Request r = find(requestId);
        if (!r.status().equals("PENDING")) {
            throw new BusinessRuleException("This request was answered already.");
        }
        return make(requestId, r.userId(), r.kind());
    }

    /** HR issues a certificate nobody asked for in HR Pay (asked at the desk, for instance). */
    @Transactional
    public Request issueDirect(long userId, String kind, String purpose) {
        check(kind);
        long id = jdbc.sql("insert into certificate_request (user_id, kind, purpose, requested_by) values (?, ?, ?, ?) returning id")
                .params(userId, kind, clean(purpose), CurrentUser.email()).query(Long.class).single();
        return make(id, userId, kind);
    }

    @Transactional
    public Request reject(long requestId, String reason) {
        Request r = find(requestId);
        if (!r.status().equals("PENDING")) {
            throw new BusinessRuleException("This request was answered already.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Say why the certificate is refused.");
        }
        jdbc.sql("update certificate_request set status = 'REJECTED', decided_by = ?, decided_at = now(), reason = ? where id = ?")
                .params(CurrentUser.email(), reason.trim(), requestId).update();
        audit.record("certificate.rejected", "user", r.userId(), Map.of("request", requestId));
        notifications.send(r.userId(), "certificate.rejected", () -> Texts.t("Certificate refused"),
                () -> Texts.t("Your request for a %s was refused: %s", kindName(r.kind()), reason.trim()), "/my/documents");
        return find(requestId);
    }

    private Request make(long requestId, long userId, String kind) {
        AppUser u = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found."));
        String prefix = kind.equals("WORK") ? "AT" : "AS";
        int year = LocalDate.now(clock).getYear();
        int n = jdbc.sql("select count(*) from certificate_request where number like ?").param(prefix + "-" + year + "-%")
                .query(Integer.class).single() + 1;
        String number = "%s-%d-%04d".formatted(prefix, year, n);
        byte[] file = Texts.in(company.language(), () -> kind.equals("WORK") ? work(u, number) : salary(u, number));
        long doc = documents.add(userId, documents.typeId("CERTIFICATE"), number + ".pdf", file, LocalDate.now(clock), null,
                Texts.in(company.language(), () -> title(kind)), "CERTIFICATE").id();
        jdbc.sql("""
                update certificate_request set status = 'ISSUED', number = ?, document_id = ?, decided_by = ?, decided_at = now()
                where id = ?""").params(number, doc, CurrentUser.email(), requestId).update();
        audit.record("certificate.issued", "user", userId, Map.of("request", requestId, "number", number, "kind", kind));
        notifications.send(userId, "certificate.issued", () -> Texts.t("Certificate ready"),
                () -> Texts.t("Your %s (%s) is in your documents.", kindName(kind), number), "/my/documents");
        return find(requestId);
    }

    private static String title(String kind) {
        return kind.equals("WORK") ? Texts.t("WORK CERTIFICATE") : Texts.t("SALARY CERTIFICATE");
    }

    static String kindName(String kind) {
        return kind.equals("WORK") ? Texts.t("work certificate") : Texts.t("salary certificate");
    }

    private byte[] work(AppUser u, String number) {
        LocalDate today = LocalDate.now(clock);
        if (departed(u.getId(), today)) {
            throw new BusinessRuleException("This person has left: make the certificate of employment from the departure.");
        }
        Company.Details employer = company.get();
        var contract = files.contractOn(u.getId(), today).orElse(null);
        LocalDate from = hired(u);
        StringBuilder body = new StringBuilder(Texts.t("We, %s, certify that %s, registration number %s, has been working for our company since %s",
                employer.name(), u.getFirstName() + " " + u.getLastName(), u.getRegistrationNo(), from == null ? "…" : Texts.day(from)));
        if (contract != null) {
            if (contract.occupationName() != null) {
                body.append(Texts.t(" as %s", contract.occupationName()));
            }
            body.append(Texts.t(", under a %s", Letters.contractType(contract.contractType())));
            if (contract.endDate() != null) {
                body.append(Texts.t(" until %s", Texts.day(contract.endDate())));
            }
        }
        body.append(". ").append(Texts.t("The person is still in our employment on the date of this certificate."));
        body.append("\n\n").append(Texts.t("This certificate is issued at the person's request, to serve where needed."));
        return pdf.letter(title("WORK"), number, body.toString(), Texts.t("Done on %s.", Texts.day(today)),
                List.of(new LifecyclePdf.Signature(signer(), Texts.t("For the employer"))));
    }

    private byte[] salary(AppUser u, String number) {
        LocalDate today = LocalDate.now(clock);
        Company.Details employer = company.get();
        var current = salaries.on(u.getId(), today)
                .orElseThrow(() -> new BusinessRuleException("This person has no salary on file."));
        StringBuilder body = new StringBuilder(Texts.t("We, %s, certify that %s, registration number %s, employed by our company since %s, receives a gross monthly salary of %s %s.",
                employer.name(), u.getFirstName() + " " + u.getLastName(), u.getRegistrationNo(),
                hired(u) == null ? "…" : Texts.day(hired(u)), Letters.amount(current.grossMonthly()), current.currency()));
        record Month(LocalDate month, BigDecimal gross, BigDecimal net, String currency) {}
        List<Month> months = jdbc.sql("""
                select r.month, sum(p.gross) as gross, sum(p.net) as net, min(p.currency) as currency
                from payslip p join payroll_run r on r.id = p.run_id
                where p.user_id = ? and r.status = 'CLOSED' and (p.kind = 'REGULAR' or p.approved_at is not null)
                group by r.month order by r.month desc limit 3""").param(u.getId()).query(Month.class).list();
        if (!months.isEmpty()) {
            body.append("\n\n").append(Texts.t("Pay of the last months:"));
            List<String> lines = new ArrayList<>();
            for (Month m : months.reversed()) {
                lines.add(Texts.t("%s: gross %s %s, net paid %s %s", Texts.month(YearMonth.from(m.month())),
                        Letters.amount(m.gross()), m.currency(), Letters.amount(m.net()), m.currency()));
            }
            body.append("\n").append(String.join("\n", lines));
        }
        body.append("\n\n").append(Texts.t("This certificate is issued at the person's request, to serve where needed."));
        return pdf.letter(title("SALARY"), number, body.toString(), Texts.t("Done on %s.", Texts.day(today)),
                List.of(new LifecyclePdf.Signature(signer(), Texts.t("For the employer"))));
    }

    private boolean departed(long userId, LocalDate today) {
        return jdbc.sql("select count(*) from departure where user_id = ? and status = 'CONFIRMED' and last_day < ?")
                .params(userId, today).query(Integer.class).single() > 0;
    }

    private LocalDate hired(AppUser u) {
        return u.getHireDate() != null ? u.getHireDate()
                : files.contracts(u.getId()).stream().map(EmployeeFile.Contract::startDate).min(LocalDate::compareTo).orElse(null);
    }

    private String signer() {
        Long me = CurrentUser.id();
        return me == null ? " " : users.findById(me).map(s -> s.getFirstName() + " " + s.getLastName()).orElse(" ");
    }

    private static void check(String kind) {
        if (!KINDS.contains(kind)) {
            throw new BusinessRuleException("Ask for a work certificate or a salary certificate.");
        }
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
