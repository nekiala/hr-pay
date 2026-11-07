package cd.hrpay.notify;

import cd.hrpay.i18n.Texts;
import cd.hrpay.ops.JobLocks;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * What a person is told in the app: the bell at the top of every page, and the phone's notifications when they
 * turned them on. Each notification is written in the person's language when it is made; the push itself carries
 * nothing, and the phone fetches the text with the person's session (see {@link WebPush}).
 */
@Service
public class Notifications {

    private static final Logger log = LoggerFactory.getLogger(Notifications.class);

    public record Item(long id, String kind, String title, String body, String link, OffsetDateTime createdAt,
                       OffsetDateTime readAt) {}

    public record Inbox(int unread, List<Item> items) {}

    public record Subscription(long id, String userAgent, OffsetDateTime createdAt, OffsetDateTime lastOkAt) {}

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final NotificationService messages;
    private final WebPush push;
    private final JobLocks jobs;

    public Notifications(JdbcClient jdbc, UserRepository users, NotificationService messages, WebPush push, JobLocks jobs) {
        this.jdbc = jdbc;
        this.users = users;
        this.messages = messages;
        this.push = push;
        this.jobs = jobs;
    }

    /** Tells one person something; the texts are made in their language. Does nothing for a disabled account. */
    public void send(long userId, String kind, Supplier<String> title, Supplier<String> body, String link) {
        AppUser user = users.findById(userId).orElse(null);
        if (user == null || !user.isActive()) {
            return;
        }
        String language = messages.languageOf(user);
        String t = cut(Texts.in(language, title), 200);
        String b = body == null ? null : cut(Texts.in(language, body), 500);
        jdbc.sql("insert into notification (user_id, kind, title, body, link, pushed_at) values (?, ?, ?, ?, ?, ?)")
                .params(userId, kind, t, b, link, push.enabled() ? null : OffsetDateTime.now()).update();
    }

    /** Tells everyone who holds a permission, except the person who caused it. */
    public void sendToHolders(String permission, String kind, Supplier<String> title, Supplier<String> body, String link) {
        Long me = CurrentUser.id();
        for (Long id : holders(permission)) {
            if (!id.equals(me)) {
                send(id, kind, title, body, link);
            }
        }
    }

    public List<Long> holders(String permission) {
        return jdbc.sql("""
                select distinct u.id from app_user u join user_role ur on ur.user_id = u.id
                join role_permission rp on rp.role_id = ur.role_id
                where rp.permission_code = ? and u.status = 'ACTIVE'""").param(permission).query(Long.class).list();
    }

    // ---- the person's own inbox -------------------------------------------------------------------------------

    public Inbox inbox(long userId, int limit) {
        int unread = jdbc.sql("select count(*) from notification where user_id = ? and read_at is null").param(userId)
                .query(Integer.class).single();
        List<Item> items = jdbc.sql("""
                select id, kind, title, body, link, created_at, read_at from notification where user_id = ?
                order by created_at desc, id desc limit ?""").params(userId, Math.min(Math.max(limit, 1), 100))
                .query(Item.class).list();
        return new Inbox(unread, items);
    }

    public void markRead(long userId, Long id) {
        if (id == null) {
            jdbc.sql("update notification set read_at = now() where user_id = ? and read_at is null").param(userId).update();
        } else {
            jdbc.sql("update notification set read_at = now() where user_id = ? and id = ? and read_at is null")
                    .params(userId, id).update();
        }
    }

    // ---- phones -------------------------------------------------------------------------------------------------

    public void subscribe(long userId, String endpoint, String p256dh, String auth, String userAgent) {
        push.checkEndpoint(endpoint);
        // A phone that changes hands moves to its new owner.
        jdbc.sql("""
                insert into push_subscription (user_id, endpoint, p256dh, auth, user_agent) values (?, ?, ?, ?, ?)
                on conflict (endpoint) do update set user_id = excluded.user_id, p256dh = excluded.p256dh,
                    auth = excluded.auth, user_agent = excluded.user_agent, failures = 0""")
                .params(userId, endpoint, p256dh, auth, cut(userAgent, 300)).update();
    }

    public void unsubscribe(long userId, String endpoint) {
        jdbc.sql("delete from push_subscription where user_id = ? and endpoint = ?").params(userId, endpoint).update();
    }

    public List<Subscription> subscriptions(long userId) {
        return jdbc.sql("select id, user_agent, created_at, last_ok_at from push_subscription where user_id = ? order by created_at")
                .param(userId).query(Subscription.class).list();
    }

    @Scheduled(fixedDelayString = "${app.push.every:PT15S}", initialDelayString = "PT30S")
    public void scheduled() {
        if (push.enabled()) {
            jobs.runForEachCompany("push.send", Duration.ofMinutes(2), this::sendPushes);
        }
    }

    /** Wakes the phones of everyone with a new notification: one push per person, whatever the number. */
    public void sendPushes() {
        List<Long> people = jdbc.sql("""
                select distinct user_id from notification where pushed_at is null and created_at > now() - interval '1 day'""")
                .query(Long.class).list();
        for (long userId : people) {
            record Sub(long id, String endpoint) {}
            for (Sub s : jdbc.sql("select id, endpoint from push_subscription where user_id = ?").param(userId).query(Sub.class).list()) {
                WebPush.Result r = push.send(s.endpoint());
                switch (r) {
                    case SENT -> jdbc.sql("update push_subscription set last_ok_at = now(), failures = 0 where id = ?").param(s.id()).update();
                    case GONE -> jdbc.sql("delete from push_subscription where id = ?").param(s.id()).update();
                    case FAILED -> jdbc.sql("""
                            update push_subscription set failures = failures + 1 where id = ?""").param(s.id()).update();
                }
            }
            jdbc.sql("delete from push_subscription where user_id = ? and failures >= 20").param(userId).update();
        }
        int marked = jdbc.sql("update notification set pushed_at = now() where pushed_at is null").update();
        if (marked > 0) {
            log.debug("Pushed {} notifications to {} people", marked, people.size());
        }
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
