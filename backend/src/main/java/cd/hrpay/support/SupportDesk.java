package cd.hrpay.support;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.NotificationService;
import cd.hrpay.notify.Notifications;
import cd.hrpay.tenancy.TenantContext;
import cd.hrpay.tenancy.Tenants;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Requests from a company's people to the provider: a problem, a question or an idea, then the conversation that
 * follows. The company's rows stay its own; the provider reads them across companies from its console.
 */
@Service
public class SupportDesk {

    public record Ticket(long id, String number, long companyId, String companyName, long userId, String userName,
                         String userEmail, String subject, String category, String status, String page,
                         String appVersion, String userAgent, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                         int messages, boolean lastFromProvider) {}

    public record Message(long id, boolean fromProvider, String authorName, String body, OffsetDateTime createdAt) {}

    public record Thread(Ticket ticket, List<Message> messages) {}

    public record NewTicket(String subject, String category, String body, String page, String appVersion, String userAgent) {}

    static final String SELECT = """
            select t.id, t.number, t.company_id, c.name as company_name, t.user_id,
                   person_name(u.first_name, u.middle_name, u.last_name) as user_name, u.email as user_email, t.subject, t.category,
                   t.status, t.page, t.app_version, t.user_agent, t.created_at, t.updated_at,
                   (select count(*) from support_message m where m.ticket_id = t.id) as messages,
                   coalesce((select m.from_provider from support_message m where m.ticket_id = t.id order by m.id desc limit 1), false)
                     as last_from_provider
            from support_ticket t join company c on c.id = t.company_id join app_user u on u.id = t.user_id""";

    private final JdbcClient jdbc;
    private final Tenants tenants;
    private final TransactionTemplate tx;
    private final UserRepository users;
    private final Notifications notifications;
    private final NotificationService messages;
    private final AuditService audit;

