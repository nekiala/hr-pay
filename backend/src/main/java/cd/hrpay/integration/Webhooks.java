package cd.hrpay.integration;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.ops.JobLocks;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.security.DataCrypto;
import cd.hrpay.tenancy.TenantContext;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Webhooks: when something happens in a company (a payroll closed, a person hired), HR Pay posts a short JSON
 * message to the addresses the company registered. Messages are written in the same transaction as the change, so
 * a change that rolls back sends nothing, then delivered in the background with retries.
 *
 * <p>A message says what happened and to what; the details are read through the API with a key. Each request is
 * signed: {@code X-HRPay-Signature: sha256=HMAC(secret, timestamp + "." + body)} with the timestamp of
 * {@code X-HRPay-Timestamp}.
 */
@Service
public class Webhooks {

    private static final Logger log = LoggerFactory.getLogger(Webhooks.class);

    /** The events a webhook can follow, and the actions of the audit log behind each. */
    public static final Map<String, Set<String>> EVENTS = events();

    private static Map<String, Set<String>> events() {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        m.put("employee.created", Set.of("user.created"));
        m.put("employee.updated", Set.of("user.updated", "user.employment_changed", "user.manager_changed", "profile.saved",
                "user.roles_changed"));
        m.put("contract.started", Set.of("contract.started"));
        m.put("salary.changed", Set.of("salary.add", "salary.anniversary_raise"));
        m.put("departure.confirmed", Set.of("departure.confirmed"));
        m.put("attendance.checked_in", Set.of("attendance.check_in"));
        m.put("attendance.checked_out", Set.of("attendance.check_out", "attendance.auto_close", "attendance.mission_close"));
        m.put("leave.requested", Set.of("leave.requested"));
        m.put("leave.approved", Set.of("leave.approved"));
        m.put("leave.rejected", Set.of("leave.rejected"));
        m.put("payroll.calculated", Set.of("payroll.calculated"));
        m.put("payroll.approved", Set.of("payroll.approved"));
        m.put("payroll.closed", Set.of("payroll.closed"));
        m.put("payment.paid", Set.of("payment.paid"));
        return m;
    }

    /** Details passed on: what identifies the change, never an amount or personal number. */
    private static final Set<String> SAFE_DETAILS = Set.of("month", "payslips", "type", "at", "workDate", "source",
            "effectiveFrom", "start", "paidOn", "method", "provider", "lastDay", "userId", "from", "to", "status", "reason");

