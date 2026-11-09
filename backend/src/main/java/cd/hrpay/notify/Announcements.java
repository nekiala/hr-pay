package cd.hrpay.notify;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.AppProperties;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.ops.JobLocks;
import cd.hrpay.org.OrgStructure;
import cd.hrpay.tenancy.TenantContext;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HR announcements to everyone, a unit (with the units below it) or a site. Everyone addressed sees the announcement in
 * HR Pay. When HR asks for it, people who agreed to WhatsApp get a WhatsApp notice: a template in fixed words with a
 * link, so the text itself is read in HR Pay and Meta keeps the template as a utility one. Everyone else with an
 * email address gets the announcement by email, sent in the background.
 */
@Service
public class Announcements {

    public enum Audience { ALL, UNIT, SITE }

    public record Input(String title, String body, Audience audience, Long unitId, Long siteId, boolean whatsapp) {}

    /** What sending would do: how many people, by which way, and Meta's estimated cost in US dollars. */
    public record Preview(int recipients, int byWhatsapp, int byEmail, int inAppOnly, boolean whatsappAvailable,
                          boolean templateApproved, BigDecimal estimatedCostUsd, BigDecimal pricePerMessageUsd) {}

    public record Summary(long id, String title, String audience, String audienceName, boolean whatsapp, int recipients,
                          int byWhatsapp, int byEmail, String createdBy, OffsetDateTime createdAt) {}

    public record Announcement(long id, String title, String body, String audience, String audienceName,
                               String createdBy, OffsetDateTime createdAt) {}

    private record Target(long id, String email, String language, boolean optedIn) {}

    private final JdbcClient jdbc;
    private final OrgStructure org;
    private final WhatsApp whatsapp;
    private final Notifications notifications;
    private final NotificationService messages;
    private final UserRepository users;
    private final AuditService audit;
    private final JobLocks locks;
    private final AppProperties properties;
    private final BigDecimal price;

    public Announcements(JdbcClient jdbc, OrgStructure org, WhatsApp whatsapp, Notifications notifications,
                         NotificationService messages, UserRepository users, AuditService audit, JobLocks locks,
                         AppProperties properties,
                         @Value("${app.whatsapp.utility-price-usd:0.004}") BigDecimal price) {
        this.jdbc = jdbc;
        this.org = org;
        this.whatsapp = whatsapp;
        this.notifications = notifications;
        this.messages = messages;
        this.users = users;
        this.audit = audit;
        this.locks = locks;
        this.properties = properties;
        this.price = price;
    }

    // ---- reading ----------------------------------------------------------------------------------------------------

    /** Every announcement, newest first (HR's list). */
    public List<Summary> all() {
        return jdbc.sql("""
                select a.id, a.title, a.audience, coalesce(u.name, s.name) as audience_name, a.whatsapp, a.recipients,
                       a.by_whatsapp, a.by_email, a.created_by, a.created_at
                from announcement a left join org_unit u on u.id = a.unit_id left join site s on s.id = a.site_id
                order by a.id desc limit 200""").query(Summary.class).list();
    }

    /** The announcements addressed to someone, newest first. */
    public List<Announcement> mine(long userId) {
        return jdbc.sql(SELECT + " join announcement_recipient r on r.announcement_id = a.id where r.user_id = ? order by a.id desc limit 100")
                .param(userId).query(Announcement.class).list();
    }

    /** One announcement, for someone it was addressed to or for HR. */
    public Announcement one(long id, long userId, boolean hr) {
        Announcement a = jdbc.sql(SELECT + " where a.id = ?").param(id).query(Announcement.class).optional()
                .orElseThrow(() -> new NotFoundException("Announcement not found"));
        if (!hr && jdbc.sql("select count(*) from announcement_recipient where announcement_id = ? and user_id = ?")
                .params(id, userId).query(Integer.class).single() == 0) {
            throw new NotFoundException("Announcement not found");
        }
        return a;
    }

    private static final String SELECT = """
            select a.id, a.title, a.body, a.audience, coalesce(u.name, s.name) as audience_name, a.created_by, a.created_at
            from announcement a left join org_unit u on u.id = a.unit_id left join site s on s.id = a.site_id""";

    // ---- sending ----------------------------------------------------------------------------------------------------

    public Preview preview(Input in) {
        return plan(in, targets(in)).preview();
    }

    private record Plan(Preview preview, Set<Long> onWhatsapp) {}

    private Plan plan(Input in, List<Target> targets) {
        long companyId = TenantContext.companyId();
        Optional<WhatsApp.Account> account = whatsapp.usable(companyId) ? whatsapp.resolve(companyId) : Optional.empty();
        String template = WhatsAppTemplates.forGroup(WhatsAppTemplates.Group.ANNOUNCEMENTS).name();
        boolean approved = account.isPresent() && (account.get().approved(template, "fr") || account.get().approved(template, "en"));
        Set<Long> onWhatsapp = new java.util.HashSet<>();
        int email = 0;
        int app = 0;
        for (Target t : targets) {
            String lang = "en".equals(t.language()) ? "en" : "fr";
            if (in.whatsapp() && t.optedIn() && account.isPresent() && account.get().approved(template, lang)) {
                onWhatsapp.add(t.id());
            } else if (t.email() != null && !t.email().isBlank()) {
                email++;
            } else {
                app++;
            }
        }
        BigDecimal cost = price.multiply(BigDecimal.valueOf(onWhatsapp.size())).setScale(2, RoundingMode.HALF_UP);
        return new Plan(new Preview(targets.size(), onWhatsapp.size(), email, app, account.isPresent(), approved, cost, price),
                onWhatsapp);
    }

