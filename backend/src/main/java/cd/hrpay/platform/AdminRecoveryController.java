package cd.hrpay.platform;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.NotificationService;
import cd.hrpay.notify.Notifications;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.security.LoginAttempts;
import cd.hrpay.security.PasswordResets;
import cd.hrpay.security.TwoStep;
import cd.hrpay.security.UserSessions;
import cd.hrpay.tenancy.TenantContext;
import cd.hrpay.tenancy.Tenants;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The last resort when a company's administrators are all locked out: the provider sends one of them a link to choose
 * a new password, or sets a temporary one. Written in the company's own audit log, and its administrators are told.
 */
@RestController
@RequestMapping("/api/platform/companies/{companyId}/admins")
@PreAuthorize("hasAuthority('platform.manage')")
public class AdminRecoveryController {

    public record Admin(long id, String email, String firstName, String lastName, String status, boolean twoStep,
                        OffsetDateTime lastLoginAt) {}

    /** mode LINK emails a link; PASSWORD sets a temporary password, shown once, to be changed at sign-in. */
    public record Reset(@NotBlank @Pattern(regexp = "LINK|PASSWORD") String mode, boolean resetTwoStep,
                        @NotBlank String reason) {}

    public record Done(String mode, String email, String temporaryPassword, String delivery) {}

    private final JdbcClient jdbc;
    private final Tenants tenants;
    private final TransactionTemplate tx;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final PasswordResets resets;
    private final TwoStep twoStep;
    private final UserSessions sessions;
    private final LoginAttempts attempts;
    private final Notifications notifications;
    private final NotificationService messages;
    private final AuditService audit;

    public AdminRecoveryController(JdbcClient jdbc, Tenants tenants, TransactionTemplate tx, UserRepository users,
                                   PasswordEncoder encoder, PasswordResets resets, TwoStep twoStep, UserSessions sessions,
                                   LoginAttempts attempts, Notifications notifications, NotificationService messages,
                                   AuditService audit) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.tx = tx;
        this.users = users;
        this.encoder = encoder;
        this.resets = resets;
        this.twoStep = twoStep;
        this.sessions = sessions;
        this.attempts = attempts;
        this.notifications = notifications;
        this.messages = messages;
        this.audit = audit;
    }

    @GetMapping
    public List<Admin> admins(@PathVariable long companyId) {
        requireProvider();
        requireCompany(companyId);
        return tenants.callAs(companyId, () -> jdbc.sql("""
                select u.id, u.email, u.first_name, u.last_name, u.status,
                       exists (select 1 from user_two_step t where t.user_id = u.id and t.confirmed_at is not null) as two_step,
                       u.last_login_at
                from app_user u
                where exists (select 1 from user_role ur join role r on r.id = ur.role_id
                              where ur.user_id = u.id and r.code = 'ADMIN')
                order by u.status, u.last_name, u.first_name""").query(Admin.class).list());
    }

    @PostMapping("/{userId}/reset")
    public Done reset(@PathVariable long companyId, @PathVariable long userId, @Valid @RequestBody Reset body,
                      HttpServletRequest request) {
        requireProvider();
        requireCompany(companyId);
        String by = CurrentUser.email();
        String reason = body.reason().trim().length() > 300 ? body.reason().trim().substring(0, 300) : body.reason().trim();
        Done done = tenants.callAs(companyId, () -> tx.execute(s -> {
            AppUser user = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found."));
            if (!user.roleCodes().contains("ADMIN")) {
                throw new BusinessRuleException("Only a company administrator can be reset from here.");
            }
            if (!user.isActive()) {
                throw new BusinessRuleException("This account is disabled. Reactivate it first.");
            }
            String password = null;
            String delivery;
            if ("PASSWORD".equals(body.mode())) {
                password = PlatformController.temporaryPassword();
                user.setPasswordHash(encoder.encode(password));
                user.setMustChangePassword(true);
                users.save(user);
                sessions.endAll(user.getId(), null, by);
                attempts.succeeded(user.getEmail());
                delivery = messages.email(user, () -> Texts.t("Your HR Pay password was reset"),
                        () -> Texts.t("Hello %s,\n\nYour HR Pay provider's support reset your password, at your company's request. They will give you a temporary password; you choose your own at the next sign-in.\n\nIf nobody at your company asked for this, tell your HR department.", user.getFirstName())).status();
            } else {
                delivery = resets.send(user, by, request.getRemoteAddr(), true).status();
            }
            if (body.resetTwoStep()) {
                twoStep.remove(user, by);
            }
            audit.recordAs(by, "user.recovered_by_provider", "user", user.getId(), Map.of("mode", body.mode(),
                    "secondStepReset", body.resetTwoStep(), "reason", reason));
            String name = user.getFirstName() + " " + user.getLastName();
            notifications.sendToHolders("role.assign", "security", () -> Texts.t("Provider support reset an administrator's access"),
                    () -> Texts.t("%s: %s. Reason given: %s", name, "PASSWORD".equals(body.mode())
                            ? Texts.t("temporary password") : Texts.t("link to choose a new password"), reason),
                    "/audit");
            return new Done(body.mode(), user.getEmail(), password, delivery);
        }));
        audit.record("company.admin_recovered", "company", companyId, Map.of("user", done.email(), "mode", body.mode(),
                "secondStepReset", body.resetTwoStep(), "reason", reason));
        return done;
    }

    private void requireCompany(long id) {
        boolean known = tenants.callAsPlatform(() -> jdbc.sql("select count(*) from company where id = ?").param(id)
                .query(Integer.class).single()) > 0;
        if (!known) {
            throw new NotFoundException("Unknown company.");
        }
    }

    private static void requireProvider() {
        if (!TenantContext.isProvider(TenantContext.companyId())) {
            throw new AccessDeniedException("Only the provider manages companies.");
        }
    }
}
