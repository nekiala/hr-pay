package cd.hrpay.notify;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.ops.JobLocks;
import cd.hrpay.tenancy.TenantContext;
import cd.hrpay.tenancy.Tenants;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An employee's agreement to receive HR Pay messages on WhatsApp, and the messages themselves. The number is first
 * confirmed with a code sent by WhatsApp only; the agreement ends when the person stops (in the app or by replying
 * STOP) or when their number changes. Each agreement and withdrawal is kept.
 */
@Service
public class WhatsAppConsent {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppConsent.class);
    public static final String OTP_PURPOSE = "WA_VERIFY";
    static final Set<String> STOP_WORDS = Set.of("STOP", "ARRET", "ARRÊT", "STOPP", "UNSUBSCRIBE", "DESABONNER", "DÉSABONNER");

    public record History(String action, String phone, String source, OffsetDateTime createdAt) {}

    /** What the person's WhatsApp page shows. */
    public record Mine(boolean offered, String phone, boolean optedIn, OffsetDateTime since, String number,
                       List<History> history) {}

    public record CodeSent(UUID challengeId, OffsetDateTime expiresAt) {}

    private final JdbcClient jdbc;
    private final WhatsApp whatsapp;
    private final OtpService otp;
    private final UserRepository users;
    private final AuditService audit;
    private final Tenants tenants;
    private final JobLocks locks;
    private final NotificationService messages;

    public WhatsAppConsent(JdbcClient jdbc, WhatsApp whatsapp, OtpService otp, UserRepository users, AuditService audit,
                           Tenants tenants, JobLocks locks, NotificationService messages) {
        this.jdbc = jdbc;
        this.whatsapp = whatsapp;
        this.otp = otp;
        this.users = users;
        this.audit = audit;
        this.tenants = tenants;
        this.locks = locks;
        this.messages = messages;
    }

    // ---- the person's choice ----------------------------------------------------------------------------------------

    public Mine mine(AppUser user) {
        long companyId = TenantContext.companyId();
        boolean offered = !whatsapp.enabledKinds(companyId).isEmpty() && whatsapp.resolve(companyId).isPresent();
        record State(String phone, String whatsappPhone, OffsetDateTime optIn) {}
        State s = jdbc.sql("select phone_e164 as phone, whatsapp_phone, whatsapp_opt_in_at as opt_in from app_user where id = ?")
                .param(user.getId()).query(State.class).single();
        boolean in = s.optIn() != null && s.phone().equals(s.whatsappPhone());
        String number = whatsapp.company().inUseNumber();
        return new Mine(offered, s.phone(), in, in ? s.optIn() : null, number, jdbc.sql("""
                select action, phone, source, created_at from whatsapp_consent where user_id = ? order by created_at desc limit 10""")
                .param(user.getId()).query(History.class).list());
    }

    /** Sends a code by WhatsApp only: receiving it proves the number is the person's and reaches WhatsApp. */
    @Transactional
    public CodeSent sendCode(AppUser user) {
        long companyId = TenantContext.companyId();
        if (whatsapp.enabledKinds(companyId).isEmpty() || whatsapp.resolve(companyId).isEmpty()) {
            throw new BusinessRuleException("Your company does not send messages on WhatsApp.");
        }
        OtpService.Challenge c = otp.issue(user, OTP_PURPOSE, "{}");
        if (c.deliveries().stream().noneMatch(d -> d.channel() == NotificationService.Channel.WHATSAPP && d.status().equals("SENT"))) {
            throw new BusinessRuleException(Texts.t("The code could not be sent on WhatsApp to %s. Check the number in your profile.",
                    user.getPhone()));
        }
        return new CodeSent(c.id(), c.expiresAt());
    }

    /** The person types the code: their number is confirmed and they agree to receive messages. */
    @Transactional
    public Mine confirm(AppUser user, UUID challengeId, String code) {
        otp.verify(challengeId, user, OTP_PURPOSE, code);
        jdbc.sql("update app_user set whatsapp_phone = phone_e164, whatsapp_opt_in_at = now() where id = ?").param(user.getId()).update();
        log(user.getId(), "IN", user.getPhone(), "APP");
        audit.recordAs(user.getEmail(), "whatsapp.opted_in", "user", user.getId(), Map.of("phone", user.getPhone()));
        return mine(user);
    }

    @Transactional
    public Mine stop(AppUser user) {
        optOut(user.getId(), "APP");
        return mine(user);
    }

    /** Ends the agreement; does nothing when there is none. */
    void optOut(long userId, String source) {
        record State(String phone, String whatsappPhone) {}
        State s = jdbc.sql("""
                update app_user set whatsapp_opt_in_at = null where id = ? and whatsapp_opt_in_at is not null
                returning phone_e164 as phone, whatsapp_phone""").param(userId).query(State.class).optional().orElse(null);
        if (s != null) {
            log(userId, "OUT", s.whatsappPhone() == null ? s.phone() : s.whatsappPhone(), source);
            audit.record("whatsapp.opted_out", "user", userId, Map.of("source", source));
        }
    }

    private void log(long userId, String action, String phone, String source) {
        jdbc.sql("insert into whatsapp_consent (user_id, action, phone, source) values (?, ?, ?, ?)")
                .params(userId, action, phone, source).update();
    }

    // ---- replies from employees ---------------------------------------------------------------------------------------

    /** A message someone sent to an HR Pay number. STOP (or ARRÊT) ends the agreement and gets a confirmation. */
    public void received(WhatsApp.Account account, String from, String text) {
        String phone = "+" + from.replaceAll("\\D", "");
        Long company = tenants.callAsPlatform(() -> jdbc.sql("select company_id from app_user where whatsapp_phone = ?")
                .param(phone).query(Long.class).optional().orElse(null));
        if (company == null || account.level() == WhatsApp.Level.COMPANY && !company.equals(account.companyId())) {
            return;
        }
        String word = text == null ? "" : text.trim().toUpperCase(Locale.ROOT).replaceAll("[^\\p{L}]", "");
        if (!STOP_WORDS.contains(word)) {
            return;
        }
        tenants.runAs(company, () -> {
            Long userId = jdbc.sql("select id from app_user where whatsapp_phone = ?").param(phone).query(Long.class).single();
            optOut(userId, "REPLY");
            AppUser user = users.findById(userId).orElse(null);
            String reply = Texts.in(user == null ? Texts.FR : messages.languageOf(user),
                    () -> Texts.t("You will no longer receive HR Pay messages on WhatsApp. You can turn them on again in HR Pay, under My account."));
            whatsapp.sendText(account, user, phone, reply);
        });
    }

    // ---- sending notifications ------------------------------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${app.whatsapp.every:PT30S}", initialDelayString = "PT40S")
    public void scheduled() {
        locks.runForEachCompany("whatsapp.send", Duration.ofMinutes(5), this::sendPending);
    }

    private record Pending(long id, long userId, String kind, String body, String link, String firstName,
                           String phone, String whatsappPhone, String language) {}

    /** Sends the new notifications of the company's people who agreed, then marks every new one as handled. */
    public int sendPending() {
        long companyId = TenantContext.companyId();
        List<String> kinds = whatsapp.enabledKinds(companyId);
        Optional<WhatsApp.Account> account = kinds.isEmpty() ? Optional.empty() : whatsapp.resolve(companyId);
        int sent = 0;
        // Notifications made while this runs wait for the next run.
        long upTo = jdbc.sql("select coalesce(max(id), 0) from notification").query(Long.class).single();
        if (account.isPresent()) {
            String companyLanguage = jdbc.sql("select language from company where id = ?").param(companyId)
                    .query(String.class).optional().orElse(Texts.FR);
            List<Pending> pending = jdbc.sql("""
                    select n.id, n.user_id, n.kind, n.body, n.link, u.first_name, u.phone_e164 as phone, u.whatsapp_phone,
                           coalesce(u.language, ?) as language
                    from notification n join app_user u on u.id = n.user_id
                    where n.whatsapp_at is null and n.id <= ? and n.created_at > now() - interval '1 day'
                      and u.whatsapp_opt_in_at is not null and u.status = 'ACTIVE'
                    order by n.id limit 200""").params(companyLanguage, upTo).query(Pending.class).list();
            if (pending.size() == 200) {
                upTo = pending.getLast().id();
            }
            for (Pending p : pending) {
                jdbc.sql("update notification set whatsapp_at = now() where id = ?").param(p.id()).update();
                if (!p.phone().equals(p.whatsappPhone())) {
                    // The number changed since it was confirmed: the agreement was for the old one.
                    optOut(p.userId(), "PHONE");
                    continue;
                }
                WhatsAppTemplates.Group group = WhatsAppTemplates.groupOf(p.kind());
                if (group == null || !kinds.contains(group.name())) {
                    continue;
                }
                WhatsAppTemplates.Template t = WhatsAppTemplates.forGroup(group);
                String language = "en".equals(p.language()) ? "en" : "fr";
                if (!account.get().approved(t.name(), language)) {
                    log.debug("Template {} ({}) is not approved by Meta yet", t.name(), language);
                    continue;
                }
                String body = WhatsAppTemplates.param(p.body());
                String link = p.link() == null ? "" : p.link().replaceFirst("^/+", "");
                String text = t.languages().get(language).body().replace("{{1}}", p.firstName()).replace("{{2}}", body);
                AppUser user = users.findById(p.userId()).orElse(null);
                if (whatsapp.sendTemplate(account.get(), user, p.phone(), t.name(), language,
                        List.of(WhatsAppTemplates.param(p.firstName()), body), link.isEmpty() ? "?from=whatsapp" : link, text).ok()) {
                    sent++;
                }
            }
        }
        jdbc.sql("update notification set whatsapp_at = now() where whatsapp_at is null and id <= ?").param(upTo).update();
        return sent;
    }
}
