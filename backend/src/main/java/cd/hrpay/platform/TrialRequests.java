package cd.hrpay.platform;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.NotificationService;
import cd.hrpay.notify.Notifications;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.security.PasswordResets;
import cd.hrpay.tenancy.TenantContext;
import cd.hrpay.tenancy.Tenants;
import cd.hrpay.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trial requests from the website. Anyone may ask; nothing is created until the provider approves, which creates the
 * company with its trial and emails its administrator a link to choose a password.
 */
@RestController
public class TrialRequests {

    public record Ask(@NotBlank @Size(max = 150) String companyName, @NotBlank @Pattern(regexp = "CD|CG") String countryCode,
                      @NotBlank @Pattern(regexp = "1-50|51-300|301-1000|1000\\+") String employees,
                      @NotBlank @Size(max = 100) String firstName, @NotBlank @Size(max = 100) String lastName,
                      @NotBlank @Email @Size(max = 320) String email, @NotBlank @Size(max = 40) String phone,
                      @Size(max = 1000) String message, @Pattern(regexp = "fr|en") String language,
                      /** Left empty by people; filled by robots that fill every field. */ String website) {}

    public record Request(long id, String companyName, String countryCode, String employees, String firstName,
                          String lastName, String email, String phone, String message, String language, String status,
                          Long companyId, String decidedBy, OffsetDateTime decidedAt, OffsetDateTime createdAt) {}

    public record Approve(@NotBlank String planCode, @NotEmpty Set<String> modules) {}

    private final JdbcClient jdbc;
    private final Tenants tenants;
    private final PlatformController platform;
    private final PasswordResets resets;
    private final UserRepository users;
    private final Notifications notifications;
    private final NotificationService messages;
    private final AuditService audit;

    public TrialRequests(JdbcClient jdbc, Tenants tenants, PlatformController platform, PasswordResets resets,
                         UserRepository users, Notifications notifications, NotificationService messages, AuditService audit) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.platform = platform;
        this.resets = resets;
        this.users = users;
        this.notifications = notifications;
        this.messages = messages;
        this.audit = audit;
    }

    /** Always the same answer, so the form tells nothing about who already asked. */
    @PostMapping("/api/trials")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ask(@Valid @RequestBody Ask in, HttpServletRequest request) {
        if (in.website() != null && !in.website().isBlank()) {
            return;
        }
        String ip = request.getRemoteAddr();
        String email = in.email().trim().toLowerCase();
        Long id = tenants.callAsPlatform(() -> {
            int fromIp = jdbc.sql("select count(*) from trial_request where ip = ? and created_at > now() - interval '1 hour'")
                    .param(ip).query(Integer.class).single();
            int waiting = jdbc.sql("select count(*) from trial_request where lower(email) = ? and status = 'NEW'")
                    .param(email).query(Integer.class).single();
            if (fromIp >= 5 || waiting > 0) {
                return null;
            }
            return jdbc.sql("""
                    insert into trial_request (company_name, country_code, employees, first_name, last_name, email, phone,
                                               message, language, ip)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(in.companyName().trim(), in.countryCode(), in.employees(), in.firstName().trim(),
                            in.lastName().trim(), email, in.phone().trim(), in.message() == null ? null : in.message().trim(),
                            in.language() == null ? "fr" : in.language(), ip)
                    .query(Long.class).single();
        });
        if (id == null) {
            return;
        }
        tenants.runAs(TenantContext.providerCompany(), () -> {
            audit.recordAs(email, "trial.requested", "trial_request", id, Map.of("company", in.companyName().trim()));
            for (Long holder : notifications.holders("platform.manage")) {
                notifications.send(holder, "trial.requested", () -> Texts.t("Trial requested by %s", in.companyName().trim()),
                        () -> in.firstName() + " " + in.lastName() + " · " + email + " · " + in.employees(), "/platform/trials");
                users.findById(holder).ifPresent(u -> messages.email(u,
                        () -> Texts.t("Trial requested by %s", in.companyName().trim()),
                        () -> in.companyName().trim() + " (" + in.countryCode() + ", " + in.employees() + ")\n"
                                + in.firstName() + " " + in.lastName() + " <" + email + "> " + in.phone() + "\n\n"
                                + (in.message() == null ? "" : in.message())));
            }
        });
    }

    @GetMapping("/api/platform/trials")
    @PreAuthorize("hasAuthority('platform.manage')")
    public List<Request> list() {
        requireProvider();
        return tenants.callAsPlatform(() -> jdbc.sql("""
                select id, company_name, country_code, employees, first_name, last_name, email, phone, message, language,
                       status, company_id, decided_by, decided_at, created_at
                from trial_request order by case status when 'NEW' then 0 else 1 end, created_at desc limit 300""")
                .query(Request.class).list());
    }

    @PostMapping("/api/platform/trials/{id}/approve")
    @PreAuthorize("hasAuthority('platform.manage')")
    public Request approve(@PathVariable long id, @Valid @RequestBody Approve body, HttpServletRequest request) {
        requireProvider();
        Request r = waiting(id);
        PlatformController.Created created = platform.create(new PlatformController.NewCompany(r.companyName(),
                r.countryCode(), body.planCode(), body.modules(),
                new PlatformController.AdminInput(r.firstName(), r.lastName(), r.email(), r.phone()), r.language()));
        long companyId = created.company().id();
        String by = CurrentUser.email();
        tenants.runAs(companyId, () -> users.findByEmailIgnoreCase(r.email())
                .ifPresent(admin -> resets.send(admin, by, request.getRemoteAddr(), PasswordResets.Reason.WELCOME)));
        tenants.callAsPlatform(() -> jdbc.sql("""
                update trial_request set status = 'APPROVED', company_id = ?, decided_by = ?, decided_at = now() where id = ?""")
                .params(companyId, by, id).update());
        audit.record("trial.approved", "trial_request", id, Map.of("company", companyId));
        return find(id);
    }

    @PostMapping("/api/platform/trials/{id}/decline")
    @PreAuthorize("hasAuthority('platform.manage')")
    public Request decline(@PathVariable long id) {
        requireProvider();
        waiting(id);
        tenants.callAsPlatform(() -> jdbc.sql("""
                update trial_request set status = 'DECLINED', decided_by = ?, decided_at = now() where id = ?""")
                .params(CurrentUser.email(), id).update());
        audit.record("trial.declined", "trial_request", id, null);
        return find(id);
    }

    private Request waiting(long id) {
        Request r = find(id);
        if (!"NEW".equals(r.status())) {
            throw new BusinessRuleException("This request was already answered.");
        }
        return r;
    }

    private Request find(long id) {
        return tenants.callAsPlatform(() -> jdbc.sql("""
                select id, company_name, country_code, employees, first_name, last_name, email, phone, message, language,
                       status, company_id, decided_by, decided_at, created_at from trial_request where id = ?""")
                .param(id).query(Request.class).optional()).orElseThrow(() -> new NotFoundException("Request not found."));
    }

    private static void requireProvider() {
        if (!TenantContext.isProvider(TenantContext.companyId())) {
            throw new AccessDeniedException("Only the provider manages companies.");
        }
    }
}
