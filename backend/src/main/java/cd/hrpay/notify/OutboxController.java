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
                          String body, String status, String error) {}

    private final JdbcClient jdbc;

    public OutboxController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('audit.read')")
    public List<Message> latest() {
        return jdbc.sql("""
                select id, created_at, channel, recipient, subject, body, status, error
                from outbox_message order by id desc limit 100""")
                .query((rs, n) -> new Message(rs.getLong("id"), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("channel"), rs.getString("recipient"), rs.getString("subject"),
                        rs.getString("body"), rs.getString("status"), rs.getString("error")))
                .list();
    }
}
