package cd.hrpay.privacy;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.security.DataCrypto;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * A person's rights over their data: a copy of everything held about them, corrections they ask HR for, and the
 * erasure of a former employee's personal data once the company's retention period after they left has passed.
 * Erasure keeps the payroll amounts, the registration number and the audit log, which the employer must keep.
 */
@Service
public class PersonalData {

    public record Correction(long id, long userId, String name, String field, String current, String requested,
                             String reason, String status, String answer, OffsetDateTime createdAt,
                             OffsetDateTime decidedAt, String decidedBy) {}

    public record Due(long userId, String name, String registrationNo, LocalDate leftOn, LocalDate eraseFrom) {}

    /** Tables that are about one person but hold secrets or delivery internals, not data about them. */
    private static final Set<String> NOT_EXPORTED = Set.of("user_two_step", "user_session", "otp_challenge", "outbox_message");
    private static final Pattern SECRET_COLUMN = Pattern.compile(".*(hash|secret|recovery_codes|session_id|token).*");
    private static final Pattern ENCRYPTED = Pattern.compile("enc:v1:[A-Za-z0-9+/=]+");
    static final Set<String> FIELDS = Set.of("NAME", "EMAIL", "PHONE", "BIRTH_DATE", "MARITAL_STATUS", "DEPENDANTS",
            "NATIONALITY", "CNSS_NUMBER", "TAX_NUMBER", "BANK_ACCOUNT", "MOBILE_MONEY", "OTHER");

    private final JdbcClient jdbc;
    private final DataCrypto crypto;
    private final AuditService audit;
    private final JsonMapper json = JsonMapper.builder().build();

