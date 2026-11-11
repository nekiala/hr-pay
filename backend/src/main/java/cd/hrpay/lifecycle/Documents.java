package cd.hrpay.lifecycle;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.Notifications;
import cd.hrpay.security.CurrentUser;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each employee's documents: ID, diplomas, contracts, certificates. A document of a type that expires (an ID card,
 * a work permit, a medical certificate) brings a reminder to the employee and HR some days before it does. A deleted
 * document is only hidden, so what was on file stays provable.
 */
@Service
public class Documents {

    public static final long MAX_BYTES = 10L * 1024 * 1024;

    public record Type(long id, String code, String name, boolean hasExpiry, int remindDays, boolean employeeCanUpload,
                       boolean employeeCanSee, int position, boolean active) {}

    public record Doc(long id, long userId, long typeId, String typeCode, String typeName, String fileName, String contentType,
                      int sizeBytes, String sha256, LocalDate issuedOn, LocalDate expiresOn, String note, String source,
                      String uploadedBy, OffsetDateTime uploadedAt, boolean employeeCanSee) {}

    public record Expiring(long id, long userId, String name, String registrationNo, String typeName, LocalDate expiresOn) {}

    public record File(String fileName, String contentType, byte[] data) {}

    private static final String SELECT = """
            select d.id, d.user_id, d.type_id, t.code as type_code, t.name as type_name, d.file_name, d.content_type,
                   d.size_bytes, d.sha256, d.issued_on, d.expires_on, d.note, d.source, d.uploaded_by, d.uploaded_at,
                   t.employee_can_see
            from employee_document d join document_type t on t.id = d.type_id
            where d.deleted_at is null""";

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final Notifications notifications;
    private final Clock clock;

    public Documents(JdbcClient jdbc, AuditService audit, Notifications notifications, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
    }

    // ---- types --------------------------------------------------------------------------------------------------

    public List<Type> types() {
        return jdbc.sql("select * from document_type order by position, name").query(Type.class).list();
    }

    public Type type(long id) {
        return jdbc.sql("select * from document_type where id = ?").param(id).query(Type.class).optional()
                .orElseThrow(() -> new NotFoundException("Document type not found."));
    }

    public long typeId(String code) {
        return jdbc.sql("select id from document_type where code = ?").param(code).query(Long.class).optional()
                .orElseGet(() -> jdbc.sql("insert into document_type (code, name, position) values (?, ?, 90) returning id")
                        .params(code, Texts.t(code.charAt(0) + code.substring(1).toLowerCase(Locale.ROOT)))
                        .query(Long.class).single());
    }

