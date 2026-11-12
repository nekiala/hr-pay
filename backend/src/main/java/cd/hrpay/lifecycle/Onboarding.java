package cd.hrpay.lifecycle;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.AppProperties;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.NotificationService;
import cd.hrpay.notify.Notifications;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.payment.Company;
import cd.hrpay.payroll.EmployeeFile;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.security.DataCrypto;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Onboarding, from the accepted offer to the end of the trial. Before day one the new hire fills their details from a
 * one-time link (no account, decision 5); HR checks what they sent before it goes into the employee file. Each hiring
 * step has an owner and a due date. The manager may name a buddy. On day one the account opens on a welcome page.
 * During the trial the manager writes two short reviews: after one month, and two weeks before the end (Code art. 43).
 */
@Service
public class Onboarding {

    public static final long MAX_FILE = 5L * 1024 * 1024;
    private static final Set<String> FILE_TYPES = Set.of("application/pdf", "image/jpeg", "image/png");
    private static final ZoneId ZONE = ZoneId.of("Africa/Kinshasa");

    /** What the new hire types on the link. Everything is optional: HR copies what is there into the file. */
    public record Answers(LocalDate birthDate, String gender, String maritalStatus, String nationalityCode,
                          String addressQuarter, String addressStreet, String idDocumentType, String idDocumentNumber,
                          LocalDate idDocumentIssuedOn, String idDocumentIssuedAt, String cnssNumber, String paymentMethod,
                          String bankName, String bankAccount, String mobileMoneyOperator, String mobileMoneyNumber,
                          List<FamilyMember> family) {}

    public record FamilyMember(String name, String relationship, LocalDate birthDate) {}

    public record DocType(long id, String name, boolean received) {}

    public record SentDocument(long id, long documentTypeId, String typeName, String fileName, String status, String reason,
                               OffsetDateTime uploadedAt, boolean hasExpiry) {}

    /** The page behind the link. */
    public record PreStart(String company, String firstName, LocalDate startOn, List<DocType> documents, Answers answers,
                           List<SentDocument> sent, OffsetDateTime submittedAt, String language) {}

    public record Row(long userId, String name, String registrationNo, String unit, LocalDate startOn, String status,
                      OffsetDateTime linkSentAt, OffsetDateTime submittedAt, OffsetDateTime detailsAppliedAt, int pending,
                      String buddy, int lateSteps, int openSteps) {}

    public record Detail(Row row, Long buddyUserId, Answers answers, List<SentDocument> documents,
                         Checklists.Checklist checklist, List<Review> reviews, String link, LocalDate linkExpiresOn) {}

    public record Review(long id, long userId, String name, String kind, LocalDate dueOn, LocalDate trialEndsOn,
                         Long reviewerId, String reviewer, String status, Integer rating, String strengths, String improvements,
                         String recommendation, String comment, String doneBy, OffsetDateTime doneAt) {}

    public record Welcome(String firstName, LocalDate startOn, String manager, String buddy, String unit, String job,
                          List<Checklists.Item> steps, String text, boolean seen) {}

    public record Overview(int open, int lateSteps, int waitingCheck, int trialsConfirmed, int trialsEnded,
                           int reviewsLate) {}

    private final JdbcClient jdbc;
    private final Checklists checklists;
    private final Documents documents;
    private final EmployeeFile files;
    private final OrgStructure org;
    private final Notifications notifications;
    private final NotificationService mail;
    private final AuditService audit;
    private final Company company;
    private final DataCrypto crypto;
    private final JsonMapper json;
    private final AppProperties properties;
    private final Clock clock;

