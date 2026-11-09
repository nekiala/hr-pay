package cd.hrpay.notify;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.AppProperties;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.security.DataCrypto;
import cd.hrpay.tenancy.TenantContext;
import cd.hrpay.tenancy.Tenants;
import cd.hrpay.user.AppUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The WhatsApp accounts (Meta WhatsApp Cloud API) that send HR Pay's messages, and the calls to Meta. A company's
 * people get messages from the company's own number when it set one, otherwise from the platform's number (the
 * provider's Meta account, set in its console), otherwise from the WHATSAPP_* settings of .env (codes only).
 */
@Service
public class WhatsApp {

    private static final Logger log = LoggerFactory.getLogger(WhatsApp.class);

    public enum Level { COMPANY, PLATFORM, ENV }

    /** An account ready to send. {@code businessId} is null for the .env account (its templates are not managed here). */
    public record Account(Level level, Long companyId, String phoneNumberId, String businessId, String token,
                          String appSecret, Map<String, TemplateStatus> templates) {
        boolean approved(String template, String language) {
            if (businessId == null) {
                return true;
            }
            TemplateStatus s = templates.get(template + "|" + language);
            return s != null && "APPROVED".equals(s.status());
        }
    }

    public record TemplateStatus(String status, String id, String reason, OffsetDateTime checkedAt) {}

    /** The settings as pages show them: secrets never leave the server, only whether one is set. */
    public record Settings(boolean enabled, String phoneNumberId, String businessId, String displayNumber,
                           boolean tokenSet, boolean appSecretSet, String verifyToken, String webhookUrl,
                           List<TemplateRow> templates, String updatedBy, OffsetDateTime updatedAt) {}

    public record TemplateRow(String name, String language, String category, String status, String reason,
                              OffsetDateTime checkedAt) {}

    /** What a page sends. Empty secrets keep the ones already saved. */
    public record Input(boolean enabled, String phoneNumberId, String businessId, String displayNumber,
                        String accessToken, String appSecret) {}

    /** The company's page: whether its people get messages, which kinds, and its own number if any. */
    public record CompanyView(boolean enabled, List<String> kinds, boolean ownNumber, Settings own, Level inUse,
                              String inUseNumber, int optedIn, int employees) {}

    public record CompanyInput(boolean enabled, List<String> kinds, boolean ownNumber, Input own) {}

    public record Sent(boolean ok, String providerId, String error) {}

    private record Row(boolean enabled, String phoneNumberId, String businessId, String displayNumber,
                       String accessToken, String appSecret, String verifyToken, String templates, String updatedBy,
                       OffsetDateTime updatedAt) {}

    private record CompanyRow(boolean enabled, String kinds, boolean ownNumber, String phoneNumberId,
                              String businessId, String displayNumber, String accessToken, String appSecret,
                              String verifyToken, String templates, String updatedBy, OffsetDateTime updatedAt) {
        Row own() {
            return new Row(ownNumber, phoneNumberId, businessId, displayNumber, accessToken, appSecret, verifyToken,
                    templates, updatedBy, updatedAt);
        }
    }

    private final JdbcClient jdbc;
    private final Tenants tenants;
    private final DataCrypto crypto;
    private final AuditService audit;
    private final AppProperties properties;
    private final RestClient meta;
    private final JsonMapper json = JsonMapper.builder().build();
    private final SecureRandom random = new SecureRandom();

    public WhatsApp(JdbcClient jdbc, Tenants tenants, DataCrypto crypto, AuditService audit, AppProperties properties,
                    @Value("${app.whatsapp.api-url:https://graph.facebook.com/v21.0}") String apiUrl) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.crypto = crypto;
        this.audit = audit;
        this.properties = properties;
        this.meta = RestClient.builder().baseUrl(apiUrl).build();
    }

    public String otpTemplate() {
        AppProperties.Notifications n = properties.notifications();
        return n == null || n.whatsappTemplate() == null || n.whatsappTemplate().isBlank() ? "hrpay_otp" : n.whatsappTemplate();
    }

    // ---- choosing the account -----------------------------------------------------------------------------------

    /** The account that sends to this company's people, if any. */
    public Optional<Account> resolve(long companyId) {
        Optional<CompanyRow> own = companyRow(companyId).filter(CompanyRow::ownNumber);
        if (own.isPresent()) {
            return Optional.of(account(Level.COMPANY, companyId, own.get().own()));
        }
        Optional<Row> platform = platformRow().filter(Row::enabled);
        if (platform.isPresent()) {
            return Optional.of(account(Level.PLATFORM, null, platform.get()));
        }
        AppProperties.Notifications n = properties.notifications();
        if (n != null && n.whatsappEnabled()) {
            return Optional.of(new Account(Level.ENV, null, n.whatsappPhoneNumberId(), null, n.whatsappToken(), null, Map.of()));
        }
        return Optional.empty();
    }

    /** Whether this company sends notifications on WhatsApp, and which kinds. */
    public List<String> enabledKinds(long companyId) {
        return companyRow(companyId).filter(CompanyRow::enabled).map(r -> kinds(r.kinds())).orElse(List.of());
    }

    private Account account(Level level, Long companyId, Row r) {
        return new Account(level, companyId, r.phoneNumberId(), r.businessId(), crypto.decrypt(r.accessToken()),
                r.appSecret() == null ? null : crypto.decrypt(r.appSecret()), statuses(r.templates()));
    }

    // ---- sending ------------------------------------------------------------------------------------------------

    /** Sends a template. Records the message on the Messages page with Meta's id, to follow its delivery. */
    public Sent sendTemplate(Account a, AppUser user, String to, String template, String language,
                             List<String> bodyParams, String urlParam, String textForOutbox) {
        List<Object> components = new ArrayList<>();
        components.add(Map.of("type", "body", "parameters",
                bodyParams.stream().map(p -> Map.of("type", "text", "text", p)).toList()));
        if (urlParam != null) {
            components.add(Map.of("type", "button", "sub_type", "url", "index", "0",
                    "parameters", List.of(Map.of("type", "text", "text", urlParam))));
        }
        Map<String, Object> payload = Map.of("messaging_product", "whatsapp", "to", to.replace("+", ""),
                "type", "template", "template", Map.of("name", template, "language", Map.of("code", language),
                        "components", components));
        return post(a, user, to, payload, textForOutbox);
    }

    /** A free reply, allowed by Meta within 24 hours of the person's last message. */
    public Sent sendText(Account a, AppUser user, String to, String text) {
        return post(a, user, to, Map.of("messaging_product", "whatsapp", "to", to.replace("+", ""), "type", "text",
                "text", Map.of("body", text)), text);
    }

    private Sent post(Account a, AppUser user, String to, Map<String, Object> payload, String textForOutbox) {
        String id = null;
        String error = null;
        try {
            String raw = meta.post().uri("/{id}/messages", a.phoneNumberId())
                    .header("Authorization", "Bearer " + a.token())
                    .body(payload).retrieve().body(String.class);
            JsonNode r = read(raw);
            JsonNode m = r == null ? null : r.path("messages").path(0).path("id");
            id = m == null || m.isMissingNode() ? null : m.asText();
        } catch (RestClientResponseException e) {
            error = metaError(e);
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        if (error != null) {
            log.warn("WhatsApp to {} failed: {}", to, error);
        }
        jdbc.sql("""
                insert into outbox_message (user_id, channel, recipient, body, status, error, via, provider_id)
                values (?, 'WHATSAPP', ?, ?, ?, ?, ?, ?)""")
                .params(user == null ? null : user.getId(), to, textForOutbox, error == null ? "SENT" : "FAILED",
                        cut(error, 500), a.level() == Level.ENV ? "ENV" : a.level().name(), id).update();
        return new Sent(error == null, id, error);
    }

    // ---- the platform's account -----------------------------------------------------------------------------------

    public Settings platform() {
        return platformRow().map(r -> settings(r, "platform")).orElse(null);
    }

    @Transactional
    public Settings savePlatform(Input in, String by) {
        Optional<Row> old = platformRow();
        Row r = checked(in, old.orElse(null));
        jdbc.sql("""
                insert into platform_whatsapp (id, enabled, phone_number_id, business_id, display_number, access_token,
                                               app_secret, verify_token, updated_by, updated_at)
                values (1, ?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (id) do update set enabled = excluded.enabled, phone_number_id = excluded.phone_number_id,
                    business_id = excluded.business_id, display_number = excluded.display_number,
                    access_token = excluded.access_token, app_secret = excluded.app_secret, updated_by = excluded.updated_by,
                    updated_at = now()""")
                .params(r.enabled(), r.phoneNumberId(), r.businessId(), r.displayNumber(), r.accessToken(), r.appSecret(),
                        r.verifyToken(), by).update();
        audit.record("whatsapp.platform_saved", "platform_whatsapp", 1, Map.of("enabled", r.enabled(),
                "phoneNumberId", r.phoneNumberId()));
        return platform();
    }

    // ---- a company's settings -------------------------------------------------------------------------------------

    public CompanyView company() {
        long companyId = TenantContext.companyId();
        Optional<CompanyRow> row = companyRow(companyId);
        Optional<Account> a = resolve(companyId);
        String number = a.map(x -> switch (x.level()) {
            case COMPANY -> row.map(CompanyRow::displayNumber).orElse(null);
            case PLATFORM -> platformRow().map(Row::displayNumber).orElse(null);
            case ENV -> null;
        }).orElse(null);
        int optedIn = jdbc.sql("""
                select count(*) from app_user where status = 'ACTIVE' and whatsapp_opt_in_at is not null
                and whatsapp_phone = phone_e164""").query(Integer.class).single();
        int employees = jdbc.sql("select count(*) from app_user where status = 'ACTIVE'").query(Integer.class).single();
        return new CompanyView(row.map(CompanyRow::enabled).orElse(false),
                row.map(r -> kinds(r.kinds())).orElse(List.of("PAYSLIP", "DECISIONS", "ACTIONS", "REMINDERS")),
                row.map(CompanyRow::ownNumber).orElse(false),
                row.filter(r -> r.phoneNumberId() != null).map(r -> settings(r.own(), "company")).orElse(null),
                a.map(Account::level).orElse(null), number, optedIn, employees);
    }

    @Transactional
    public CompanyView saveCompany(CompanyInput in, String by) {
        long companyId = TenantContext.companyId();
        List<String> kinds = in.kinds() == null ? List.of() : in.kinds().stream().distinct().toList();
        for (String k : kinds) {
            try {
                WhatsAppTemplates.Group.valueOf(k);
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleException("Unknown kind of message: " + k + ".");
            }
        }
        Optional<CompanyRow> old = companyRow(companyId);
        Row own = null;
        if (in.ownNumber() || in.own() != null && notBlank(in.own().phoneNumberId())) {
            Input o = in.own() == null ? new Input(true, null, null, null, null, null) : in.own();
            own = checked(new Input(in.ownNumber(), o.phoneNumberId(), o.businessId(), o.displayNumber(), o.accessToken(),
                    o.appSecret()), old.filter(r -> r.phoneNumberId() != null).map(CompanyRow::own).orElse(null));
        }
        if (in.enabled() && !in.ownNumber() && platformRow().filter(Row::enabled).isEmpty()
                && (properties.notifications() == null || !properties.notifications().whatsappEnabled())) {
            throw new BusinessRuleException("No WhatsApp number is set up yet: enter your own, or ask your provider to set up theirs.");
        }
        jdbc.sql("""
                insert into company_whatsapp (enabled, kinds, own_number, phone_number_id, business_id, display_number,
                                              access_token, app_secret, verify_token, updated_by, updated_at)
                values (?, ?::text[], ?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (company_id) do update set enabled = excluded.enabled, kinds = excluded.kinds,
                    own_number = excluded.own_number, phone_number_id = excluded.phone_number_id,
                    business_id = excluded.business_id, display_number = excluded.display_number,
                    access_token = excluded.access_token, app_secret = excluded.app_secret,
                    verify_token = coalesce(company_whatsapp.verify_token, excluded.verify_token), alerted_at = null,
                    updated_by = excluded.updated_by, updated_at = now()""")
                .params(in.enabled(), "{" + String.join(",", kinds) + "}", in.ownNumber(),
                        own == null ? null : own.phoneNumberId(), own == null ? null : own.businessId(),
                        own == null ? null : own.displayNumber(), own == null ? null : own.accessToken(),
                        own == null ? null : own.appSecret(), own == null ? token() : own.verifyToken(), by).update();
        audit.record("whatsapp.company_saved", "company_whatsapp", companyId, Map.of("enabled", in.enabled(),
                "kinds", kinds, "ownNumber", in.ownNumber()));
        return company();
    }

    // ---- templates at Meta ----------------------------------------------------------------------------------------

    /** Submits every template, in French and English, to Meta for approval; those already there are only read back. */
    @Transactional
    public Settings submitTemplates(boolean platform) {
        Account a = managed(platform);
        String base = properties.publicUrl();
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (String lang : WhatsAppTemplates.LANGUAGES) {
            payloads.add(WhatsAppTemplates.otpPayload(otpTemplate(), lang));
            for (WhatsAppTemplates.Template t : WhatsAppTemplates.NOTIFICATIONS) {
                payloads.add(WhatsAppTemplates.payload(t, lang, base));
            }
        }
        Map<String, TemplateStatus> statuses = new LinkedHashMap<>(a.templates());
        for (Map<String, Object> p : payloads) {
            String key = p.get("name") + "|" + p.get("language");
            try {
                JsonNode r = read(meta.post().uri("/{waba}/message_templates", a.businessId())
                        .header("Authorization", "Bearer " + a.token()).body(p).retrieve().body(String.class));
                statuses.put(key, new TemplateStatus(r.path("status").asText("PENDING"), r.path("id").asText(null), null,
                        OffsetDateTime.now()));
            } catch (RestClientResponseException e) {
                String error = metaError(e);
                // Already submitted: its status comes from the refresh below.
                if (!error.toLowerCase().contains("already") && !error.contains("2388024") && !error.contains("2388023")) {
                    statuses.put(key, new TemplateStatus("NOT_SUBMITTED", null, cut(error, 300), OffsetDateTime.now()));
                }
            } catch (RestClientException e) {
                // Meta could not be reached: this one stays unsubmitted, the others are still tried.
                statuses.put(key, new TemplateStatus("NOT_SUBMITTED", null, cut(e.getMessage(), 300), OffsetDateTime.now()));
            }
        }
        saveStatuses(platform, statuses);
        audit.record("whatsapp.templates_submitted", platform ? "platform_whatsapp" : "company_whatsapp",
                platform ? 1 : TenantContext.companyId(), Map.of("count", payloads.size()));
        return refreshTemplates(platform);
    }

    /** Reads from Meta whether each template is approved, waiting or rejected (with the reason). */
    @Transactional
    public Settings refreshTemplates(boolean platform) {
        Account a = managed(platform);
        Map<String, TemplateStatus> statuses = new LinkedHashMap<>(a.templates());
        java.util.Set<String> ours = WhatsAppTemplates.names(otpTemplate());
        try {
            JsonNode r = read(meta.get().uri("/{waba}/message_templates?fields=name,language,status,rejected_reason,id&limit=200",
                    a.businessId()).header("Authorization", "Bearer " + a.token()).retrieve().body(String.class));
            for (JsonNode t : r.path("data")) {
                String name = t.path("name").asText();
                if (!ours.contains(name)) {
                    continue;
                }
                String reason = t.path("rejected_reason").asText(null);
                statuses.put(name + "|" + t.path("language").asText(), new TemplateStatus(t.path("status").asText(),
                        t.path("id").asText(null), reason == null || reason.equals("NONE") ? null : reason, OffsetDateTime.now()));
            }
        } catch (RestClientResponseException e) {
            throw new BusinessRuleException("Meta refused: " + cut(metaError(e), 300));
        } catch (RestClientException e) {
            throw new BusinessRuleException("Meta could not be reached: " + cut(e.getMessage(), 300));
        }
        saveStatuses(platform, statuses);
        return platform ? platform() : company().own();
    }

    private Account managed(boolean platform) {
        Optional<Row> row = platform ? platformRow() : companyRow(TenantContext.companyId()).filter(r -> r.phoneNumberId() != null)
                .map(CompanyRow::own);
        Row r = row.orElseThrow(() -> new BusinessRuleException("Save the WhatsApp account first."));
        return account(platform ? Level.PLATFORM : Level.COMPANY, platform ? null : TenantContext.companyId(), r);
    }

    private void saveStatuses(boolean platform, Map<String, TemplateStatus> statuses) {
        String value = json.writeValueAsString(statuses);
        if (platform) {
            jdbc.sql("update platform_whatsapp set templates = ?::jsonb where id = 1").param(value).update();
        } else {
            jdbc.sql("update company_whatsapp set templates = ?::jsonb").param(value).update();
        }
    }

    // ---- the webhook ----------------------------------------------------------------------------------------------

    /** Meta's check when the webhook is registered: the verify token must be one of ours. */
    public boolean verifyToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return tenants.callAsPlatform(() -> jdbc.sql("""
                select count(*) from (select verify_token from platform_whatsapp union all
                                      select verify_token from company_whatsapp) v where verify_token = ?""")
                .param(token).query(Integer.class).single() > 0);
    }

    /** The account a webhook call is about, found by its phone number id, across companies. */
    public Optional<Account> byPhoneNumberId(String phoneNumberId) {
        return tenants.callAsPlatform(() -> {
            Optional<Row> p = platformRow().filter(r -> r.phoneNumberId().equals(phoneNumberId));
            if (p.isPresent()) {
                return Optional.of(account(Level.PLATFORM, null, p.get()));
            }
            Long company = jdbc.sql("select company_id from company_whatsapp where own_number and phone_number_id = ?")
                    .param(phoneNumberId).query(Long.class).optional().orElse(null);
            return company == null ? Optional.<Account>empty()
                    : companyRow(company).map(r -> account(Level.COMPANY, company, r.own()));
        });
    }

    /** Meta signs each call with the app secret (X-Hub-Signature-256: sha256=HMAC of the raw body). */
    public static boolean signed(String appSecret, byte[] body, String header) {
        if (appSecret == null || header == null || !header.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            byte[] given = HexFormat.of().parseHex(header.substring(7));
            return MessageDigest.isEqual(expected, given);
        } catch (Exception e) {
            return false;
        }
    }

    /** What Meta reports about a message: delivered, read or failed. */
    public void status(String providerId, String status, String error) {
        tenants.callAsPlatform(() -> switch (status) {
            case "delivered" -> jdbc.sql("update outbox_message set delivered_at = coalesce(delivered_at, now()) where provider_id = ?")
                    .param(providerId).update();
            case "read" -> jdbc.sql("""
                    update outbox_message set read_at = coalesce(read_at, now()), delivered_at = coalesce(delivered_at, now())
                    where provider_id = ?""").param(providerId).update();
            case "failed" -> jdbc.sql("update outbox_message set status = 'FAILED', error = ? where provider_id = ?")
                    .params(cut(error == null ? "Meta reported a failure." : error, 500), providerId).update();
            default -> 0;
        });
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private Row checked(Input in, Row old) {
        if (!notBlank(in.phoneNumberId()) || !in.phoneNumberId().trim().matches("\\d{5,40}")) {
            throw new BusinessRuleException("Enter the phone number ID shown in Meta's WhatsApp Manager (digits only).");
        }
        if (!notBlank(in.businessId()) || !in.businessId().trim().matches("\\d{5,40}")) {
            throw new BusinessRuleException("Enter the WhatsApp Business Account ID (digits only).");
        }
        String token = notBlank(in.accessToken()) ? crypto.encrypt(in.accessToken().trim()) : old == null ? null : old.accessToken();
        if (token == null) {
            throw new BusinessRuleException("Enter the access token of a Meta system user.");
        }
        String secret = notBlank(in.appSecret()) ? crypto.encrypt(in.appSecret().trim()) : old == null ? null : old.appSecret();
        if (secret == null) {
            throw new BusinessRuleException("Enter the app secret: it proves that delivery reports come from Meta.");
        }
        String display = notBlank(in.displayNumber()) ? in.displayNumber().trim() : null;
        if (display != null && !display.matches("\\+?[0-9 ]{8,20}")) {
            throw new BusinessRuleException("Enter the number as people see it, for example +243 81 234 5678.");
        }
        return new Row(in.enabled(), in.phoneNumberId().trim(), in.businessId().trim(), display, token, secret,
                old == null || old.verifyToken() == null ? token() : old.verifyToken(), old == null ? "{}" : old.templates(),
                null, null);
    }

    private Settings settings(Row r, String scope) {
        Map<String, TemplateStatus> s = statuses(r.templates());
        List<TemplateRow> rows = new ArrayList<>();
        for (String name : WhatsAppTemplates.names(otpTemplate())) {
            for (String lang : WhatsAppTemplates.LANGUAGES) {
                TemplateStatus t = s.get(name + "|" + lang);
                rows.add(new TemplateRow(name, lang, name.equals(otpTemplate()) ? "AUTHENTICATION" : "UTILITY",
                        t == null ? "NOT_SUBMITTED" : t.status(), t == null ? null : t.reason(), t == null ? null : t.checkedAt()));
            }
        }
        String base = properties.publicUrl() == null ? "" : properties.publicUrl().replaceAll("/+$", "");
        return new Settings(r.enabled(), r.phoneNumberId(), r.businessId(), r.displayNumber(), r.accessToken() != null,
                r.appSecret() != null, r.verifyToken(), base + "/api/public/whatsapp/webhook", rows, r.updatedBy(),
                r.updatedAt());
    }

    private Map<String, TemplateStatus> statuses(String stored) {
        if (stored == null || stored.isBlank()) {
            return Map.of();
        }
        Map<String, TemplateStatus> out = new LinkedHashMap<>();
        JsonNode n = json.readTree(stored);
        for (var e : n.properties()) {
            JsonNode v = e.getValue();
            out.put(e.getKey(), new TemplateStatus(v.path("status").asText(null), v.path("id").asText(null),
                    v.path("reason").asText(null), v.hasNonNull("checkedAt") ? OffsetDateTime.parse(v.path("checkedAt").asText()) : null));
        }
        return out;
    }

    private Optional<Row> platformRow() {
        return tenants.callAsPlatform(() -> jdbc.sql("""
                select enabled, phone_number_id, business_id, display_number, access_token, app_secret, verify_token,
                       templates::text as templates, updated_by, updated_at from platform_whatsapp where id = 1""")
                .query(Row.class).optional());
    }

    private Optional<CompanyRow> companyRow(long companyId) {
        return tenants.callAs(companyId, () -> jdbc.sql("""
                select enabled, array_to_string(kinds, ',') as kinds, own_number, phone_number_id, business_id,
                       display_number, access_token, app_secret, verify_token, templates::text as templates, updated_by,
                       updated_at from company_whatsapp""").query(CompanyRow.class).optional());
    }

    private static List<String> kinds(String csv) {
        return csv == null || csv.isBlank() ? List.of() : List.of(csv.split(","));
    }

    private String token() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** Meta's answers are JSON, though Graph API sometimes labels them text/javascript: read the text and parse it. */
    private JsonNode read(String body) {
        return body == null || body.isBlank() ? json.missingNode() : json.readTree(body);
    }

    private static String metaError(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        return e.getStatusCode().value() + " " + (body == null || body.isBlank() ? e.getStatusText() : body);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