    @Transactional
    public Type saveType(Long id, String code, String name, boolean hasExpiry, int remindDays, boolean employeeCanUpload,
                         boolean employeeCanSee, int position, boolean active) {
        String c = code.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        jdbc.sql("select id from document_type where code = ? and id <> ?").params(c, id == null ? -1 : id).query(Long.class)
                .optional().ifPresent(x -> {
                    throw new ConflictException("A document type with the code %s exists already.".formatted(c));
                });
        long saved;
        if (id == null) {
            saved = jdbc.sql("""
                    insert into document_type (code, name, has_expiry, remind_days, employee_can_upload, employee_can_see, position, active)
                    values (?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(c, name.trim(), hasExpiry, remindDays, employeeCanUpload, employeeCanSee, position, active)
                    .query(Long.class).single();
        } else {
            type(id);
            jdbc.sql("""
                    update document_type set code = ?, name = ?, has_expiry = ?, remind_days = ?, employee_can_upload = ?,
                        employee_can_see = ?, position = ?, active = ? where id = ?""")
                    .params(c, name.trim(), hasExpiry, remindDays, employeeCanUpload, employeeCanSee, position, active, id).update();
            saved = id;
        }
        audit.record("document_type.saved", "document_type", saved, Map.of("code", c, "active", active));
        return type(saved);
    }

    // ---- documents ----------------------------------------------------------------------------------------------

    /** A person's documents; for the person themself, only the types they may see. */
    public List<Doc> of(long userId, boolean own) {
        return jdbc.sql(SELECT + " and d.user_id = ?" + (own ? " and t.employee_can_see" : "")
                + " order by t.position, t.name, d.uploaded_at desc").param(userId).query(Doc.class).list();
    }

    public Doc find(long id) {
        return jdbc.sql(SELECT + " and d.id = ?").param(id).query(Doc.class).optional()
                .orElseThrow(() -> new NotFoundException("Document not found."));
    }

    public File file(long id) {
        Doc d = find(id);
        byte[] data = jdbc.sql("select data from employee_document where id = ?").param(id).query(byte[].class).single();
        return new File(d.fileName(), d.contentType(), data);
    }

    @Transactional
    public Doc add(long userId, long typeId, String fileName, byte[] data, LocalDate issuedOn, LocalDate expiresOn, String note,
                   String source) {
        Type t = type(typeId);
        if (!t.active()) {
            throw new BusinessRuleException("This type of document is no longer used.");
        }
        if (data == null || data.length == 0) {
            throw new BusinessRuleException("The file is empty.");
        }
        if (data.length > MAX_BYTES) {
            throw new BusinessRuleException("A document is at most 10 MB.");
        }
        String contentType = sniff(data);
        if (contentType == null) {
            throw new BusinessRuleException("Send a PDF, JPEG or PNG file.");
        }
        if (t.hasExpiry() && expiresOn == null && !"CERTIFICATE".equals(source) && !"SIGNED".equals(source)) {
            throw new BusinessRuleException("Enter the date %s expires.".formatted(t.name()));
        }
        if (issuedOn != null && expiresOn != null && expiresOn.isBefore(issuedOn)) {
            throw new BusinessRuleException("The expiry date comes before the date of issue.");
        }
        String name = cleanName(fileName, contentType);
        long id = jdbc.sql("""
                insert into employee_document (user_id, type_id, file_name, content_type, size_bytes, data, sha256, issued_on,
                    expires_on, note, source, uploaded_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(userId, typeId, name, contentType, data.length, data, sha256(data), issuedOn, expiresOn,
                        note == null || note.isBlank() ? null : note.trim(), source, CurrentUser.email())
                .query(Long.class).single();
        audit.record("document.added", "user", userId, Map.of("document", id, "type", t.code(), "source", source));
        return find(id);
    }

    @Transactional
    public void delete(long id) {
        Doc d = find(id);
        jdbc.sql("update employee_document set deleted_at = now(), deleted_by = ? where id = ?").params(CurrentUser.email(), id).update();
        audit.record("document.deleted", "user", d.userId(), Map.of("document", id, "type", d.typeCode()));
    }

    /** Documents that expire within the next days, newest replacement aside: the HR list of things to chase. */
    public List<Expiring> expiring(int days) {
        return jdbc.sql("""
                select d.id, d.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no, t.name as type_name, d.expires_on
                from employee_document d join document_type t on t.id = d.type_id join app_user u on u.id = d.user_id
                where d.deleted_at is null and d.expires_on is not null and d.expires_on <= ? and u.status = 'ACTIVE'
                  and not exists (select 1 from employee_document n where n.user_id = d.user_id and n.type_id = d.type_id
                                  and n.deleted_at is null and n.expires_on > d.expires_on)
                order by d.expires_on""").param(LocalDate.now(clock).plusDays(days)).query(Expiring.class).list();
    }

    /** Sends each expiry reminder once: to HR, and to the employee when they can see the document. */
    @Transactional
    public int remind() {
        record Due(long id, long userId, String name, String typeName, LocalDate expiresOn, boolean employeeCanSee) {}
        LocalDate today = LocalDate.now(clock);
        List<Due> due = jdbc.sql("""
                select d.id, d.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, t.name as type_name, d.expires_on,
                       t.employee_can_see
                from employee_document d join document_type t on t.id = d.type_id join app_user u on u.id = d.user_id
                where d.deleted_at is null and d.expires_on is not null and u.status = 'ACTIVE'
                  and d.expires_on <= cast(? as date) + t.remind_days
                  and (d.reminded_for is null or d.reminded_for <> d.expires_on)
                  and not exists (select 1 from employee_document n where n.user_id = d.user_id and n.type_id = d.type_id
                                  and n.deleted_at is null and n.expires_on > d.expires_on)""")
                .param(today).query(Due.class).list();
        for (Due d : due) {
            boolean past = d.expiresOn().isBefore(today);
            String link = "/users/" + d.userId() + "/lifecycle";
            notifications.sendToHolders("lifecycle.manage", "document.expiring",
                    () -> past ? Texts.t("Document expired") : Texts.t("Document expiring"),
                    () -> past ? Texts.t("%s of %s expired on %s.", d.typeName(), d.name(), Texts.day(d.expiresOn()))
                            : Texts.t("%s of %s expires on %s.", d.typeName(), d.name(), Texts.day(d.expiresOn())), link);
            if (d.employeeCanSee()) {
                notifications.send(d.userId(), "document.expiring",
                        () -> past ? Texts.t("Document expired") : Texts.t("Document expiring"),
                        () -> Texts.t("Your %s expires on %s. Bring the new one to HR.", d.typeName(), Texts.day(d.expiresOn())),
                        "/my/documents");
            }
            jdbc.sql("update employee_document set reminded_for = expires_on where id = ?").param(d.id()).update();
        }
        return due.size();
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** The file's real type from its first bytes, whatever its name says. */
    public static String sniff(byte[] b) {
        if (b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') {
            return "application/pdf";
        }
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        return null;
    }

    public static String cleanName(String fileName, String contentType) {
        String n = fileName == null ? "" : fileName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.length() > 150) {
            n = n.substring(n.length() - 150);
        }
        String ext = switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "image/jpeg" -> ".jpg";
            default -> ".png";
        };
        if (n.isEmpty()) {
            n = "document" + ext;
        } else if (!n.toLowerCase(Locale.ROOT).endsWith(ext) && !(ext.equals(".jpg") && n.toLowerCase(Locale.ROOT).endsWith(".jpeg"))) {
            n = n + ext;
        }
        return n;
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
