package cd.hrpay.payment;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.AppProperties;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.Notifications;
import cd.hrpay.ops.JobLocks;
import cd.hrpay.security.DataCrypto;
import cd.hrpay.tenancy.TenantContext;
import cd.hrpay.tenancy.Tenants;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mobile money payouts through NTPay's payment batches ("paiements de masse", https://ntpay.cloud/docs). A mobile money
 * payment file of a DRC company is sent as one batch; NTPay pays it from the company's own NTPay balance after the
 * company's approval circuit and NTPay's validation. Each line's result comes back by signed notifications (and a
 * status check every few minutes); when the batch is finished, the paid lines settle the file and the failed ones
 * leave it, to be paid again once the number is corrected.
 */
@Service
public class Ntpay {

    private static final Logger log = LoggerFactory.getLogger(Ntpay.class);

    /** NTPay's batch statuses while the batch may still pay, and the line statuses. */
    static final List<String> OPEN = List.of("pending_approval", "scheduled", "approved");

    public record Settings(boolean configured, boolean enabled, boolean testMode, boolean hasSignatureKey,
                           String notificationUrl, OffsetDateTime checkedAt, String checkResult, String updatedBy,
                           OffsetDateTime updatedAt) {}

    /** A blank key keeps the one saved. */
    public record SettingsInput(Boolean enabled, Boolean testMode, String apiKey, String signatureKey) {}

    private record Config(long companyId, boolean enabled, boolean testMode, String apiKey, String signatureKey,
                          String webhookToken) {}

    private record Answer(int status, JsonNode body, String raw) {}

    private record FileLine(long id, long userId, String beneficiary, String account, BigDecimal amount) {}

    private final JdbcClient jdbc;
    private final Tenants tenants;
    private final TransactionTemplate tx;
    private final DataCrypto crypto;
    private final Payments payments;
    private final Notifications notifications;
    private final AuditService audit;
    private final AppProperties properties;
    private final JobLocks jobs;
    private final Clock clock;
    private final RestClient client;
    private final JsonMapper json = JsonMapper.builder().build();
    private final SecureRandom random = new SecureRandom();