    public PersonalData(JdbcClient jdbc, DataCrypto crypto, AuditService audit) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.audit = audit;
    }

    // ---- a copy of everything -------------------------------------------------------------------------------

    /** Every row about the person, table by table, as JSON; identifiers decrypted, secrets left out. */
    @Transactional
    public String export(long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportedAt", OffsetDateTime.now().toString());
        out.put("person", rows("select * from app_user where id = ?", userId));
        List<String> tables = jdbc.sql("""
                select table_name from information_schema.columns
                where table_schema = 'public' and column_name = 'user_id' order by table_name""").query(String.class).list();
        for (String table : tables) {
            if (!NOT_EXPORTED.contains(table)) {
                out.put(table, rows("select * from " + table + " where user_id = ?", userId));
            }
        }
        out.put("sign_ins", rows("""
                select method, ip, user_agent, signed_in_at, last_seen_at, ended_at from user_session where user_id = ?
                order by signed_in_at desc""", userId));
        out.put("audit_log", rows("""
                select occurred_at, actor, action, entity_type, entity_id, details from audit_log
                where (entity_type = 'user' and entity_id = ?::text) or lower(actor) = (select lower(email) from app_user where id = ?)
                order by id""", userId, userId));
        audit.record("privacy.data_exported", "user", userId, Map.of("by", String.valueOf(CurrentUser.email())));
        return decrypt(json.writerWithDefaultPrettyPrinter().writeValueAsString(out));
    }

    private List<Map<String, Object>> rows(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query((rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            var meta = rs.getMetaData();
            for (int c = 1; c <= meta.getColumnCount(); c++) {
                String name = meta.getColumnLabel(c);
                if (SECRET_COLUMN.matcher(name).matches() || name.equals("company_id")) {
                    continue;
                }
                Object v = rs.getObject(c);
                if (v instanceof java.sql.Array a) {
                    v = List.of((Object[]) a.getArray());
                } else if (meta.getColumnTypeName(c).startsWith("json") && rs.getString(c) != null) {
                    v = json.readTree(rs.getString(c));
                } else if (v != null && v.getClass().getName().startsWith("org.postgresql")) {
                    v = rs.getString(c);
                } else if (v instanceof java.sql.Timestamp || v instanceof java.sql.Date || v instanceof java.sql.Time
                        || v instanceof java.time.temporal.Temporal) {
                    v = v.toString();
                } else if (v instanceof byte[]) {
                    v = "(binary)";
                }
                row.put(name, v);
            }
            return row;
        }).list();
    }

    /** Encrypted identifiers anywhere in the copy, columns or JSON alike, read as they were entered. */
    private String decrypt(String text) {
        Matcher m = ENCRYPTED.matcher(text);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String plain = json.writeValueAsString(crypto.decrypt(m.group()));
            m.appendReplacement(b, Matcher.quoteReplacement(plain.substring(1, plain.length() - 1)));
        }
        m.appendTail(b);
        return b.toString();
    }

    // ---- corrections -----------------------------------------------------------------------------------------

    private static final String CORRECTION_SELECT = """
            select c.id, c.user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, c.field, c.current, c.requested,
                   c.reason, c.status, c.answer, c.created_at, c.decided_at, c.decided_by
            from data_correction_request c join app_user u on u.id = c.user_id""";

    @Transactional
    public Correction ask(long userId, String field, String current, String requested, String reason) {
        if (!FIELDS.contains(field)) {
            throw new BusinessRuleException("Choose what to correct.");
        }
        long open = jdbc.sql("select count(*) from data_correction_request where user_id = ? and status = 'OPEN'")
                .param(userId).query(Long.class).single();
        if (open >= 10) {
            throw new BusinessRuleException("You already have 10 requests waiting for HR.");
        }
        long id = jdbc.sql("""
                insert into data_correction_request (user_id, field, current, requested, reason) values (?, ?, ?, ?, ?)
                returning id""").params(userId, field, blank(current), requested.trim(), blank(reason)).query(Long.class).single();
        audit.record("privacy.correction_requested", "user", userId, Map.of("field", field, "request", id));
        return correction(id);
    }

    public Correction correction(long id) {
        return jdbc.sql(CORRECTION_SELECT + " where c.id = ?").param(id).query(Correction.class).optional()
                .orElseThrow(() -> new NotFoundException("Request not found."));
    }

    public List<Correction> corrections(Long userId, String status) {
        return jdbc.sql(CORRECTION_SELECT + """
                 where (cast(? as bigint) is null or c.user_id = ?) and (cast(? as text) is null or c.status = ?)
                order by c.status = 'OPEN' desc, c.created_at desc limit 500""")
                .params(userId, userId, status, status).query(Correction.class).list();
    }

    @Transactional
    public Correction decide(long id, boolean done, String answer) {
        Correction c = correction(id);
        if (!c.status().equals("OPEN")) {
            throw new BusinessRuleException("This request was already answered.");
        }
        if (!done && (answer == null || answer.isBlank())) {
            throw new BusinessRuleException("Say why the correction is refused.");
        }
        jdbc.sql("""
                update data_correction_request set status = ?, answer = ?, decided_at = now(), decided_by = ? where id = ?""")
                .params(done ? "DONE" : "REFUSED", blank(answer), CurrentUser.email(), id).update();
        audit.record("privacy.correction_" + (done ? "done" : "refused"), "user", c.userId(), Map.of("request", id));
        return correction(id);
    }

    // ---- erasure after the retention period ------------------------------------------------------------------

    public int retentionYears() {
        return jdbc.sql("select erase_after_years from company where id = current_company_id()").query(Integer.class).single();
    }

    @Transactional
    public void setRetentionYears(int years) {
        if (years < 1 || years > 50) {
            throw new BusinessRuleException("Keep the data between 1 and 50 years.");
        }
        jdbc.sql("update company set erase_after_years = ? where id = current_company_id()").param(years).update();
        audit.record("privacy.retention_changed", "company", cd.hrpay.tenancy.TenantContext.companyId(), Map.of("years", years));
    }

    /**
     * Former employees whose retention period has passed: the account is disabled, no contract is open, and the last
     * one ended more than the retention period ago.
     */
    public List<Due> due() {
        return jdbc.sql("""
                select u.id as user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no,
                       max(c.end_date) as left_on,
                       (max(c.end_date) + make_interval(years => co.erase_after_years))::date as erase_from
                from app_user u join employee_contract c on c.user_id = u.id
                join company co on co.id = u.company_id
                where u.status = 'DISABLED' and u.erased_at is null
                group by u.id, co.erase_after_years
                having bool_and(c.end_date is not null)
                   and max(c.end_date) + make_interval(years => co.erase_after_years) <= current_date
                order by left_on""").query(Due.class).list();
    }

    /** Erases the person's personal data. Cannot be undone. */
    @Transactional
    public void erase(long userId) {
        if (due().stream().noneMatch(d -> d.userId() == userId)) {
            throw new BusinessRuleException("Only a former employee whose retention period has passed can be erased.");
        }
        String name = "Erased #" + userId;
        jdbc.sql("""
                update app_user set first_name = 'Erased', last_name = '#' || id, middle_name = null, email = 'erased-' || id || '@erased.invalid',
                       phone_e164 = '+999' || lpad(id::text, 11, '0'), password_hash = null, manager_id = null,
                       erased_at = now(), updated_at = now() where id = ?""").param(userId).update();
        jdbc.sql("delete from employee_profile where user_id = ?").param(userId).update();
        jdbc.sql("delete from dependant where user_id = ?").param(userId).update();
        jdbc.sql("delete from user_two_step where user_id = ?").param(userId).update();
        jdbc.sql("delete from user_session where user_id = ?").param(userId).update();
        jdbc.sql("delete from data_correction_request where user_id = ?").param(userId).update();
        jdbc.sql("update clock_event set lat = null, lng = null, accuracy_m = null where user_id = ?").param(userId).update();
        // Payslips keep their amounts; the copy of the person's details on them goes.
        jdbc.sql("""
                update payslip set snapshot = (snapshot - array['email', 'cnssNumber', 'taxNumber', 'bankName', 'bankAccount',
                        'mobileMoneyOperator', 'mobileMoneyNumber', 'maritalStatus', 'childrenInCharge', 'dependantsInCharge'])
                        || jsonb_build_object('name', ?::text)
                where user_id = ?""").params(name, userId).update();
        audit.record("privacy.erased", "user", userId, Map.of());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
