package cd.hrpay.notify;

import cd.hrpay.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in person's notifications, and the phones that receive them. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    public record ReadInput(Long id) {}

    public record Keys(@Size(max = 200) String p256dh, @Size(max = 100) String auth) {}

    public record SubscriptionInput(@NotBlank @Size(max = 1000) String endpoint, @Valid Keys keys) {}

    public record EndpointInput(@NotBlank @Size(max = 1000) String endpoint) {}

    public record PushSettings(boolean enabled, String publicKey, List<Notifications.Subscription> phones) {}

    private final Notifications notifications;
    private final WebPush push;

    public NotificationController(Notifications notifications, WebPush push) {
        this.notifications = notifications;
        this.push = push;
    }

    @GetMapping
    public Notifications.Inbox inbox(@RequestParam(defaultValue = "20") int limit) {
        return notifications.inbox(CurrentUser.id(), limit);
    }

    @PostMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@RequestBody(required = false) ReadInput in) {
        notifications.markRead(CurrentUser.id(), in == null ? null : in.id());
    }

    @GetMapping("/push")
    public PushSettings push() {
        return new PushSettings(push.enabled(), push.publicKey(), notifications.subscriptions(CurrentUser.id()));
    }

    @PostMapping("/push")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void subscribe(@Valid @RequestBody SubscriptionInput in, HttpServletRequest request) {
        notifications.subscribe(CurrentUser.id(), in.endpoint(), in.keys() == null ? null : in.keys().p256dh(),
                in.keys() == null ? null : in.keys().auth(), request.getHeader("User-Agent"));
    }

    @DeleteMapping("/push")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@Valid @RequestBody EndpointInput in) {
        notifications.unsubscribe(CurrentUser.id(), in.endpoint());
    }
}