    @Transactional
    public Summary send(Input in, String by) {
        String title = in.title() == null ? "" : in.title().trim();
        String body = in.body() == null ? "" : in.body().trim();
        if (title.isEmpty() || title.length() > 120) {
            throw new BusinessRuleException("Give the announcement a title of at most 120 characters.");
        }
        if (body.isEmpty() || body.length() > 5000) {
            throw new BusinessRuleException("Write the announcement, at most 5,000 characters.");
        }
        List<Target> targets = targets(in);
        if (targets.isEmpty()) {
            throw new BusinessRuleException("Nobody is in this audience.");
        }
        Plan plan = plan(in, targets);
        long id = jdbc.sql("""
                insert into announcement (title, body, audience, unit_id, site_id, whatsapp, recipients, by_whatsapp, by_email, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(title, body, in.audience().name(), in.audience() == Audience.UNIT ? in.unitId() : null,
                        in.audience() == Audience.SITE ? in.siteId() : null, in.whatsapp(), targets.size(),
                        plan.preview().byWhatsapp(), plan.preview().byEmail(), by)
                .query(Long.class).single();
        String link = "/announcements/" + id;
        String excerpt = body.length() > 300 ? body.substring(0, 299) + "…" : body;
        for (Target t : targets) {
            String channel = plan.onWhatsapp().contains(t.id()) ? "WHATSAPP"
                    : t.email() != null && !t.email().isBlank() ? "EMAIL" : "APP";
            jdbc.sql("insert into announcement_recipient (announcement_id, user_id, channel) values (?, ?, ?)")
                    .params(id, t.id(), channel).update();
            // The bell; for the WhatsApp ones, the WhatsApp sender picks this notification up.
            notifications.send(t.id(), channel.equals("WHATSAPP") ? WhatsAppTemplates.ANNOUNCEMENT_KIND : "announcement",
                    () -> title, () -> excerpt, link);
        }
        audit.record("announcement.sent", "announcement", id, Map.of("audience", in.audience().name(),
                "recipients", targets.size(), "whatsapp", plan.preview().byWhatsapp(), "email", plan.preview().byEmail()));
        return all().stream().filter(s -> s.id() == id).findFirst().orElseThrow();
    }

    private List<Target> targets(Input in) {
        if (in.audience() == null) {
            throw new BusinessRuleException("Choose who receives the announcement.");
        }
        String select = """
                select u.id, u.email, coalesce(u.language, c.language) as language,
                       (u.whatsapp_opt_in_at is not null and u.whatsapp_phone = u.phone_e164) as opted_in
                from app_user u join company c on c.id = u.company_id
                where u.status = 'ACTIVE'""";
        return switch (in.audience()) {
            case ALL -> jdbc.sql(select + " order by u.id").query(Target.class).list();
            case SITE -> {
                if (in.siteId() == null) {
                    throw new BusinessRuleException("Choose the site.");
                }
                yield jdbc.sql(select + " and u.site_id = ? order by u.id").param(in.siteId()).query(Target.class).list();
            }
            case UNIT -> {
                if (in.unitId() == null) {
                    throw new BusinessRuleException("Choose the unit.");
                }
                org.unit(in.unitId());
                Set<Long> branch = org.branch(in.unitId());
                Set<Long> people = new java.util.HashSet<>();
                org.unitsOn(org.today()).forEach((user, unit) -> {
                    if (branch.contains(unit)) {
                        people.add(user);
                    }
                });
                yield jdbc.sql(select + " order by u.id").query(Target.class).list().stream()
                        .filter(t -> people.contains(t.id())).toList();
            }
        };
    }

    // ---- emails, in the background ----------------------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${app.announcements.every:PT1M}", initialDelayString = "PT50S")
    public void scheduled() {
        locks.runForEachCompany("announcements.email", Duration.ofMinutes(10), this::sendEmails);
    }

    /** Emails the announcements still waiting, at most 200 per run. */
    public int sendEmails() {
        record Due(long announcementId, long userId, String title, String body) {}
        List<Due> due = jdbc.sql("""
                select r.announcement_id, r.user_id, a.title, a.body from announcement_recipient r
                join announcement a on a.id = r.announcement_id
                where r.channel = 'EMAIL' and r.emailed_at is null order by r.announcement_id, r.user_id limit 200""")
                .query(Due.class).list();
        String base = properties.publicUrl() == null ? "" : properties.publicUrl().replaceAll("/+$", "");
        int sent = 0;
        for (Due d : due) {
            jdbc.sql("update announcement_recipient set emailed_at = now() where announcement_id = ? and user_id = ?")
                    .params(d.announcementId(), d.userId()).update();
            AppUser user = users.findById(d.userId()).orElse(null);
            if (user == null || !user.isActive()) {
                continue;
            }
            messages.email(user, () -> d.title(), () -> d.body() + "\n\n"
                    + Texts.t("Read it in HR Pay: %s", base + "/announcements/" + d.announcementId()));
            sent++;
        }
        return sent;
    }
}
