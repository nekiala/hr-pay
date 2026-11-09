package cd.hrpay.notify;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Messages the app sent or tried to send. Admins read OTP codes here while testing without SMTP. */
@RestController
@RequestMapping("/api/outbox")
public class OutboxController {

    public record Message(long id, OffsetDateTime createdAt, String channel, String recipient, String subject,
                          String body, String status, String error, String via,
                          OffsetDateTime deliveredAt, OffsetDateTime readAt) {}

    public record Server(MailServers.Level inUse) {}

    private final JdbcClient jdbc;
    private final MailServers servers;

    public OutboxController(JdbcClient jdbc, MailServers servers) {
        this.jdbc = jdbc;
        this.servers = servers;
    }

    /** Which server sends this company's emails now; NONE means they are only written on this page. */
    @GetMapping("/server")
    @PreAuthorize("hasAuthority('audit.read')")
    public Server server() {
        return new Server(servers.resolve(cd.hrpay.tenancy.TenantContext.companyId(), false).level());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('audit.read')")
    public List<Message> latest() {
        return jdbc.sql("""
                select id, created_at, channel, recipient, subject, body, status, error, via, delivered_at, read_at
                from outbox_message order by id desc limit 100""")
                .query((rs, n) -> new Message(rs.getLong("id"), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("channel"), rs.getString("recipient"), rs.getString("subject"),
                        rs.getString("body"), rs.getString("status"), rs.getString("error"), rs.getString("via"),
                        rs.getObject("delivered_at", OffsetDateTime.class), rs.getObject("read_at", OffsetDateTime.class)))
                .list();
    }
}