    public Onboarding(JdbcClient jdbc, Checklists checklists, Documents documents, EmployeeFile files, OrgStructure org,
                      Notifications notifications, NotificationService mail, AuditService audit, Company company,
                      DataCrypto crypto, JsonMapper json, AppProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.checklists = checklists;
        this.documents = documents;
        this.files = files;
        this.org = org;
        this.notifications = notifications;
        this.mail = mail;
        this.audit = audit;
        this.company = company;
        this.crypto = crypto;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    private String base() {
        return properties.publicUrl() == null ? "" : properties.publicUrl().replaceAll("/+$", "");
    }

    static boolean isHr() {
        return CurrentUser.has("lifecycle.manage");
    }

    private void requireHr() {
        if (!isHr()) {
            throw new AccessDeniedException("Only HR can do this.");
        }
    }

    /** HR, or the person's manager (who names the buddy and reviews the trial). */
    private void requireHrOrManager(long userId) {
        if (isHr()) {
            return;
        }
        Long me = CurrentUser.id();
        boolean manager = me != null && org.managerOf(userId).map(b -> b.userId() == me).orElse(false);
        if (!manager) {
            throw new AccessDeniedException("Only HR or the manager can do this.");
        }
    }

    // ---- starting ----------------------------------------------------------------------------------------------

    /**
     * Opens the onboarding of a new hire starting on a date, with the hiring checklist (if not open yet) and the trial
     * reviews of the contract that starts then. Called by the hire from Recruitment, or by HR for anyone hired.
     */
    @Transactional
    public Detail start(long userId, LocalDate startOn) {
        if (startOn == null) {
            throw new BusinessRuleException("Give the start date.");
        }
        Long existing = jdbc.sql("select id from onboarding where user_id = ?").param(userId).query(Long.class).optional().orElse(null);
        if (existing == null) {
            jdbc.sql("insert into onboarding (user_id, start_on, created_by) values (?, ?, ?)")
                    .params(userId, startOn, CurrentUser.email()).update();
        } else {
            jdbc.sql("update onboarding set start_on = ?, status = 'OPEN', closed_at = null where id = ?").params(startOn, existing).update();
        }
        try {
            checklists.start(userId, "HIRING", startOn);
        } catch (ConflictException ignored) {
            // already open: its steps keep their dates
        }
        planReviews(userId);
        audit.record("onboarding.started", "user", userId, Map.of("start", startOn.toString()));
        return detail(userId);
    }

    /** The two trial reviews of the person's contract with a trial period: after a month, and two weeks before the end. */
    void planReviews(long userId) {
        record Trial(long contractId, LocalDate startDate, int trialMonths) {}
        jdbc.sql("""
                select c.id as contract_id, c.start_date, t.trial_months from employee_contract c
                join contract_terms t on t.contract_id = c.id
                where c.user_id = ? and t.trial_months > 0 order by c.start_date desc limit 1""")
                .param(userId).query(Trial.class).optional().ifPresent(t -> {
                    LocalDate ends = t.startDate().plusMonths(t.trialMonths()).minusDays(1);
                    Long reviewer = org.managerOf(userId).map(OrgStructure.Boss::userId).orElse(null);
                    if (t.trialMonths() > 1) {
                        review(userId, t.contractId(), "MID", t.startDate().plusMonths(1), ends, reviewer);
                    }
                    LocalDate end = ends.minusDays(14);
                    review(userId, t.contractId(), "END", end.isBefore(t.startDate()) ? t.startDate() : end, ends, reviewer);
                });
    }

    private void review(long userId, long contractId, String kind, LocalDate due, LocalDate ends, Long reviewer) {
        jdbc.sql("""
                insert into trial_review (user_id, contract_id, kind, due_on, trial_ends_on, reviewer_id) values (?, ?, ?, ?, ?, ?)
                on conflict (contract_id, kind) do nothing""").params(userId, contractId, kind, due, ends, reviewer).update();
    }

    // ---- the one-time link -------------------------------------------------------------------------------------

    /** A new link (the old one stops working), sent by email to the new hire. Valid until the start date. */
    @Transactional
    public Detail sendLink(long userId) {
        requireHr();
        record O(long id, LocalDate startOn, String status) {}
        O o = jdbc.sql("select id, start_on, status from onboarding where user_id = ?").param(userId).query(O.class).optional()
                .orElseThrow(() -> new NotFoundException("Start the onboarding first."));
        if (!"OPEN".equals(o.status())) {
            throw new ConflictException("This onboarding is closed.");
        }
        if (o.startOn().isBefore(today())) {
            throw new ConflictException("The start date has passed: the employee now fills their file from their account.");
        }
        record Person(String email, String firstName) {}
        Person p = jdbc.sql("select email, first_name from app_user where id = ?").param(userId).query(Person.class).single();
        if (p.email() == null) {
            throw new BusinessRuleException("The employee has no email address.");
        }
        UUID token = UUID.randomUUID();
        jdbc.sql("update onboarding set token = ?, token_expires_on = ?, link_sent_at = now() where id = ?")
                .params(token, o.startOn(), o.id()).update();
        String link = base() + "/welcome-form/" + token;
        String name = company.get().name();
        Texts.run(company.language(), () -> mail.emailAddress(p.email(),
                Texts.t("Before your first day at %s", name),
                Texts.t("Dear %s,\n\nWelcome to %s. Before your first day (%s), please fill in your details and send your papers from this link: %s\n\nThe link works until your first day. It needs no password; do not share it.\n\n%s",
                        p.firstName(), name, Texts.day(o.startOn()), link, name)));
        audit.record("onboarding.link_sent", "user", userId, Map.of());
        return detail(userId);
    }

    /** The company of a link (read across companies). */
    public Long companyOfToken(UUID token) {
        return jdbc.sql("select company_id from onboarding where token = ?").param(token).query(Long.class).optional().orElse(null);
    }

    private record Link(long id, long userId, LocalDate startOn, OffsetDateTime submittedAt) {}

    private Link link(UUID token) {
        Link l = jdbc.sql("""
                select id, user_id, start_on, submitted_at from onboarding
                where token = ? and status = 'OPEN' and token_expires_on >= ?""").params(token, today()).query(Link.class)
                .optional().orElseThrow(() -> new NotFoundException("This link is no longer valid. Ask HR for a new one."));
        return l;
    }

    public PreStart preStart(UUID token) {
        Link l = link(token);
        String first = jdbc.sql("select first_name from app_user where id = ?").param(l.userId()).query(String.class).single();
        return new PreStart(company.get().name(), first, l.startOn(), wantedDocuments(l.userId()), answers(l.id()), sent(l.id()),
                l.submittedAt(), company.language());
    }

    /** The papers the hiring checklist asks for, and whether the file already holds them. */
    List<DocType> wantedDocuments(long userId) {
        return jdbc.sql("""
                select distinct t.id, t.name,
                       exists (select 1 from employee_document d where d.user_id = c.user_id and d.type_id = t.id
                               and d.deleted_at is null) as received
                from employee_checklist c join employee_checklist_item i on i.checklist_id = c.id
                join document_type t on t.id = i.document_type_id
                where c.user_id = ? and c.kind = 'HIRING' and c.status = 'OPEN' and i.action = 'DOCUMENT'
                order by t.name""").param(userId).query(DocType.class).list();
    }

    @Transactional
    public PreStart saveAnswers(UUID token, Answers a, boolean submit) {
        Link l = link(token);
        String plain = json.writeValueAsString(a);
        jdbc.sql("update onboarding set answers = ? where id = ?").params(crypto.encrypt(plain), l.id()).update();
        if (submit) {
            jdbc.sql("update onboarding set submitted_at = now() where id = ?").param(l.id()).update();
            String name = jdbc.sql("select person_name(first_name, middle_name, last_name) from app_user where id = ?")
                    .param(l.userId()).query(String.class).single();
            notifications.sendToHolders("lifecycle.manage", "onboarding.submitted",
                    () -> Texts.t("New hire's details to check: %s", name),
                    () -> Texts.t("%s sent their details and papers before their first day.", name),
                    "/onboarding?user=" + l.userId());
        }
        return preStart(token);
    }

    @Transactional
    public PreStart upload(UUID token, long typeId, String fileName, String contentType, byte[] data) {
        Link l = link(token);
        if (wantedDocuments(l.userId()).stream().noneMatch(d -> d.id() == typeId)) {
            throw new BusinessRuleException("This paper is not asked for.");
        }
        checkFile(data, contentType);
        jdbc.sql("""
                insert into onboarding_document (onboarding_id, document_type_id, file_name, content_type, data)
                values (?, ?, ?, ?, ?)""").params(l.id(), typeId, cleanName(fileName), contentType, data).update();
        return preStart(token);
    }

    @Transactional
    public PreStart removeUpload(UUID token, long documentId) {
        Link l = link(token);
        jdbc.sql("delete from onboarding_document where id = ? and onboarding_id = ? and status = 'PENDING'")
                .params(documentId, l.id()).update();
        return preStart(token);
    }

    static void checkFile(byte[] data, String type) {
        if (data == null || data.length == 0) {
            throw new BusinessRuleException("The file is empty.");
        }
        if (data.length > MAX_FILE) {
            throw new BusinessRuleException("The file is larger than 5 MB.");
        }
        if (type == null || !FILE_TYPES.contains(type)) {
            throw new BusinessRuleException("Send a PDF, JPEG or PNG file.");
        }
        boolean pdf = data.length > 4 && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F';
        boolean jpeg = data.length > 3 && (data[0] & 0xff) == 0xFF && (data[1] & 0xff) == 0xD8;
        boolean png = data.length > 4 && (data[0] & 0xff) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
        if (!(pdf || jpeg || png)) {
            throw new BusinessRuleException("Send a PDF, JPEG or PNG file.");
        }
    }

    private static String cleanName(String name) {
        String n = name == null || name.isBlank() ? "document" : name.replaceAll("[\\\\/:*?\"<>|]", "_").strip();
        return n.length() > 200 ? n.substring(0, 200) : n;
    }

    private Answers answers(long onboardingId) {
        String stored = jdbc.sql("select answers from onboarding where id = ?").param(onboardingId).query(String.class)
                .optional().orElse(null);
        if (stored == null) {
            return null;
        }
        return json.readValue(crypto.decrypt(stored), Answers.class);
    }

    private List<SentDocument> sent(long onboardingId) {
        return jdbc.sql("""
                select d.id, d.document_type_id, t.name as type_name, d.file_name, d.status, d.reason, d.uploaded_at, t.has_expiry
                from onboarding_document d join document_type t on t.id = d.document_type_id
                where d.onboarding_id = ? order by d.uploaded_at""").param(onboardingId).query(SentDocument.class).list();
    }

    // ---- HR ----------------------------------------------------------------------------------------------------

    private static final String ROW_SELECT = """
            select o.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no,
                   (select ou.name from org_assignment a join org_unit ou on ou.id = a.unit_id
                     where a.user_id = o.user_id and a.valid_to is null limit 1) as unit,
                   o.start_on, o.status, o.link_sent_at, o.submitted_at, o.details_applied_at,
                   (select count(*) from onboarding_document d where d.onboarding_id = o.id and d.status = 'PENDING')::int as pending,
                   (select person_name(b.first_name, b.middle_name, b.last_name) from app_user b where b.id = o.buddy_user_id) as buddy,
                   (select count(*) from employee_checklist c join employee_checklist_item i on i.checklist_id = c.id
                     where c.user_id = o.user_id and c.kind = 'HIRING' and c.status = 'OPEN' and i.done_at is null
                       and i.due_on < :today)::int as late_steps,
                   (select count(*) from employee_checklist c join employee_checklist_item i on i.checklist_id = c.id
                     where c.user_id = o.user_id and c.kind = 'HIRING' and c.status = 'OPEN' and i.done_at is null)::int as open_steps
            from onboarding o join app_user u on u.id = o.user_id""";

    /** HR sees every onboarding; a manager sees the new hires of their team. */
    public List<Row> list(boolean closedToo) {
        List<Row> rows = jdbc.sql(ROW_SELECT + (closedToo ? "" : " where o.status = 'OPEN'") + " order by o.start_on desc limit 300")
                .param("today", today()).query(Row.class).list();
        if (isHr()) {
            return rows;
        }
        Long me = CurrentUser.id();
        return rows.stream().filter(r -> me != null && org.managerOf(r.userId()).map(b -> b.userId() == me).orElse(false)).toList();
    }

    public Detail detail(long userId) {
        requireHrOrManager(userId);
        Row row = jdbc.sql(ROW_SELECT + " where o.user_id = :user").param("today", today()).param("user", userId)
                .query(Row.class).optional().orElseThrow(() -> new NotFoundException("No onboarding for this person."));
        record X(long id, Long buddyUserId, UUID token, LocalDate tokenExpiresOn) {}
        X x = jdbc.sql("select id, buddy_user_id, token, token_expires_on from onboarding where user_id = ?").param(userId)
                .query(X.class).single();
        boolean hr = isHr();
        Long checklistId = jdbc.sql("""
                select id from employee_checklist where user_id = ? and kind = 'HIRING' order by status = 'OPEN' desc, started_at desc limit 1""")
                .param(userId).query(Long.class).optional().orElse(null);
        String link = hr && x.token() != null && x.tokenExpiresOn() != null && !x.tokenExpiresOn().isBefore(today())
                ? base() + "/welcome-form/" + x.token() : null;
        return new Detail(row, x.buddyUserId(), hr ? answers(x.id()) : null, hr ? sent(x.id()) : List.of(),
                checklistId == null ? null : checklists.find(checklistId), reviewsOf(userId), link,
                link == null ? null : x.tokenExpiresOn());
    }

    /** HR copies what the new hire typed into the employee file; empty answers keep what the file has. */
    @Transactional
    public Detail applyDetails(long userId) {
        requireHr();
        long id = onboardingId(userId);
        Answers a = answers(id);
        if (a == null) {
            throw new ConflictException("The new hire has not sent any details yet.");
        }
        EmployeeFile.Profile p = files.profile(userId);
        String bankName = or(a.bankName(), p.bankName());
        String bankAccount = or(a.bankAccount(), p.bankAccount());
        String operator = or(a.mobileMoneyOperator(), p.mobileMoneyOperator());
        String mobile = or(a.mobileMoneyNumber(), p.mobileMoneyNumber());
        String method = or(a.paymentMethod(), p.paymentMethod());
        // A new file says "bank" with no account yet: until the account is known, the pay is in cash.
        if (("BANK".equals(method) && (bankName == null || bankAccount == null))
                || ("MOBILE_MONEY".equals(method) && (operator == null || mobile == null))) {
            method = "CASH";
        }
        files.saveProfile(userId, new EmployeeFile.Profile(or(a.birthDate(), p.birthDate()), or(a.gender(), p.gender()),
                p.nationality(), or(a.maritalStatus(), p.maritalStatus()), or(a.cnssNumber(), p.cnssNumber()), p.taxNumber(),
                method, bankName, bankAccount, operator, mobile,
                p.commune(), p.nursingBreakUntil(), or(a.nationalityCode(), p.nationalityCode()), p.birthCountryCode(),
                p.birthPlaceId(), p.birthPlaceText(), p.addressPlaceId(), or(a.addressQuarter(), p.addressQuarter()),
                or(a.addressStreet(), p.addressStreet()), a.bankName() != null ? null : p.bankId(),
                a.mobileMoneyOperator() != null ? null : p.mobileMoneyOperatorId(), or(a.idDocumentType(), p.idDocumentType()),
                or(a.idDocumentNumber(), p.idDocumentNumber()), or(a.idDocumentIssuedOn(), p.idDocumentIssuedOn()),
                or(a.idDocumentIssuedAt(), p.idDocumentIssuedAt())));
        if (a.family() != null) {
            Set<String> known = new java.util.HashSet<>();
            files.dependants(userId).forEach(d -> known.add(d.fullName().strip().toLowerCase()));
            for (FamilyMember m : a.family()) {
                if (m.name() == null || m.name().isBlank() || m.relationship() == null || known.contains(m.name().strip().toLowerCase())) {
                    continue;
                }
                files.addDependant(userId, m.name().strip(), m.relationship(), m.birthDate(), true, false, false,
                        Texts.t("From the form before the first day"));
            }
        }
        jdbc.sql("update onboarding set details_applied_at = now(), details_applied_by = ? where id = ?")
                .params(CurrentUser.email(), id).update();
        audit.record("onboarding.details_applied", "user", userId, Map.of());
        return detail(userId);
    }

    private static <T> T or(T value, T fallback) {
        if (value instanceof String s && s.isBlank()) {
            return fallback;
        }
        return value != null ? value : fallback;
    }

    private long onboardingId(long userId) {
        return jdbc.sql("select id from onboarding where user_id = ?").param(userId).query(Long.class).optional()
                .orElseThrow(() -> new NotFoundException("No onboarding for this person."));
    }

    /** A paper the new hire sent: accepted, it goes into the employee file; refused, they are told why. */
    @Transactional
    public Detail check(long documentId, boolean accept, String reason, LocalDate expiresOn) {
        requireHr();
        record D(long id, long userId, long typeId, String fileName, String contentType, byte[] data, String status) {}
        D d = jdbc.sql("""
                select d.id, o.user_id, d.document_type_id as type_id, d.file_name, d.content_type, d.data, d.status
                from onboarding_document d join onboarding o on o.id = d.onboarding_id where d.id = ?""")
                .param(documentId).query(D.class).optional().orElseThrow(() -> new NotFoundException("Document not found."));
        if (!"PENDING".equals(d.status())) {
            throw new ConflictException("This paper was already checked.");
        }
        if (accept) {
            documents.add(d.userId(), d.typeId(), d.fileName(), d.data(), null, expiresOn, Texts.t("Sent before the first day"), "HR");
            // The file now holds it: the copy here is no longer needed.
            jdbc.sql("update onboarding_document set status = 'ACCEPTED', data = '\\x'::bytea, checked_by = ?, checked_at = now() where id = ?")
                    .params(CurrentUser.email(), documentId).update();
        } else {
            if (reason == null || reason.isBlank()) {
                throw new BusinessRuleException("Say why the paper is refused: the new hire reads it.");
            }
            jdbc.sql("update onboarding_document set status = 'REJECTED', reason = ?, data = '\\x'::bytea, checked_by = ?, checked_at = now() where id = ?")
                    .params(reason.strip(), CurrentUser.email(), documentId).update();
        }
        return detail(d.userId());
    }

    public Documents.File sentFile(long documentId) {
        requireHr();
        record F(String fileName, String contentType, byte[] data) {}
        F f = jdbc.sql("select file_name, content_type, data from onboarding_document where id = ? and status = 'PENDING'")
                .param(documentId).query(F.class).optional().orElseThrow(() -> new NotFoundException("Document not found."));
        return new Documents.File(f.fileName(), f.contentType(), f.data());
    }

    /**
     * Ticks a hiring step: HR any step; the manager the steps owned by the manager or the buddy (the buddy tells them).
     * Steps that tick themselves (a paper received, the contract signed) are left to the app.
     */
    @Transactional
    public Detail tickStep(long userId, long itemId, boolean done) {
        record S(String owner, String action) {}
        S st = jdbc.sql("""
                select i.owner, i.action from employee_checklist_item i join employee_checklist c on c.id = i.checklist_id
                where i.id = ? and c.user_id = ? and c.kind = 'HIRING'""").params(itemId, userId).query(S.class).optional()
                .orElseThrow(() -> new NotFoundException("Step not found."));
        if (!isHr()) {
            requireHrOrManager(userId);
            if (st.action() != null || !Set.of("MANAGER", "BUDDY").contains(st.owner())) {
                throw new AccessDeniedException("This step is done by someone else.");
            }
        }
        checklists.tick(itemId, done, null);
        return detail(userId);
    }

    public record Colleague(long id, String name, String job) {}

    /** Who can be the buddy: the active colleagues of the new hire's unit, the manager included. */
    public List<Colleague> colleagues(long userId) {
        requireHrOrManager(userId);
        return jdbc.sql("""
                select u.id, person_name(u.first_name, u.middle_name, u.last_name) as name,
                       (select oc.name from employee_contract c join occupation oc on oc.id = c.occupation_id
                         where c.user_id = u.id order by c.start_date desc limit 1) as job
                from app_user u join org_assignment a on a.user_id = u.id and a.valid_to is null
                where u.status = 'ACTIVE' and u.id <> :user
                  and a.unit_id = (select unit_id from org_assignment where user_id = :user and valid_to is null limit 1)
                order by u.last_name, u.first_name limit 200""").param("user", userId).query(Colleague.class).list();
    }

    /** The manager (or HR) names a colleague to show the new hire around. */
    @Transactional
    public Detail setBuddy(long userId, Long buddyUserId) {
        requireHrOrManager(userId);
        long id = onboardingId(userId);
        if (buddyUserId != null) {
            if (buddyUserId == userId) {
                throw new BusinessRuleException("The buddy is a colleague, not the new hire.");
            }
            if (jdbc.sql("select count(*) from app_user where id = ? and status = 'ACTIVE'").param(buddyUserId).query(Integer.class).single() == 0) {
                throw new BusinessRuleException("Choose an active colleague.");
            }
        }
        Long before = jdbc.sql("select buddy_user_id from onboarding where id = ?").param(id).query(Long.class).optional().orElse(null);
        jdbc.sql("update onboarding set buddy_user_id = ? where id = ?").params(buddyUserId, id).update();
        if (buddyUserId != null && !Objects.equals(before, buddyUserId)) {
            record P(String name, LocalDate startOn) {}
            P p = jdbc.sql("""
                    select person_name(u.first_name, u.middle_name, u.last_name) as name, o.start_on
                    from onboarding o join app_user u on u.id = o.user_id where o.id = ?""").param(id).query(P.class).single();
            notifications.send(buddyUserId, "onboarding.buddy",
                    () -> Texts.t("You are the buddy of %s", p.name()),
                    () -> Texts.t("%s starts on %s. Show them around in their first weeks: the team, the places, the habits.",
                            p.name(), Texts.day(p.startOn())),
                    null);
        }
        audit.record("onboarding.buddy", "user", userId, Map.of("buddy", buddyUserId == null ? "" : buddyUserId));
        return detail(userId);
    }

    @Transactional
    public Detail close(long userId) {
        requireHr();
        long id = onboardingId(userId);
        jdbc.sql("update onboarding set status = 'DONE', closed_at = now(), token = null where id = ?").param(id).update();
        return detail(userId);
    }

    public Overview overview() {
        requireHr();
        LocalDate today = today();
        int open = jdbc.sql("select count(*) from onboarding where status = 'OPEN'").query(Integer.class).single();
        int late = jdbc.sql("""
                select count(*) from employee_checklist c join employee_checklist_item i on i.checklist_id = c.id
                join onboarding o on o.user_id = c.user_id and o.status = 'OPEN'
                where c.kind = 'HIRING' and c.status = 'OPEN' and i.done_at is null and i.due_on < ?""").param(today)
                .query(Integer.class).single();
        int waiting = jdbc.sql("""
                select count(*) from onboarding o where o.status = 'OPEN' and ((o.submitted_at is not null and o.details_applied_at is null)
                   or exists (select 1 from onboarding_document d where d.onboarding_id = o.id and d.status = 'PENDING'))""")
                .query(Integer.class).single();
        record T(int confirmed, int ended) {}
        T t = jdbc.sql("""
                select count(*) filter (where recommendation = 'CONFIRM')::int as confirmed,
                       count(*) filter (where recommendation = 'END')::int as ended
                from trial_review where kind = 'END' and status = 'DONE' and done_at > now() - interval '365 days'""")
                .query(T.class).single();
        int reviewsLate = jdbc.sql("select count(*) from trial_review where status = 'PLANNED' and due_on < ?").param(today)
                .query(Integer.class).single();
        return new Overview(open, late, waiting, t.confirmed(), t.ended(), reviewsLate);
    }

    // ---- welcome page ------------------------------------------------------------------------------------------

    /** The new hire's welcome page, while their onboarding is open and their first day has come. */
    public Welcome welcome() {
        Long me = CurrentUser.id();
        if (me == null) {
            return null;
        }
        record O(LocalDate startOn, Long buddyUserId, OffsetDateTime welcomeSeenAt) {}
        O o = jdbc.sql("select start_on, buddy_user_id, welcome_seen_at from onboarding where user_id = ? and status = 'OPEN'")
                .param(me).query(O.class).optional().orElse(null);
        if (o == null || o.startOn().isAfter(today())) {
            return null;
        }
        String first = jdbc.sql("select first_name from app_user where id = ?").param(me).query(String.class).single();
        String manager = org.managerOf(me).map(OrgStructure.Boss::userName).orElse(null);
        String buddy = o.buddyUserId() == null ? null : jdbc.sql("select person_name(first_name, middle_name, last_name) from app_user where id = ?")
                .param(o.buddyUserId()).query(String.class).single();
        String unit = org.currentAssignment(me).map(OrgStructure.Assignment::unitName).orElse(null);
        String job = jdbc.sql("""
                select oc.name from employee_contract c join occupation oc on oc.id = c.occupation_id where c.user_id = ?
                order by c.start_date desc limit 1""").param(me).query(String.class).optional().orElse(null);
        LocalDate weekEnd = o.startOn().plusDays(7);
        List<Checklists.Item> steps = new ArrayList<>();
        jdbc.sql("select id from employee_checklist where user_id = ? and kind = 'HIRING' and status = 'OPEN'").param(me)
                .query(Long.class).optional().ifPresent(id -> checklists.find(id).items().stream()
                        .filter(i -> "NEW_HIRE".equals(i.owner()) || (i.dueOn() != null && !i.dueOn().isAfter(weekEnd)))
                        .forEach(steps::add));
        String text = jdbc.sql("select welcome_text from company where id = current_company_id()").query(String.class).optional().orElse(null);
        return new Welcome(first, o.startOn(), manager, buddy, unit, job, steps, text, o.welcomeSeenAt() != null);
    }

    @Transactional
    public void welcomeSeen() {
        Long me = CurrentUser.id();
        if (me != null) {
            jdbc.sql("update onboarding set welcome_seen_at = coalesce(welcome_seen_at, now()) where user_id = ?").param(me).update();
        }
    }

    public String welcomeText() {
        return jdbc.sql("select welcome_text from company where id = current_company_id()").query(String.class).optional().orElse(null);
    }

    @Transactional
    public String saveWelcomeText(String text) {
        requireHr();
        jdbc.sql("update company set welcome_text = ?, updated_by = ?, updated_at = now() where id = current_company_id()")
                .params(text == null || text.isBlank() ? null : text.strip(), CurrentUser.email()).update();
        return welcomeText();
    }

    // ---- trial reviews -----------------------------------------------------------------------------------------

    private static final String REVIEW_SELECT = """
            select r.id, r.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, r.kind, r.due_on,
                   r.trial_ends_on, r.reviewer_id,
                   (select person_name(m.first_name, m.middle_name, m.last_name) from app_user m where m.id = r.reviewer_id) as reviewer,
                   r.status, r.rating, r.strengths, r.improvements, r.recommendation, r.comment, r.done_by, r.done_at
            from trial_review r join app_user u on u.id = r.user_id""";

    List<Review> reviewsOf(long userId) {
        return jdbc.sql(REVIEW_SELECT + " where r.user_id = ? and r.status <> 'CANCELLED' order by r.due_on").param(userId)
                .query(Review.class).list();
    }

    /** The reviews the current user has to write (as manager), and for HR every planned one. */
    public List<Review> myReviews() {
        Long me = CurrentUser.id();
        if (isHr()) {
            return jdbc.sql(REVIEW_SELECT + " where r.status = 'PLANNED' or r.done_at > now() - interval '60 days' order by r.status desc, r.due_on")
                    .query(Review.class).list();
        }
        return jdbc.sql(REVIEW_SELECT + " where r.reviewer_id = ? and (r.status = 'PLANNED' or r.done_at > now() - interval '60 days') order by r.status desc, r.due_on")
                .param(me).query(Review.class).list();
    }

    @Transactional
    public Review saveReview(long id, int rating, String strengths, String improvements, String recommendation, String comment) {
        Review r = jdbc.sql(REVIEW_SELECT + " where r.id = ?").param(id).query(Review.class).optional()
                .orElseThrow(() -> new NotFoundException("Review not found."));
        Long me = CurrentUser.id();
        if (!isHr() && (me == null || !Objects.equals(r.reviewerId(), me))) {
            requireHrOrManager(r.userId());
        }
        if ("CANCELLED".equals(r.status())) {
            throw new ConflictException("This review was cancelled.");
        }
        if ("END".equals(r.kind()) && recommendation == null) {
            throw new BusinessRuleException("Say whether the trial should be confirmed or ended.");
        }
        jdbc.sql("""
                update trial_review set status = 'DONE', rating = ?, strengths = ?, improvements = ?, recommendation = ?, comment = ?,
                       done_by = ?, done_at = now() where id = ?""")
                .params(rating, blank(strengths), blank(improvements), recommendation, blank(comment), CurrentUser.email(), id)
                .update();
        if (recommendation != null) {
            String text = "END".equals(recommendation)
                    ? Texts.t("The manager recommends ending the trial of %s, which ends on %s. Notice must be given in writing before the trial ends (Labour Code art. 43): end it from Hiring and departures.", r.name(), Texts.day(r.trialEndsOn()))
                    : Texts.t("The manager recommends confirming %s at the end of the trial on %s. Confirm it from Hiring and departures.", r.name(), Texts.day(r.trialEndsOn()));
            notifications.sendToHolders("lifecycle.manage", "onboarding.trial_review",
                    () -> Texts.t("Trial review: %s", r.name()), () -> text, "/onboarding?user=" + r.userId());
        }
        audit.record("trial_review.done", "user", r.userId(), Map.of("kind", r.kind(), "recommendation", recommendation == null ? "" : recommendation));
        return jdbc.sql(REVIEW_SELECT + " where r.id = ?").param(id).query(Review.class).single();
    }

    // ---- reminders (daily) -------------------------------------------------------------------------------------

    /** The day before a step is due its owner is reminded; reviewers on the day their review is due. */
    @Transactional
    public int remind() {
        LocalDate today = today();
        record Step(long id, long userId, String label, String owner, LocalDate dueOn, String name, Long buddyUserId) {}
        List<Step> steps = jdbc.sql("""
                select i.id, c.user_id, i.label, i.owner, i.due_on, person_name(u.first_name, u.middle_name, u.last_name) as name,
                       o.buddy_user_id
                from employee_checklist_item i join employee_checklist c on c.id = i.checklist_id
                join app_user u on u.id = c.user_id
                join onboarding o on o.user_id = c.user_id and o.status = 'OPEN'
                where c.kind = 'HIRING' and c.status = 'OPEN' and i.done_at is null and i.due_on is not null
                  and i.due_on <= ? and i.reminded_on is null""").param(today.plusDays(1)).query(Step.class).list();
        int sent = 0;
        for (Step s : steps) {
            java.util.function.Supplier<String> title = () -> Texts.t("Onboarding of %s: %s", s.name(), s.label());
            java.util.function.Supplier<String> body = () -> Texts.t("This step is due on %s.", Texts.day(s.dueOn()));
            String link = "/onboarding?user=" + s.userId();
            switch (s.owner()) {
                case "MANAGER" -> org.managerOf(s.userId()).ifPresent(b -> notifications.send(b.userId(), "onboarding.step", title, body, link));
                case "BUDDY" -> {
                    if (s.buddyUserId() != null) {
                        notifications.send(s.buddyUserId(), "onboarding.step", title, body, null);
                    }
                }
                case "NEW_HIRE" -> {
                    // Before day one the new hire has the link, not the app: HR follows these steps.
                    notifications.sendToHolders("lifecycle.manage", "onboarding.step", title, body, link);
                }
                default -> notifications.sendToHolders("lifecycle.manage", "onboarding.step", title, body, link);
            }
            jdbc.sql("update employee_checklist_item set reminded_on = ? where id = ?").params(today, s.id()).update();
            sent++;
        }
        record R(long id, long userId, String name, String kind, Long reviewerId, LocalDate trialEndsOn) {}
        List<R> due = jdbc.sql("""
                select r.id, r.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, r.kind, r.reviewer_id,
                       r.trial_ends_on
                from trial_review r join app_user u on u.id = r.user_id
                where r.status = 'PLANNED' and r.due_on <= ? and r.reminded_on is null""").param(today).query(R.class).list();
        for (R r : due) {
            java.util.function.Supplier<String> title = () -> Texts.t("Trial review to write: %s", r.name());
            java.util.function.Supplier<String> body = () -> "END".equals(r.kind())
                    ? Texts.t("The trial ends on %s: confirm or end it now, while there is time for the written notice.", Texts.day(r.trialEndsOn()))
                    : Texts.t("One month in: how is the start going? A short review helps both of you.");
            if (r.reviewerId() != null) {
                notifications.send(r.reviewerId(), "onboarding.trial_review", title, body, "/onboarding?tab=reviews");
            } else {
                notifications.sendToHolders("lifecycle.manage", "onboarding.trial_review", title, body, "/onboarding?tab=reviews");
            }
            jdbc.sql("update trial_review set reminded_on = ? where id = ?").params(today, r.id()).update();
            sent++;
        }
        return sent;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
