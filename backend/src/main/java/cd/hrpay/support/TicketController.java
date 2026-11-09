package cd.hrpay.support;

import cd.hrpay.security.CurrentUser;
import cd.hrpay.tenancy.TenantContext;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** "Report a problem" for everyone signed in, and the provider's support desk. */
@RestController
public class TicketController {

    public record TicketInput(@NotBlank @Size(max = 150) String subject,
                              @NotBlank @Pattern(regexp = "PROBLEM|QUESTION|IDEA") String category,
                              @NotBlank @Size(max = 5000) String body, @Size(max = 300) String page,
                              @Size(max = 40) String appVersion, @Size(max = 300) String userAgent) {}

    public record MessageInput(@NotBlank @Size(max = 5000) String body) {}

    private final SupportDesk desk;
    private final UserService users;

    public TicketController(SupportDesk desk, UserService users) {
        this.desk = desk;
        this.users = users;
    }

    /** A company administrator follows every request of the company; anyone else their own. */
    private static boolean seesAll() {
        return CurrentUser.has("role.assign");
    }

    private AppUser me() {
        return users.find(CurrentUser.id());
    }

    @PostMapping("/api/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportDesk.Ticket open(@Valid @RequestBody TicketInput in) {
        return desk.open(me(), new SupportDesk.NewTicket(in.subject(), in.category(), in.body(), in.page(), in.appVersion(),
                in.userAgent()));
    }

    @GetMapping("/api/tickets")
    public List<SupportDesk.Ticket> mine() {
        return desk.mine(CurrentUser.id(), seesAll());
    }

    @GetMapping("/api/tickets/{id}")
    public SupportDesk.Thread thread(@PathVariable long id) {
        return desk.thread(id, CurrentUser.id(), seesAll());
    }

    @PostMapping("/api/tickets/{id}/messages")
    public SupportDesk.Thread reply(@PathVariable long id, @Valid @RequestBody MessageInput in) {
        return desk.reply(id, me(), seesAll(), in.body());
    }

    @PostMapping("/api/tickets/{id}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@PathVariable long id) {
        desk.closeByCompany(id, me(), seesAll());
    }

    // ---- provider ----

    @GetMapping("/api/platform/tickets")
    @PreAuthorize("hasAuthority('platform.manage')")
    public List<SupportDesk.Ticket> all(@RequestParam(required = false) String status) {
        requireProvider();
        return desk.all(status == null || status.isBlank() ? null : status);
    }

    @GetMapping("/api/platform/tickets/{id}")
    @PreAuthorize("hasAuthority('platform.manage')")
    public SupportDesk.Thread providerThread(@PathVariable long id) {
        requireProvider();
        return desk.providerThread(id);
    }

    @PostMapping("/api/platform/tickets/{id}/messages")
    @PreAuthorize("hasAuthority('platform.manage')")
    public SupportDesk.Thread answer(@PathVariable long id, @Valid @RequestBody MessageInput in) {
        requireProvider();
        AppUser me = me();
        return desk.answer(id, me.getEmail(), me.fullName(), in.body());
    }

    @PostMapping("/api/platform/tickets/{id}/close")
    @PreAuthorize("hasAuthority('platform.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void providerClose(@PathVariable long id) {
        requireProvider();
        desk.closeByProvider(id, CurrentUser.email());
    }

    private static void requireProvider() {
        if (!TenantContext.isProvider(TenantContext.companyId())) {
            throw new AccessDeniedException("Only the provider manages companies.");
        }
    }
}