    public SupportDesk(JdbcClient jdbc, Tenants tenants, TransactionTemplate tx, UserRepository users,
                       Notifications notifications, NotificationService messages, AuditService audit) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.tx = tx;
        this.users = users;
        this.notifications = notifications;
        this.messages = messages;
        this.audit = audit;
    }

    // ---- the company's side ----------------------------------------------------------------------------------------

    public Ticket open(AppUser user, NewTicket in) {
        if (!List.of("PROBLEM", "QUESTION", "IDEA").contains(in.category())) {
            throw new BusinessRuleException("Choose a problem, a question or an idea.");
        }
        long id = tx.execute(s -> {
            long ticket = jdbc.sql("""
                    insert into support_ticket (number, user_id, subject, category, page, app_version, user_agent)
                    values ('-', ?, ?, ?, ?, ?, ?) returning id""")
                    .params(user.getId(), in.subject().trim(), in.category(), cut(in.page(), 300), cut(in.appVersion(), 40),
                            cut(in.userAgent(), 300))
                    .query(Long.class).single();
            jdbc.sql("update support_ticket set number = ? where id = ?").params("S-%06d".formatted(ticket), ticket).update();
            addMessage(ticket, false, user.getEmail(), name(user), in.body());
            audit.record("support.ticket_opened", "support_ticket", ticket, Map.of("category", in.category()));
            return ticket;
        });
        Ticket t = find(id);
        tellProvider(t, () -> Texts.t("New support request"));
        return t;
    }

    /** The person's own requests; a company administrator sees the whole company's. */
    public List<Ticket> mine(long userId, boolean all) {
        return jdbc.sql(SELECT + (all ? "" : " where t.user_id = " + userId) + " order by t.updated_at desc limit 200")
                .query(Ticket.class).list();
    }

    public Thread thread(long id, long userId, boolean all) {
        Ticket t = find(id);
        if (!all && t.userId() != userId) {
            throw new NotFoundException("Request not found.");
        }
        return new Thread(t, messagesOf(id));
    }

    public Thread reply(long id, AppUser user, boolean all, String body) {
        Ticket t = thread(id, user.getId(), all).ticket();
        tx.executeWithoutResult(s -> {
            addMessage(id, false, user.getEmail(), name(user), body);
            jdbc.sql("update support_ticket set status = 'OPEN', closed_at = null, closed_by = null, updated_at = now() where id = ?")
                    .param(id).update();
        });
        tellProvider(t, () -> Texts.t("New message on a support request"));
        return thread(id, user.getId(), all);
    }

    public void closeByCompany(long id, AppUser user, boolean all) {
        thread(id, user.getId(), all);
        close(id, user.getEmail());
    }

    // ---- the provider's side ---------------------------------------------------------------------------------------

    public List<Ticket> all(String status) {
        return tenants.callAsPlatform(() -> jdbc.sql(SELECT + (status == null ? "" : " where t.status = :status")
                        + " order by case t.status when 'OPEN' then 0 when 'ANSWERED' then 1 else 2 end, t.updated_at desc limit 500")
                .paramSource(status == null ? Map.of() : Map.of("status", status)).query(Ticket.class).list());
    }

    public Thread providerThread(long id) {
        return tenants.callAsPlatform(() -> new Thread(find(id), messagesOf(id)));
    }

    public Thread answer(long id, String by, String byName, String body) {
        Ticket t = tenants.callAsPlatform(() -> find(id));
        tenants.callAs(t.companyId(), () -> tx.execute(s -> {
            addMessage(id, true, by, byName, body);
            jdbc.sql("update support_ticket set status = 'ANSWERED', updated_at = now() where id = ?").param(id).update();
            audit.recordAs(by, "support.ticket_answered", "support_ticket", id, null);
            notifications.send(t.userId(), "support.answered", () -> Texts.t("Support answered request %s", t.number()),
                    () -> t.subject(), "/support/" + id);
            users.findById(t.userId()).ifPresent(u -> messages.email(u,
                    () -> Texts.t("Support answered request %s", t.number()),
                    () -> Texts.t("Hello %s,\n\nSupport answered your request \"%s\":\n\n%s\n\nReply or close the request in HR Pay, on the Help page.",
                            u.getFirstName(), t.subject(), body.trim())));
            return null;
        }));
        return providerThread(id);
    }

    public void closeByProvider(long id, String by) {
        Ticket t = tenants.callAsPlatform(() -> find(id));
        tenants.runAs(t.companyId(), () -> close(id, by));
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    private void close(long id, String by) {
        jdbc.sql("update support_ticket set status = 'CLOSED', closed_at = now(), closed_by = ?, updated_at = now() where id = ?")
                .params(by, id).update();
        audit.recordAs(by, "support.ticket_closed", "support_ticket", id, null);
    }

    private Ticket find(long id) {
        return jdbc.sql(SELECT + " where t.id = ?").param(id).query(Ticket.class).optional()
                .orElseThrow(() -> new NotFoundException("Request not found."));
    }

    private List<Message> messagesOf(long id) {
        return jdbc.sql("select id, from_provider, author_name, body, created_at from support_message where ticket_id = ? order by id")
                .param(id).query(Message.class).list();
    }

    private void addMessage(long ticket, boolean fromProvider, String author, String authorName, String body) {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty() || text.length() > 5000) {
            throw new BusinessRuleException("Write a message of at most 5,000 characters.");
        }
        jdbc.sql("insert into support_message (ticket_id, from_provider, author, author_name, body) values (?, ?, ?, ?, ?)")
                .params(ticket, fromProvider, author, cut(authorName, 200), text).update();
    }

    /** The provider's administrators: a notification, and an email so a request is seen even outside the app. */
    private void tellProvider(Ticket t, java.util.function.Supplier<String> subject) {
        Long provider = TenantContext.providerCompany();
        tenants.runAs(provider, () -> tx.executeWithoutResult(s -> {
            for (Long id : notifications.holders("platform.manage")) {
                notifications.send(id, "support.request", () -> Texts.t("Support request %s from %s", t.number(), t.companyName()),
                        t::subject, "/platform/support/" + t.id());
                users.findById(id).ifPresent(u -> messages.email(u, () -> subject.get() + " " + t.number() + " · " + t.companyName(),
                        () -> t.subject() + "\n\n" + t.userName() + " <" + t.userEmail() + ">\n" + (t.page() == null ? "" : t.page())));
            }
        }));
    }

    private static String name(AppUser u) {
        return u.fullName();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