    private static final List<Duration> RETRIES = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(6), Duration.ofHours(12), Duration.ofHours(24));

    public record Webhook(long id, String url, List<String> events, boolean active, String createdBy, OffsetDateTime createdAt,
                          int pending, int failed, OffsetDateTime lastSentAt) {}

    public record Created(Webhook webhook, String secret) {}

    public record Delivery(long id, String event, String status, int attempts, Integer lastCode, String lastError,
                           OffsetDateTime createdAt, OffsetDateTime sentAt, OffsetDateTime nextAttemptAt) {}

    private final JdbcClient jdbc;
    private final DataCrypto crypto;
    private final JsonMapper json;
    private final AuditService audit;
    private final JobLocks locks;
    private final boolean allowPrivate;
    private final HttpClient http;

    public Webhooks(JdbcClient jdbc, DataCrypto crypto, JsonMapper json, AuditService audit, JobLocks locks,
                    @Value("${app.webhooks.allow-private:false}") boolean allowPrivate) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.json = json;
        this.audit = audit;
        this.locks = locks;
        this.allowPrivate = allowPrivate;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.getDefault()).build();
    }

    // ---- managed by the administrator -------------------------------------------------------------------------

    private static final String SELECT = """
            select w.id, w.url, w.events, w.active, w.created_by, w.created_at,
                   (select count(*) from webhook_delivery d where d.webhook_id = w.id and d.status = 'PENDING') as pending,
                   (select count(*) from webhook_delivery d where d.webhook_id = w.id and d.status = 'FAILED') as failed,
                   (select max(sent_at) from webhook_delivery d where d.webhook_id = w.id) as last_sent_at
            from webhook w""";

    private static Webhook map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Webhook(rs.getLong("id"), rs.getString("url"), List.of((String[]) rs.getArray("events").getArray()),
                rs.getBoolean("active"), rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getInt("pending"), rs.getInt("failed"), rs.getObject("last_sent_at", OffsetDateTime.class));
    }

    public List<Webhook> list() {
        return jdbc.sql(SELECT + " order by w.id").query((rs, i) -> map(rs)).list();
    }

    public Webhook find(long id) {
        return jdbc.sql(SELECT + " where w.id = ?").param(id).query((rs, i) -> map(rs)).optional()
                .orElseThrow(() -> new NotFoundException("Webhook not found."));
    }

    @Transactional
    public Created create(String url, List<String> events) {
        String clean = checkUrl(url);
        List<String> e = checkEvents(events);
        String secret = "whsec_" + ApiKeys.random(32);
        long id = jdbc.sql("insert into webhook (url, events, secret, created_by) values (?, ?, ?, ?) returning id")
                .params(clean, e.toArray(String[]::new), crypto.encrypt(secret), CurrentUser.email()).query(Long.class).single();
        audit.record("webhook.created", "webhook", id, Map.of("url", clean, "events", e));
        return new Created(find(id), secret);
    }

    @Transactional
    public Webhook update(long id, String url, List<String> events, boolean active) {
        find(id);
        String clean = checkUrl(url);
        List<String> e = checkEvents(events);
        jdbc.sql("update webhook set url = ?, events = ?, active = ? where id = ?").params(clean, e.toArray(String[]::new), active, id).update();
        audit.record("webhook.updated", "webhook", id, Map.of("url", clean, "events", e, "active", active));
        return find(id);
    }

    @Transactional
    public Created rotate(long id) {
        find(id);
        String secret = "whsec_" + ApiKeys.random(32);
        jdbc.sql("update webhook set secret = ? where id = ?").params(crypto.encrypt(secret), id).update();
        audit.record("webhook.secret_rotated", "webhook", id, Map.of());
        return new Created(find(id), secret);
    }

    @Transactional
    public void delete(long id) {
        find(id);
        jdbc.sql("delete from webhook where id = ?").param(id).update();
        audit.record("webhook.deleted", "webhook", id, Map.of());
    }

    /** A test message, delivered like the others. */
    @Transactional
    public void ping(long id) {
        find(id);
        queue(id, "ping", null, null, Map.of());
    }

    public List<Delivery> deliveries(long id) {
        return jdbc.sql("""
                select id, event, status, attempts, last_code, last_error, created_at, sent_at, next_attempt_at
                from webhook_delivery where webhook_id = ? order by id desc limit 50""").param(id).query(Delivery.class).list();
    }

    @Transactional
    public void retry(long webhookId, long deliveryId) {
        jdbc.sql("update webhook_delivery set status = 'PENDING', next_attempt_at = now() where id = ? and webhook_id = ? and status = 'FAILED'")
                .params(deliveryId, webhookId).update();
    }

    private List<String> checkEvents(List<String> events) {
        if (events == null || events.isEmpty()) {
            throw new BusinessRuleException("Choose at least one event.");
        }
        for (String e : events) {
            if (!EVENTS.containsKey(e)) {
                throw new BusinessRuleException("Unknown event %s.".formatted(e));
            }
        }
        return events.stream().distinct().toList();
    }

    /** An https address on the internet: not this server, nor a private network unless the installation allows it. */
    String checkUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("Enter a full address, starting with https://.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (uri.getHost() == null || !(scheme.equals("https") || (allowPrivate && scheme.equals("http")))) {
            throw new BusinessRuleException("Enter a full address, starting with https://.");
        }
        if (uri.getUserInfo() != null) {
            throw new BusinessRuleException("The address cannot hold a user name or password.");
        }
        if (!allowPrivate) {
            try {
                for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
                    if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                            || a.isMulticastAddress() || isUniqueLocal(a) || isCarrierNat(a)) {
                        throw new BusinessRuleException("The address points to a private network: use an address reachable on the internet.");
                    }
                }
            } catch (UnknownHostException e) {
                throw new BusinessRuleException("The address %s cannot be found.".formatted(uri.getHost()));
            }
        }
        return uri.toString();
    }

    private static boolean isUniqueLocal(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    private static boolean isCarrierNat(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
    }

    // ---- queueing, from the audit log -------------------------------------------------------------------------

    @EventListener
    public void on(AuditService.Recorded r) {
        if (TenantContext.companyId() == null || TenantContext.bypass()) {
            return;
        }
        String event = EVENTS.entrySet().stream().filter(e -> e.getValue().contains(r.action())).map(Map.Entry::getKey)
                .findFirst().orElse(null);
        if (event == null) {
            return;
        }
        List<Long> hooks = jdbc.sql("select id from webhook where active and ? = any(events)").param(event).query(Long.class).list();
        for (Long id : hooks) {
            queue(id, event, r.entityType(), r.entityId(), r.details());
        }
    }

    private void queue(long webhookId, String event, String entityType, Object entityId, Map<String, ?> details) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (details != null) {
            details.forEach((k, v) -> {
                if (SAFE_DETAILS.contains(k) && v != null) {
                    data.put(k, v);
                }
            });
        }
        if ("user".equals(entityType) && entityId != null) {
            jdbc.sql("select registration_no from app_user where id = ?").param(Long.valueOf(entityId.toString()))
                    .query(String.class).optional().ifPresent(reg -> data.put("registrationNo", reg));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("occurredAt", OffsetDateTime.now().toString());
        payload.put("companyId", TenantContext.companyId());
        if (entityType != null) {
            payload.put("entity", Map.of("type", entityType, "id", entityId == null ? "" : entityId.toString()));
        }
        payload.put("data", data);
        jdbc.sql("insert into webhook_delivery (webhook_id, event, payload) values (?, ?, cast(? as jsonb))")
                .params(webhookId, event, json.writeValueAsString(payload)).update();
    }

    // ---- delivery ---------------------------------------------------------------------------------------------

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "${app.webhooks.every:PT20S}")
    public void scheduled() {
        locks.runForEachCompany("webhooks", Duration.ofMinutes(5), this::deliverDue);
    }

    record Due(long id, long webhookId, String event, String payload, int attempts, String url, String secret) {}

    public int deliverDue() {
        List<Due> due = jdbc.sql("""
                select d.id, d.webhook_id, d.event, d.payload::text as payload, d.attempts, w.url, w.secret
                from webhook_delivery d join webhook w on w.id = d.webhook_id
                where d.status = 'PENDING' and d.next_attempt_at <= now() and w.active
                order by d.id limit 50""").query(Due.class).list();
        for (Due d : due) {
            deliver(d);
        }
        return due.size();
    }

    private void deliver(Due d) {
        Integer code = null;
        String error = null;
        try {
            checkUrl(d.url());
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            String signature = hmac(crypto.decrypt(d.secret()), timestamp + "." + d.payload());
            HttpRequest request = HttpRequest.newBuilder(URI.create(d.url())).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "HR-Pay-Webhooks/1")
                    .header("X-HRPay-Event", d.event())
                    .header("X-HRPay-Delivery", String.valueOf(d.id()))
                    .header("X-HRPay-Timestamp", timestamp)
                    .header("X-HRPay-Signature", "sha256=" + signature)
                    .POST(HttpRequest.BodyPublishers.ofString(d.payload(), StandardCharsets.UTF_8)).build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            code = response.statusCode();
            if (code >= 200 && code < 300) {
                jdbc.sql("update webhook_delivery set status = 'SENT', attempts = attempts + 1, last_code = ?, last_error = null, sent_at = now() where id = ?")
                        .params(code, d.id()).update();
                return;
            }
            error = "HTTP " + code;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Interrupted";
        } catch (Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        int attempts = d.attempts() + 1;
        boolean last = attempts > RETRIES.size();
        OffsetDateTime next = OffsetDateTime.now().plus(last ? Duration.ZERO : RETRIES.get(attempts - 1));
        jdbc.sql("update webhook_delivery set status = ?, attempts = ?, last_code = ?, last_error = ?, next_attempt_at = ? where id = ?")
                .params(last ? "FAILED" : "PENDING", attempts, code, error.length() > 300 ? error.substring(0, 300) : error, next, d.id())
                .update();
        if (last) {
            log.warn("Webhook {} gave up on delivery {} ({}) after {} attempts: {}", d.webhookId(), d.id(), d.event(), attempts, error);
        }
    }

    static String hmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