    public Ntpay(JdbcClient jdbc, Tenants tenants, TransactionTemplate tx, DataCrypto crypto, Payments payments,
                 Notifications notifications, AuditService audit, AppProperties properties, JobLocks jobs, Clock clock,
                 @Value("${app.ntpay.api-url:https://ntpay.cloud/api}") String apiUrl) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.tx = tx;
        this.crypto = crypto;
        this.payments = payments;
        this.notifications = notifications;
        this.audit = audit;
        this.properties = properties;
        this.jobs = jobs;
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.client = RestClient.builder().baseUrl(apiUrl).requestFactory(factory).build();
    }

    // ---- settings -----------------------------------------------------------------------------------------------

    private Optional<Config> config() {
        return jdbc.sql("select company_id, enabled, test_mode, api_key, signature_key, webhook_token from company_ntpay")
                .query((rs, i) -> new Config(rs.getLong(1), rs.getBoolean(2), rs.getBoolean(3), crypto.decrypt(rs.getString(4)),
                        rs.getString(5) == null ? null : crypto.decrypt(rs.getString(5)), rs.getString(6)))
                .optional();
    }

    private String notificationUrl(String token) {
        String base = properties.publicUrl() == null ? "" : properties.publicUrl().replaceAll("/+$", "");
        return base + "/api/public/ntpay/" + token;
    }

    public Settings settings() {
        return jdbc.sql("""
                select enabled, test_mode, signature_key is not null as has_signature, webhook_token, checked_at, check_result,
                       updated_by, updated_at from company_ntpay""")
                .query((rs, i) -> new Settings(true, rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3),
                        notificationUrl(rs.getString(4)), rs.getObject(5, OffsetDateTime.class), rs.getString(6),
                        rs.getString(7), rs.getObject(8, OffsetDateTime.class)))
                .optional().orElse(new Settings(false, false, true, false, null, null, null, null, null));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    public Settings save(SettingsInput in, String by) {
        boolean exists = jdbc.sql("select count(*) from company_ntpay").query(Integer.class).single() > 0;
        if (!exists && blank(in.apiKey())) {
            throw new BusinessRuleException("Enter the API key of your NTPay integration.");
        }
        if (!blank(in.apiKey()) && !in.apiKey().trim().matches("[\\x21-\\x7E]{8,300}")) {
            throw new BusinessRuleException("This does not look like an NTPay API key.");
        }
        if (!blank(in.signatureKey()) && !in.signatureKey().trim().matches("[\\x21-\\x7E]{8,300}")) {
            throw new BusinessRuleException("This does not look like an NTPay signature key.");
        }
        boolean enabled = Boolean.TRUE.equals(in.enabled());
        boolean testMode = !Boolean.FALSE.equals(in.testMode());
        tx.execute(s -> {
            if (!exists) {
                byte[] token = new byte[24];
                random.nextBytes(token);
                jdbc.sql("""
                        insert into company_ntpay (enabled, test_mode, api_key, signature_key, webhook_token, updated_by)
                        values (?, ?, ?, ?, ?, ?)""")
                        .params(enabled, testMode, crypto.encrypt(in.apiKey().trim()),
                                blank(in.signatureKey()) ? null : crypto.encrypt(in.signatureKey().trim()),
                                HexFormat.of().formatHex(token), by).update();
            } else {
                jdbc.sql("""
                        update company_ntpay set enabled = ?, test_mode = ?, updated_by = ?, updated_at = now(),
                            api_key = coalesce(?, api_key), signature_key = coalesce(?, signature_key),
                            checked_at = case when ? then null else checked_at end""")
                        .params(enabled, testMode, by, blank(in.apiKey()) ? null : crypto.encrypt(in.apiKey().trim()),
                                blank(in.signatureKey()) ? null : crypto.encrypt(in.signatureKey().trim()),
                                !blank(in.apiKey())).update();
            }
            return null;
        });
        audit.record("ntpay.settings", "company_ntpay", null, Map.of("enabled", enabled, "testMode", testMode,
                "apiKeyChanged", !blank(in.apiKey()), "signatureKeyChanged", !blank(in.signatureKey())));
        return settings();
    }

    /**
     * Checks the key and whether payment batches are open to it. NTPay has no balance call: asking for a batch that
     * does not exist answers 404 when all is well, 401 for a wrong key, 403 when batches are not enabled.
     */
    public Settings test() {
        Config c = config().orElseThrow(() -> new BusinessRuleException("Enter the API key of your NTPay integration."));
        String result;
        try {
            Answer a = call(c, "GET", "/payment-batches/HRPAY-CHECK", null, null);
            result = switch (a.status()) {
                case 404, 200 -> Texts.t("Connection works: the key is accepted and payment batches are open.");
                case 401 -> Texts.t("NTPay refused the key: check that you copied the right one.");
                case 403 -> Texts.t("The key works but payment batches are not enabled for this integration: ask NTPay to enable them.");
                default -> Texts.t("NTPay answered %s: %s", a.status(), message(a));
            };
        } catch (RestClientException e) {
            result = Texts.t("NTPay could not be reached: %s", e.getMessage());
        }
        String r = cut(result, 300);
        jdbc.sql("update company_ntpay set checked_at = now(), check_result = ?").param(r).update();
        return settings();
    }

    // ---- calls --------------------------------------------------------------------------------------------------

    private Answer call(Config c, String method, String path, Object body, String idempotencyKey) {
        RestClient.RequestBodySpec spec = client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
                .header("Authorization", "Bearer " + c.apiKey())
                .header("Accept", "application/json");
        if (idempotencyKey != null) {
            spec = spec.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            spec = spec.header("Content-Type", "application/json").body(json.writeValueAsString(body));
        }
        return spec.exchange((req, res) -> {
            String raw = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode node;
            try {
                node = raw.isBlank() ? json.createObjectNode() : json.readTree(raw);
            } catch (RuntimeException e) {
                node = json.createObjectNode();
            }
            return new Answer(res.getStatusCode().value(), node, raw);
        });
    }

    private static String message(Answer a) {
        String m = a.body().path("message").asText("");
        return m.isBlank() ? cut(a.raw(), 200) : m;
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    // ---- sending a payment file -----------------------------------------------------------------------------------

    private List<FileLine> lines(long fileId) {
        return jdbc.sql("select id, user_id, beneficiary, account, amount from payment_line where batch_id = ? order by beneficiary, id")
                .param(fileId).query(FileLine.class).list();
    }

    static String lineReference(long lineId) {
        return "HRPAY-L" + lineId;
    }

    /**
     * Sends a mobile money payment file as an NTPay batch, paid at once after approval or on {@code scheduledFor}.
     * The same attempt always carries the same idempotency key, so sending again after a lost answer never makes a
     * second batch.
     */
    public Payments.BatchView send(long fileId, OffsetDateTime scheduledFor, String by) {
        Config c = config().filter(Config::enabled)
                .orElseThrow(() -> new BusinessRuleException("Set up NTPay on the payment settings first."));
        String country = jdbc.sql("select country_code from company where id = current_company_id()").query(String.class)
                .optional().orElse("CD");
        if (!"CD".equals(country)) {
            throw new BusinessRuleException("NTPay pays to mobile money in the DR Congo only.");
        }
        Payments.BatchView view = payments.batch(fileId);
        Payments.Batch b = view.batch();
        if (!b.status().equals("PREPARED")) {
            throw new ConflictException("This payment file is " + b.status().toLowerCase(java.util.Locale.ROOT) + ".");
        }
        if (!b.method().equals("MOBILE_MONEY")) {
            throw new BusinessRuleException("Only a mobile money file can be sent with NTPay.");
        }
        if (!List.of("CDF", "USD").contains(b.currency())) {
            throw new BusinessRuleException("NTPay pays in CDF or USD only.");
        }
        if (b.ntpayLive() && !"UNKNOWN".equals(b.ntpayStatus())) {
            throw new ConflictException("This file is already with NTPay.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (scheduledFor != null && (scheduledFor.isBefore(now.plusMinutes(6)) || scheduledFor.isAfter(now.plusDays(60)))) {
            throw new BusinessRuleException("The payment time must be at least a few minutes ahead and within 60 days.");
        }
        List<FileLine> lines = lines(fileId);
        List<String> wrong = lines.stream().filter(l -> Payments.msisdn(l.account()) == null).map(FileLine::beneficiary).toList();
        if (!wrong.isEmpty()) {
            throw new BusinessRuleException(Texts.t("These numbers are not DR Congo mobile numbers: %s. Cancel the file, correct the employee files and make it again.",
                    String.join(", ", wrong)));
        }
        if (lines.isEmpty() || lines.size() > 1000) {
            throw new BusinessRuleException("An NTPay batch holds 1 to 1000 payments: split the file.");
        }
        int attempt = b.ntpayStatus() == null ? Math.max(1, attempt(fileId))
                : "UNKNOWN".equals(b.ntpayStatus()) ? attempt(fileId) : attempt(fileId) + 1;
        String reference = "HRPAY-" + c.companyId() + "-F" + fileId + "-" + attempt;
        String key = UUID.nameUUIDFromBytes(("ntpay|" + c.companyId() + "|" + fileId + "|" + attempt).getBytes(StandardCharsets.UTF_8)).toString();

        Map<String, Object> body = new LinkedHashMap<>();
        String kind = Payments.QUINZAINE.equals(view.kind()) ? "Quinzaine" : Payments.ADVANCE.equals(view.kind()) ? "Avances"
                : Payments.MISSION.equals(view.kind()) ? "Missions" : "Paie";
        body.put("name", cut(kind + " " + view.month() + " · " + b.provider() + " · HR Pay", 120));
        body.put("currency", b.currency());
        body.put("reference", reference);
        if (scheduledFor != null) {
            body.put("scheduled_for", scheduledFor.toString());
        }
        body.put("callback_url", notificationUrl(c.webhookToken()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (FileLine l : lines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("phone", Payments.msisdn(l.account()));
            m.put("amount", l.amount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
            m.put("beneficiary", cut(l.beneficiary(), 60));
            m.put("reference", lineReference(l.id()));
            out.add(m);
        }
        body.put("lines", out);

        tx.execute(s -> jdbc.sql("""
                update payment_batch set ntpay_attempt = ?, ntpay_reference = ?, ntpay_status = 'UNKNOWN', ntpay_finished = false,
                    ntpay_batch_code = null, ntpay_test = ?, ntpay_scheduled_for = ?, ntpay_sent_by = ?, ntpay_sent_at = now(),
                    ntpay_error = null where id = ?""")
                .params(attempt, reference, c.testMode(), scheduledFor, by, fileId).update());
        Answer a;
        try {
            a = call(c, "POST", "/payment-batches", body, key);
        } catch (RestClientException e) {
            error(fileId, "UNKNOWN", Texts.t("NTPay did not answer (%s). Send again: the same batch is asked for, never a second one.", cut(e.getMessage(), 200)));
            throw new BusinessRuleException("NTPay did not answer. Send the file again in a moment: it cannot be paid twice.");
        }
        if (a.status() == 201 || a.status() == 200) {
            JsonNode batch = a.body().path("batch");
            tx.execute(s -> {
                jdbc.sql("""
                        update payment_batch set ntpay_batch_code = ?, ntpay_status = ?, ntpay_scheduled_for = coalesce(?, ntpay_scheduled_for)
                        where id = ?""")
                        .params(batch.path("batch_code").asText(null), batch.path("status").asText("pending_approval"),
                                time(batch.path("scheduled_for")), fileId).update();
                jdbc.sql("update payment_line set ntpay_status = 'waiting', ntpay_payout_reference = null, ntpay_error = null where batch_id = ?")
                        .param(fileId).update();
                return null;
            });
            audit.record("payment.ntpay_sent", "payment_batch", fileId, Map.of("batchCode", batch.path("batch_code").asText(""),
                    "reference", reference, "lines", lines.size(), "total", b.total(), "test", c.testMode()));
            return payments.batch(fileId);
        }
        String why = switch (a.status()) {
            case 401 -> Texts.t("NTPay refused the API key.");
            case 403 -> Texts.t("Payment batches are not enabled for this NTPay integration: ask NTPay to enable them.");
            case 422 -> refusedLines(a, lines);
            default -> Texts.t("NTPay answered %s: %s", a.status(), message(a));
        };
        error(fileId, a.status() >= 500 ? "UNKNOWN" : "REFUSED", why);
        throw new BusinessRuleException(why);
    }

    private int attempt(long fileId) {
        return jdbc.sql("select ntpay_attempt from payment_batch where id = ?").param(fileId).query(Integer.class).single();
    }

    private void error(long fileId, String status, String why) {
        tx.execute(s -> jdbc.sql("update payment_batch set ntpay_status = ?, ntpay_error = ? where id = ?")
                .params(status, cut(why, 1000), fileId).update());
        audit.recordIndependently("payment.ntpay_refused", "payment_batch", fileId, Map.of("status", status, "why", cut(why, 300)));
    }

    /** NTPay refuses the whole batch for one bad line; the people to correct, by name. */
    private String refusedLines(Answer a, List<FileLine> lines) {
        List<String> who = new ArrayList<>();
        for (JsonNode e : a.body().path("errors")) {
            String phone = Payments.msisdn(e.path("phone").asText(""));
            String name = lines.stream().filter(l -> phone != null && phone.equals(Payments.msisdn(l.account())))
                    .map(FileLine::beneficiary).findFirst().orElse(e.path("phone").asText("?"));
            String err = e.path("error").asText("");
            who.add(err.isBlank() ? name : name + " (" + err + ")");
        }
        if (who.isEmpty()) {
            return Texts.t("NTPay refused the file: %s", message(a));
        }
        return Texts.t("NTPay refused the file because of: %s. Cancel the file, correct these employee files and make it again.",
                String.join("; ", who));
    }

    private static OffsetDateTime time(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode() || n.asText("").isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(n.asText());
        } catch (RuntimeException e) {
            try {
                return java.time.LocalDateTime.parse(n.asText().replace(' ', 'T')).atZone(ZoneId.of("Africa/Kinshasa")).toOffsetDateTime();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    // ---- following the batch ------------------------------------------------------------------------------------

    private record FileRow(long id, String code, String status, boolean finished) {}

    private Optional<FileRow> fileRow(long fileId) {
        return jdbc.sql("select id, ntpay_batch_code as code, ntpay_status as status, ntpay_finished as finished from payment_batch where id = ?")
                .param(fileId).query(FileRow.class).optional();
    }

    /** Asks NTPay where the file's batch stands, and applies it. */
    public Payments.BatchView refresh(long fileId) {
        FileRow f = fileRow(fileId).orElseThrow(() -> new NotFoundException("Payment file not found."));
        if (f.code() == null) {
            return payments.batch(fileId);
        }
        Config c = config().orElseThrow(() -> new BusinessRuleException("Set up NTPay on the payment settings first."));
        Answer a;
        try {
            a = call(c, "GET", "/payment-batches/" + f.code(), null, null);
        } catch (RestClientException e) {
            throw new BusinessRuleException("NTPay could not be reached: try again in a moment.");
        }
        jdbc.sql("update payment_batch set ntpay_checked_at = now() where id = ?").param(fileId).update();
        if (a.status() != 200) {
            throw new BusinessRuleException(Texts.t("NTPay answered %s: %s", a.status(), message(a)));
        }
        JsonNode batch = a.body().has("batch") ? a.body().path("batch") : a.body();
        JsonNode lines = batch.has("lines") ? batch.path("lines") : a.body().path("lines");
        apply(fileId, batch, lines);
        return payments.batch(fileId);
    }

    /** Applies a batch as NTPay reports it: line results, then, once finished, the file settled. */
    void apply(long fileId, JsonNode batch, JsonNode lines) {
        tx.execute(s -> {
            FileRow f = jdbc.sql("select id, ntpay_batch_code as code, ntpay_status as status, ntpay_finished as finished from payment_batch where id = ? for update")
                    .param(fileId).query(FileRow.class).single();
            if (f.finished()) {
                return null;
            }
            for (JsonNode l : lines) {
                Long lineId = lineId(l.path("reference").asText(l.path("order_id").asText("")));
                if (lineId != null) {
                    line(fileId, lineId, l.path("status").asText(null), l.path("payout_reference").asText(null),
                            firstText(l, "error", "failure_reason", "message"));
                }
            }
            String status = batch.path("status").asText(f.status());
            boolean finished = batch.path("finished").asBoolean(false);
            String reason = firstText(batch, "rejection_reason", "failure_reason");
            jdbc.sql("update payment_batch set ntpay_status = ?, ntpay_error = coalesce(?, ntpay_error) where id = ?")
                    .params(status, reason, fileId).update();
            if ("approved".equals(status) && finished) {
                finish(fileId, f.code());
            } else if (List.of("rejected", "failed", "cancelled").contains(status) && OPEN.contains(f.status() == null ? "" : f.status())) {
                stopped(fileId, status, reason);
            }
            return null;
        });
    }

    private static String firstText(JsonNode n, String... names) {
        for (String name : names) {
            String v = n.path(name).asText("");
            if (!v.isBlank() && !"null".equals(v)) {
                return cut(v, 300);
            }
        }
        return null;
    }

    static Long lineId(String reference) {
        if (reference == null || !reference.matches("HRPAY-L\\d{1,18}")) {
            return null;
        }
        return Long.parseLong(reference.substring(7));
    }

    private void line(long fileId, long lineId, String status, String payoutReference, String error) {
        if (status == null || !List.of("waiting", "pending", "paid", "failed").contains(status)) {
            return;
        }
        jdbc.sql("""
                update payment_line set ntpay_status = ?, ntpay_payout_reference = coalesce(?, ntpay_payout_reference),
                    ntpay_error = case when ? = 'failed' then coalesce(?, ntpay_error) else null end
                where id = ? and batch_id = ? and coalesce(ntpay_status, '') not in ('paid')""")
                .params(status, payoutReference, status, error, lineId, fileId).update();
    }

    /** The batch is over: paid lines settle the file, failed lines leave it and their payslips can be paid again. */
    private void finish(long fileId, String code) {
        List<FileLine> failed = jdbc.sql("""
                select id, user_id, beneficiary, account, amount from payment_line
                where batch_id = ? and coalesce(ntpay_status, '') <> 'paid'""").param(fileId).query(FileLine.class).list();
        List<FileLine> paid = jdbc.sql("""
                select id, user_id, beneficiary, account, amount from payment_line
                where batch_id = ? and ntpay_status = 'paid'""").param(fileId).query(FileLine.class).list();
        Map<Long, String> errors = new LinkedHashMap<>();
        jdbc.sql("select id, ntpay_error from payment_line where batch_id = ? and coalesce(ntpay_status, '') <> 'paid'").param(fileId)
                .query((rs, i) -> errors.put(rs.getLong(1), rs.getString(2))).list();
        jdbc.sql("update payment_batch set ntpay_finished = true where id = ?").param(fileId).update();
        if (!failed.isEmpty()) {
            jdbc.sql("delete from payment_line where batch_id = ? and coalesce(ntpay_status, '') <> 'paid'").param(fileId).update();
        }
        BigDecimal total = paid.stream().map(FileLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        jdbc.sql("update payment_batch set line_count = ?, total = ? where id = ?").params(paid.size(), total, fileId).update();
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Africa/Kinshasa")));
        if (paid.isEmpty()) {
            jdbc.sql("""
                    update payment_batch set status = 'CANCELLED', cancelled_by = 'NTPay', cancelled_at = now(), cancel_reason = ?
                    where id = ?""").params(Texts.t("No payment of the NTPay batch %s went through.", code), fileId).update();
        } else {
            payments.settle(fileId, today, code, "NTPay");
        }
        String currency = jdbc.sql("select trim(currency) from payment_batch where id = ?").param(fileId).query(String.class).single();
        for (FileLine l : paid) {
            notifications.send(l.userId(), "payment.sent", () -> Texts.t("Your pay was sent to your mobile money"),
                    () -> Texts.t("%s %s sent to %s.", l.amount().toPlainString(), currency, mask(l.account())), "/my/payslips");
        }
        for (FileLine l : failed) {
            notifications.send(l.userId(), "payment.failed", () -> Texts.t("Your pay could not be sent to your mobile money"),
                    () -> Texts.t("The payment to %s did not go through. Check your number with HR; your pay will be sent again.", mask(l.account())),
                    "/my/payslips");
        }
        int failedCount = failed.size();
        int paidCount = paid.size();
        notifications.sendToHolders("payroll.pay", "payment.ntpay", () -> Texts.t("NTPay batch %s finished", code),
                () -> failedCount == 0 ? Texts.t("%s payments went through.", paidCount)
                        : Texts.t("%s payments went through, %s failed: correct the numbers and pay them in a new file.", paidCount, failedCount),
                "/payroll/payments/files/" + fileId);
        audit.recordAs("NTPay", "payment.ntpay_finished", "payment_batch", fileId, Map.of("batchCode", code, "paid", paidCount,
                "failed", failedCount, "failedLines", failed.stream().map(l -> l.beneficiary() + ": " + (errors.get(l.id()) == null ? "" : errors.get(l.id()))).toList()));
    }

    private void stopped(long fileId, String status, String reason) {
        jdbc.sql("update payment_line set ntpay_status = null, ntpay_payout_reference = null, ntpay_error = null where batch_id = ?")
                .param(fileId).update();
        notifications.sendToHolders("payroll.pay", "payment.ntpay",
                () -> "rejected".equals(status) ? Texts.t("NTPay batch rejected") : Texts.t("NTPay batch not paid"),
                () -> reason == null ? Texts.t("Nothing was paid. Send the file again or pay it another way.")
                        : Texts.t("Nothing was paid: %s", reason), "/payroll/payments/files/" + fileId);
        audit.recordAs("NTPay", "payment.ntpay_" + status, "payment_batch", fileId, Map.of("reason", reason == null ? "" : reason));
    }

    static String mask(String account) {
        String d = account == null ? "" : account.replaceAll("\\D", "");
        return d.length() < 4 ? "…" : "…" + d.substring(d.length() - 4);
    }

    /** Cancels the file's batch while NTPay has not paid it (waiting for approval, scheduled, or failed). */
    public Payments.BatchView cancel(long fileId, String by) {
        FileRow f = fileRow(fileId).orElseThrow(() -> new NotFoundException("Payment file not found."));
        if (f.code() == null || f.finished() || !List.of("pending_approval", "scheduled", "failed").contains(f.status())) {
            if ("UNKNOWN".equals(f.status()) && f.code() == null) {
                // The answer was lost and nothing is known of a batch: the file can be paid another way.
                tx.execute(s -> jdbc.sql("update payment_batch set ntpay_status = 'REFUSED', ntpay_error = ? where id = ?")
                        .params(Texts.t("Sending stopped by %s.", by), fileId).update());
                return payments.batch(fileId);
            }
            throw new ConflictException("This NTPay batch cannot be cancelled any more.");
        }
        Config c = config().orElseThrow(() -> new BusinessRuleException("Set up NTPay on the payment settings first."));
        Answer a;
        try {
            a = call(c, "POST", "/payment-batches/" + f.code() + "/cancel", Map.of(), null);
        } catch (RestClientException e) {
            throw new BusinessRuleException("NTPay could not be reached: try again in a moment.");
        }
        if (a.status() == 409) {
            refresh(fileId);
            throw new ConflictException("NTPay has already paid this batch: it can no longer be cancelled.");
        }
        if (a.status() >= 300) {
            throw new BusinessRuleException(Texts.t("NTPay answered %s: %s", a.status(), message(a)));
        }
        tx.execute(s -> {
            jdbc.sql("update payment_batch set ntpay_status = 'cancelled', ntpay_error = null where id = ?").param(fileId).update();
            jdbc.sql("update payment_line set ntpay_status = null where batch_id = ?").param(fileId).update();
            return null;
        });
        audit.record("payment.ntpay_cancelled", "payment_batch", fileId, Map.of("batchCode", f.code()));
        return payments.batch(fileId);
    }

    // ---- NTPay's notifications ----------------------------------------------------------------------------------

    /** HMAC-SHA256 of {@code timestamp + "." + body}, hex, against each {@code v1=} value; at most 5 minutes old. */
    static boolean signed(String key, byte[] body, String timestamp, String signature, long nowSeconds) {
        if (key == null || timestamp == null || signature == null || !timestamp.matches("\\d{1,12}")) {
            return false;
        }
        if (Math.abs(nowSeconds - Long.parseLong(timestamp)) > 300) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(body)).getBytes(StandardCharsets.UTF_8);
            for (String part : signature.split(",")) {
                String p = part.trim();
                if (p.startsWith("v1=") && MessageDigest.isEqual(expected, p.substring(3).toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
                    return true;
                }
            }
        } catch (java.security.GeneralSecurityException e) {
            return false;
        }
        return false;
    }

    /** Handles one notification; the answer is the HTTP status for NTPay (2xx: received, do not send again). */
    public int notification(String token, byte[] body, String timestamp, String signature, String notificationId) {
        if (token == null || !token.matches("[0-9a-f]{48}")) {
            return 404;
        }
        record Owner(long companyId, String signatureKey) {}
        Owner owner = tenants.callAsPlatform(() -> jdbc.sql("select company_id, signature_key from company_ntpay where webhook_token = ?")
                .param(token).query((rs, i) -> new Owner(rs.getLong(1), rs.getString(2))).optional().orElse(null));
        if (owner == null) {
            return 404;
        }
        String key = owner.signatureKey() == null ? null : crypto.decrypt(owner.signatureKey());
        if (!signed(key, body, timestamp, signature, clock.instant().getEpochSecond())) {
            log.warn("NTPay notification for company {} with a missing or wrong signature: ignored", owner.companyId());
            return 401;
        }
        JsonNode n;
        try {
            n = json.readTree(body);
        } catch (RuntimeException e) {
            return 400;
        }
        tenants.runAs(owner.companyId(), () -> handle(n, notificationId));
        return 200;
    }

    private void handle(JsonNode n, String notificationId) {
        if (notificationId != null && !notificationId.isBlank()) {
            int fresh = tx.execute(s -> jdbc.sql("insert into ntpay_notification (notification_id) values (?) on conflict do nothing")
                    .param(cut(notificationId, 100)).update());
            if (fresh == 0) {
                return;
            }
        }
        String code = n.path("batch_code").asText("");
        if (code.isBlank()) {
            return;
        }
        Long fileId = jdbc.sql("select id from payment_batch where ntpay_batch_code = ? order by id desc limit 1").param(code)
                .query(Long.class).optional().orElse(null);
        if (fileId == null) {
            return;
        }
        if ("payment_batch".equals(n.path("type").asText())) {
            try {
                refresh(fileId);
            } catch (RuntimeException e) {
                // NTPay could not be asked: apply what the notification says; the next check completes it.
                apply(fileId, n, json.createArrayNode());
            }
        } else if ("mass".equals(n.path("kind").asText())) {
            Long lineId = lineId(n.path("order_id").asText(""));
            if (lineId != null) {
                String status = "success".equals(n.path("result").asText()) ? "paid" : "failed".equals(n.path("result").asText()) ? "failed" : null;
                tx.execute(s -> {
                    line(fileId, lineId, status, n.path("payout_reference").asText(null), cut(n.path("message").asText(null), 300));
                    return null;
                });
            }
        }
    }

    // ---- the regular check --------------------------------------------------------------------------------------

    /** Every few minutes: the batches NTPay may still pay, in case a notification was lost. */
    @Scheduled(cron = "${app.ntpay.cron:0 */10 * * * *}", zone = "Africa/Kinshasa")
    public void scheduled() {
        jobs.runForEachCompany("ntpay.check", Duration.ofMinutes(9), this::checkAll);
    }

    void checkAll() {
        if (TenantContext.companyId() == null) {
            return;
        }
        List<Long> open = jdbc.sql("""
                select id from payment_batch where ntpay_batch_code is not null and not ntpay_finished
                  and ntpay_status in ('pending_approval', 'scheduled', 'approved') and status = 'PREPARED'""")
                .query(Long.class).list();
        for (long id : open) {
            try {
                refresh(id);
            } catch (RuntimeException e) {
                log.warn("NTPay check of payment file {} failed: {}", id, e.getMessage());
            }
        }
    }
}
