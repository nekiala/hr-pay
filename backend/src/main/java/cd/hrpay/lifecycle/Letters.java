package cd.hrpay.lifecycle;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.Notifications;
import cd.hrpay.notify.OtpService;
import cd.hrpay.payment.Company;
import cd.hrpay.payroll.EmployeeFile;
import cd.hrpay.salary.SalaryService;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contracts and letters made from the company's templates, and signed by the employee in HR Pay. The employee reads
 * the PDF, asks for a one-time code (email, and WhatsApp where it is set up), types their name and the code; HR Pay
 * then adds a signature page that names them, the time, the address they signed from and the SHA-256 fingerprint
 * of the exact PDF they read, and files the signed copy in their documents. A sent document never changes: to
 * correct it, cancel it and send a new one.
 */
@Service
public class Letters {

    public static final String PURPOSE = "LETTER_SIGN";

    /** The fields a template can use, written {{field}}, with what each holds. */
    public static final Map<String, String> FIELDS = fields();

    private static Map<String, String> fields() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("employee.name", "Full name");
        m.put("employee.first_name", "First name");
        m.put("employee.last_name", "Last name");
        m.put("employee.postnom", "Postnom");
        m.put("employee.registration_no", "Registration no.");
        m.put("employee.birth_date", "Date of birth");
        m.put("employee.nationality", "Nationality");
        m.put("employee.email", "Email");
        m.put("employee.phone", "Phone");
        m.put("employee.hire_date", "Hire date");
        m.put("contract.type", "Kind of contract");
        m.put("contract.start", "Contract start");
        m.put("contract.end", "Contract end");
        m.put("job.title", "Occupation");
        m.put("job.grade", "Grade");
        m.put("salary.gross", "Gross monthly salary, with its currency");
        m.put("site.name", "Site");
        m.put("unit.name", "Unit in the structure");
        m.put("company.name", "Company name");
        m.put("company.address", "Company address");
        m.put("signatory.name", "Name of the person who sends the document");
        m.put("today", "Today's date");
        return m;
    }

    private static final Pattern FIELD = Pattern.compile("\\{\\{\\s*([a-z_.]+)\\s*}}");

    public record Template(long id, String code, String name, String kind, String body, boolean requiresSignature,
                           Long documentTypeId, boolean active, String updatedBy, OffsetDateTime updatedAt) {}

    public record Letter(long id, long userId, String employeeName, Long templateId, String kind, String number, String title,
                         String body, boolean requiresSignature, String status, String sha256, String sentBy, String sentByName,
                         OffsetDateTime sentAt, String signedSha256, OffsetDateTime signedAt, String signedName, String signedIp,
                         String declinedReason, Long documentId, String createdBy, OffsetDateTime createdAt) {}

    public record Draft(String title, String body, List<String> missing) {}

    public record CodeSent(UUID challengeId, List<String> channels, OffsetDateTime expiresAt) {}

    private static final String SELECT = """
            select l.id, l.user_id, person_name(u.first_name, u.middle_name, u.last_name) as employee_name, l.template_id, l.kind, l.number, l.title,
                   l.body, l.requires_signature, l.status, l.sha256, l.sent_by, l.sent_by_name, l.sent_at, l.signed_sha256,
                   l.signed_at, l.signed_name, l.signed_ip, l.declined_reason, l.document_id, l.created_by, l.created_at
            from employee_letter l join app_user u on u.id = l.user_id""";

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final EmployeeFile files;
    private final SalaryService salaries;
    private final Company company;
    private final Documents documents;
    private final LifecyclePdf pdf;
    private final OtpService otp;
    private final Notifications notifications;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public Letters(JdbcClient jdbc, UserRepository users, EmployeeFile files, SalaryService salaries, Company company,
                   Documents documents, LifecyclePdf pdf, OtpService otp, Notifications notifications, AuditService audit,
                   JsonMapper json, Clock clock, org.springframework.transaction.PlatformTransactionManager txManager) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.jdbc = jdbc;
        this.users = users;
        this.files = files;
        this.salaries = salaries;
        this.company = company;
        this.documents = documents;
        this.pdf = pdf;
        this.otp = otp;
        this.notifications = notifications;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // ---- templates ----------------------------------------------------------------------------------------------

    public List<Template> templates() {
        return jdbc.sql("select * from letter_template order by kind, name").query(Template.class).list();
    }

    public Template template(long id) {
        return jdbc.sql("select * from letter_template where id = ?").param(id).query(Template.class).optional()
                .orElseThrow(() -> new NotFoundException("Template not found."));
    }

    @Transactional
    public Template saveTemplate(Long id, String code, String name, String kind, String body, boolean requiresSignature,
                                 Long documentTypeId, boolean active) {
        if (!Set.of("CONTRACT", "LETTER").contains(kind)) {
            throw new BusinessRuleException("A template is a contract or a letter.");
        }
        Set<String> unknown = unknownFields(body);
        if (!unknown.isEmpty()) {
            throw new BusinessRuleException("Unknown fields: %s.".formatted(String.join(", ", unknown)));
        }
        String c = code.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        jdbc.sql("select id from letter_template where code = ? and id <> ?").params(c, id == null ? -1 : id).query(Long.class)
                .optional().ifPresent(x -> {
                    throw new ConflictException("A template with the code %s exists already.".formatted(c));
                });
        long saved;
        if (id == null) {
            saved = jdbc.sql("""
                    insert into letter_template (code, name, kind, body, requires_signature, document_type_id, active, updated_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(c, name.trim(), kind, body, requiresSignature, documentTypeId, active, CurrentUser.email())
                    .query(Long.class).single();
        } else {
            template(id);
            jdbc.sql("""
                    update letter_template set code = ?, name = ?, kind = ?, body = ?, requires_signature = ?, document_type_id = ?,
                        active = ?, updated_by = ?, updated_at = now() where id = ?""")
                    .params(c, name.trim(), kind, body, requiresSignature, documentTypeId, active, CurrentUser.email(), id).update();
            saved = id;
        }
        audit.record("letter_template.saved", "letter_template", saved, Map.of("code", c, "active", active));
        return template(saved);
    }

    static Set<String> unknownFields(String body) {
        Set<String> unknown = new LinkedHashSet<>();
        Matcher m = FIELD.matcher(body == null ? "" : body);
        while (m.find()) {
            if (!FIELDS.containsKey(m.group(1))) {
                unknown.add(m.group(1));
            }
        }
        return unknown;
    }

    // ---- filling a template for one person -------------------------------------------------------------------

    /** What each field holds for this person today; an empty value is left out. */
    public Map<String, String> values(long userId) {
        AppUser u = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found."));
        LocalDate today = LocalDate.now(clock);
        Map<String, String> v = new LinkedHashMap<>();
        v.put("employee.name", u.fullName());
        v.put("employee.first_name", u.getFirstName());
        v.put("employee.last_name", u.getLastName());
        v.put("employee.postnom", u.getMiddleName() == null ? "" : u.getMiddleName());
        v.put("employee.registration_no", u.getRegistrationNo());
        v.put("employee.email", u.getEmail());
        v.put("employee.phone", u.getPhone());
        EmployeeFile.Profile p = files.profile(userId);
        if (p.birthDate() != null) {
            v.put("employee.birth_date", Texts.day(p.birthDate()));
        }
        v.put("employee.nationality", p.nationality());
        var contracts = files.contracts(userId);
        LocalDate hired = u.getHireDate() != null ? u.getHireDate()
                : contracts.stream().map(EmployeeFile.Contract::startDate).min(LocalDate::compareTo).orElse(null);
        if (hired != null) {
            v.put("employee.hire_date", Texts.day(hired));
        }
        contracts.stream().max(java.util.Comparator.comparing(EmployeeFile.Contract::startDate)).ifPresent(c -> {
            v.put("contract.type", contractType(c.contractType()));
            v.put("contract.start", Texts.day(c.startDate()));
            if (c.endDate() != null) {
                v.put("contract.end", Texts.day(c.endDate()));
            }
            v.put("job.title", c.occupationName());
            v.put("job.grade", c.gradeCode() + " · " + c.gradeName());
        });
        salaries.agreed(userId, today).ifPresent(s -> v.put("salary.gross", amount(s.grossMonthly()) + " " + s.currency()));
        if (u.getSite() != null) {
            v.put("site.name", u.getSite().getName());
        }
        jdbc.sql("""
                select u.name from org_assignment a join org_unit u on u.id = a.unit_id
                where a.user_id = ? and a.valid_to is null order by a.valid_from desc limit 1""")
                .param(userId).query(String.class).optional().ifPresent(n -> v.put("unit.name", n));
        Company.Details employer = company.get();
        v.put("company.name", employer.name());
        v.put("company.address", employer.address());
        Long me = CurrentUser.id();
        if (me != null) {
            users.findById(me).ifPresent(s -> v.put("signatory.name", s.fullName()));
        }
        v.put("today", Texts.day(today));
        v.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isBlank());
        return v;
    }

    static String contractType(String code) {
        return switch (code == null ? "" : code) {
            case "PERMANENT" -> Texts.t("permanent contract (CDI)");
            case "FIXED_TERM" -> Texts.t("fixed-term contract (CDD)");
            case "INTERN" -> Texts.t("internship agreement");
            case "CONSULTANT" -> Texts.t("consultancy agreement");
            default -> code;
        };
    }

    static String amount(BigDecimal v) {
        java.text.NumberFormat f = java.text.NumberFormat.getNumberInstance(Texts.locale());
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return f.format(v);
    }

    /** The text with every field filled in; a field with no value for this person shows as "…" and is named. */
    public Draft fill(long userId, String title, String body) {
        Map<String, String> v = Texts.in(company.language(), () -> values(userId));
        Set<String> missing = new LinkedHashSet<>();
        String filled = replace(body, v, missing);
        String t = replace(title, v, missing);
        return new Draft(t, filled, List.copyOf(missing));
    }

    private static String replace(String text, Map<String, String> values, Set<String> missing) {
        Matcher m = FIELD.matcher(text == null ? "" : text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null) {
                missing.add(m.group(1));
                value = "…";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    public Draft preview(long userId, long templateId) {
        Template t = template(templateId);
        return fill(userId, t.name(), t.body());
    }

    // ---- letters ------------------------------------------------------------------------------------------------

    public List<Letter> of(long userId) {
        return jdbc.sql(SELECT + " where l.user_id = ? and l.status <> 'CANCELLED' order by l.created_at desc").param(userId)
                .query(Letter.class).list();
    }

    /** What the person sees: everything sent to them. */
    public List<Letter> mine(long userId) {
        return jdbc.sql(SELECT + " where l.user_id = ? and l.status in ('SENT', 'SIGNED', 'DECLINED') order by l.sent_at desc")
                .param(userId).query(Letter.class).list();
    }

    public List<Letter> waiting() {
        return jdbc.sql(SELECT + " where l.status = 'SENT' and l.requires_signature order by l.sent_at").query(Letter.class).list();
    }

    public Letter find(long id) {
        return jdbc.sql(SELECT + " where l.id = ?").param(id).query(Letter.class).optional()
                .orElseThrow(() -> new NotFoundException("Document not found."));
    }

    @Transactional
    public Letter create(long userId, Long templateId, String title, String body, String kind, boolean requiresSignature) {
        Template t = templateId == null ? null : template(templateId);
        String k = t != null ? t.kind() : kind == null ? "LETTER" : kind;
        if (!Set.of("CONTRACT", "LETTER").contains(k)) {
            throw new BusinessRuleException("A template is a contract or a letter.");
        }
        Set<String> unknown = unknownFields(body);
        if (!unknown.isEmpty()) {
            throw new BusinessRuleException("Unknown fields: %s.".formatted(String.join(", ", unknown)));
        }
        long id = jdbc.sql("""
                insert into employee_letter (user_id, template_id, kind, title, body, requires_signature, created_by)
                values (?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(userId, templateId, k, title.trim(), body, requiresSignature, CurrentUser.email()).query(Long.class).single();
        audit.record("letter.created", "user", userId, Map.of("letter", id, "kind", k));
        return find(id);
    }

    @Transactional
    public Letter update(long id, String title, String body, boolean requiresSignature) {
        Letter l = find(id);
        if (!l.status().equals("DRAFT")) {
            throw new BusinessRuleException("A sent document cannot change: cancel it and send a new one.");
        }
        Set<String> unknown = unknownFields(body);
        if (!unknown.isEmpty()) {
            throw new BusinessRuleException("Unknown fields: %s.".formatted(String.join(", ", unknown)));
        }
        jdbc.sql("update employee_letter set title = ?, body = ?, requires_signature = ? where id = ?")
                .params(title.trim(), body, requiresSignature, id).update();
        return find(id);
    }

    /** Numbers the document, makes its PDF and sends it: to sign, or to keep when it needs no signature. */
    @Transactional
    public Letter send(long id) {
        Letter l = find(id);
        if (!l.status().equals("DRAFT")) {
            throw new BusinessRuleException("This document was sent already.");
        }
        Draft d = fill(l.userId(), l.title(), l.body());
        if (!d.missing().isEmpty()) {
            throw new BusinessRuleException("Fill in first, in the employee's file or in the text: %s."
                    .formatted(String.join(", ", d.missing().stream().map(k -> Texts.t(FIELDS.get(k))).toList())));
        }
        AppUser sender = users.findById(CurrentUser.id()).orElseThrow();
        AppUser employee = users.findById(l.userId()).orElseThrow();
        String senderName = sender.fullName();
        int year = LocalDate.now(clock).getYear();
        int n = jdbc.sql("select count(*) from employee_letter where number like ?").param("LT-" + year + "-%")
                .query(Integer.class).single() + 1;
        String number = "LT-%d-%04d".formatted(year, n);
        byte[] file = Texts.in(company.language(), () -> {
            List<LifecyclePdf.Signature> signatures = new ArrayList<>();
            signatures.add(new LifecyclePdf.Signature(senderName, Texts.t("For the employer")));
            if (l.requiresSignature()) {
                signatures.add(new LifecyclePdf.Signature(employee.fullName(),
                        Texts.t("The employee (signed electronically, see the last page)")));
            }
            return pdf.letter(d.title(), number, d.body(), Texts.t("Done on %s.", Texts.day(LocalDate.now(clock))), signatures);
        });
        String sha = Documents.sha256(file);
        jdbc.sql("""
                update employee_letter set number = ?, title = ?, body = ?, status = 'SENT', pdf = ?, sha256 = ?, sent_by = ?,
                    sent_by_name = ?, sent_at = now() where id = ?""")
                .params(number, d.title(), d.body(), file, sha, sender.getEmail(), senderName, id).update();
        if (!l.requiresSignature()) {
            long doc = documents.add(l.userId(), documentType(l), number + ".pdf", file, null, null, d.title(), "SIGNED").id();
            jdbc.sql("update employee_letter set document_id = ? where id = ?").params(doc, id).update();
        }
        audit.record("letter.sent", "user", l.userId(), Map.of("letter", id, "number", number, "sha256", sha));
        notifications.send(l.userId(), "letter.sent",
                () -> l.requiresSignature() ? Texts.t("A document to sign") : Texts.t("A new document"),
                () -> l.requiresSignature() ? Texts.t("%s waits for your signature in HR Pay.", d.title())
                        : Texts.t("%s was added to your documents.", d.title()), "/my/documents");
        return find(id);
    }

    @Transactional
    public Letter cancel(long id) {
        Letter l = find(id);
        if (!Set.of("DRAFT", "SENT").contains(l.status())) {
            throw new BusinessRuleException("A signed document stays as it is.");
        }
        jdbc.sql("update employee_letter set status = 'CANCELLED' where id = ?").param(id).update();
        audit.record("letter.cancelled", "user", l.userId(), Map.of("letter", id));
        return find(id);
    }

    public byte[] file(long id) {
        record Files(byte[] pdf, byte[] signedPdf) {}
        Files f = jdbc.sql("select pdf, signed_pdf from employee_letter where id = ?").param(id).query(Files.class).single();
        if (f.signedPdf() != null) {
            return f.signedPdf();
        }
        if (f.pdf() == null) {
            throw new BusinessRuleException("This document is still a draft.");
        }
        return f.pdf();
    }

    /** The draft's PDF as it would be sent, for HR to read before sending. */
    public byte[] draftPdf(long id) {
        Letter l = find(id);
        Draft d = fill(l.userId(), l.title(), l.body());
        return Texts.in(company.language(), () -> pdf.letter(d.title(), null, d.body(), Texts.t("Done on %s.", Texts.day(LocalDate.now(clock))),
                List.of(new LifecyclePdf.Signature(" ", Texts.t("For the employer")))));
    }

    // ---- the employee signs ---------------------------------------------------------------------------------

    private Letter toSign(long id, AppUser user) {
        Letter l = find(id);
        if (l.userId() != user.getId()) {
            throw new NotFoundException("Document not found.");
        }
        if (!l.status().equals("SENT") || !l.requiresSignature()) {
            throw new BusinessRuleException("This document is not waiting for your signature.");
        }
        return l;
    }

    @Transactional
    public CodeSent sendCode(long id, AppUser user) {
        Letter l = toSign(id, user);
        OtpService.Challenge c = otp.issue(user, PURPOSE, json.writeValueAsString(Map.of("letter", l.id(), "sha256", l.sha256())));
        List<String> channels = c.deliveries().stream().filter(d -> !d.status().equals("FAILED")).map(d -> d.channel().name()).toList();
        jdbc.sql("update employee_letter set signed_channels = ? where id = ?").params(String.join(",", channels), id).update();
        return new CodeSent(c.id(), channels, c.expiresAt());
    }

    /** Signs with the code. The name typed must be the person's own name, as HR Pay knows it. */
    public Letter sign(long id, AppUser user, UUID challengeId, String code, String typedName, String ip, String agent) {
        Letter l = toSign(id, user);
        String expected = user.fullName();
        if (!sameName(typedName, expected)) {
            throw new BusinessRuleException("Type your name as HR Pay knows it: %s.".formatted(expected));
        }
        OtpService.Verified ok = otp.verify(challengeId, user, PURPOSE, code);
        Map<?, ?> context = json.readValue(ok.contextJson(), Map.class);
        if (!String.valueOf(context.get("letter")).equals(String.valueOf(l.id())) || !l.sha256().equals(context.get("sha256"))) {
            throw new BusinessRuleException("This code was asked for another document. Ask for a new code.");
        }
        return tx.execute(status -> signNow(l, user, typedName.trim(), ip, agent));
    }

    private Letter signNow(Letter l, AppUser user, String typedName, String ip, String agent) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        ZoneId zone = ZoneId.of(user.getSite() == null ? "Africa/Kinshasa" : user.getSite().getTimeZone());
        String channels = jdbc.sql("select signed_channels from employee_letter where id = ?").param(l.id()).query(String.class)
                .optional().orElse("");
        byte[] original = file(l.id());
        byte[] page = Texts.in(company.language(), () -> pdf.signaturePage(Texts.t("Electronic signature"), List.of(
                new LifecyclePdf.Line(Texts.t("Document"), l.title() + " · " + Texts.t("No. %s", l.number())),
                new LifecyclePdf.Line(Texts.t("Signed by"), user.fullName() + " · "
                        + Texts.t("registration no. %s", user.getRegistrationNo())),
                new LifecyclePdf.Line(Texts.t("Name typed"), typedName),
                new LifecyclePdf.Line(Texts.t("Date and time"), now.atZoneSameInstant(zone)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")) + " (" + zone.getId() + ")"),
                new LifecyclePdf.Line(Texts.t("Confirmed with"), Texts.t("a one-time code sent by %s", channels(channels))),
                new LifecyclePdf.Line(Texts.t("Address"), ip),
                new LifecyclePdf.Line(Texts.t("Device"), agent == null ? null : agent.length() > 200 ? agent.substring(0, 200) : agent),
                new LifecyclePdf.Line(Texts.t("Sent by"), l.sentByName()),
                new LifecyclePdf.Line(Texts.t("Fingerprint (SHA-256) of the document read"), l.sha256())),
                Texts.t("HR Pay added this page when the person confirmed with the one-time code sent to them. The fingerprint "
                        + "identifies the exact document they read: any change to its pages gives another fingerprint. "
                        + "The signature is also recorded in HR Pay's audit log.")));
        byte[] signed = LifecyclePdf.append(original, page);
        String sha = Documents.sha256(signed);
        long doc = documents.add(l.userId(), documentType(l), l.number() + "-signed.pdf", signed, null, null, l.title(), "SIGNED").id();
        jdbc.sql("""
                update employee_letter set status = 'SIGNED', signed_pdf = ?, signed_sha256 = ?, signed_at = ?, signed_name = ?,
                    signed_ip = ?, signed_agent = ?, document_id = ? where id = ?""")
                .params(signed, sha, now, typedName, ip, agent == null ? null : agent.length() > 300 ? agent.substring(0, 300) : agent,
                        doc, l.id()).update();
        audit.record("letter.signed", "user", l.userId(), Map.of("letter", l.id(), "number", l.number(), "sha256", l.sha256(),
                "signedSha256", sha));
        users.findByEmailIgnoreCase(l.sentBy()).ifPresent(s -> notifications.send(s.getId(), "letter.signed",
                () -> Texts.t("Document signed"), () -> Texts.t("%s signed %s.", user.fullName(), l.title()),
                "/users/" + l.userId() + "/lifecycle"));
        return find(l.id());
    }

    @Transactional
    public Letter decline(long id, AppUser user, String reason) {
        Letter l = toSign(id, user);
        String why = reason == null || reason.isBlank() ? null : reason.trim();
        jdbc.sql("update employee_letter set status = 'DECLINED', declined_reason = ? where id = ?").params(why, id).update();
        audit.record("letter.declined", "user", l.userId(), Map.of("letter", id));
        users.findByEmailIgnoreCase(l.sentBy()).ifPresent(s -> notifications.send(s.getId(), "letter.declined",
                () -> Texts.t("Document refused"),
                () -> Texts.t("%s did not sign %s: %s", user.fullName(), l.title(), why == null ? "—" : why),
                "/users/" + l.userId() + "/lifecycle"));
        return find(id);
    }

    private long documentType(Letter l) {
        Long t = l.templateId() == null ? null
                : jdbc.sql("select document_type_id from letter_template where id = ?").param(l.templateId()).query(Long.class)
                        .optional().orElse(null);
        return t != null ? t : documents.typeId(l.kind().equals("CONTRACT") ? "CONTRACT" : "LETTER");
    }

    private static String channels(String list) {
        List<String> names = new ArrayList<>();
        for (String c : list.split(",")) {
            switch (c.trim()) {
                case "EMAIL" -> names.add(Texts.t("email"));
                case "WHATSAPP" -> names.add("WhatsApp");
                case "SMS" -> names.add("SMS");
                default -> { }
            }
        }
        return names.isEmpty() ? Texts.t("email") : String.join(" + ", names);
    }

    static boolean sameName(String typed, String expected) {
        return typed != null && simple(typed).equals(simple(expected));
    }

    /** The name's words without accents or case, in any order (Nom Postnom Prénom or Prénom Nom both match). */
    private static String simple(String s) {
        return java.util.Arrays.stream(Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                        .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim().split(" "))
                .sorted().collect(java.util.stream.Collectors.joining(" "));
    }
}
