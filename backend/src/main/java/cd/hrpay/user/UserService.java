package cd.hrpay.user;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.role.Role;
import cd.hrpay.role.RoleRepository;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.site.Site;
import cd.hrpay.site.SiteRepository;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User rules that are not plain permission checks.
 *
 * <p>Someone with {@code user.create} but without {@code role.assign} (HR by default) can only create
 * and manage users whose only role is Employee, so HR cannot create or change an administrator.
 */
@Service
public class UserService {

    public record UserData(String email, String registrationNo, String phone, String firstName, String lastName,
                           Long siteId) {}

    private final UserRepository users;
    private final RoleRepository roles;
    private final SiteRepository sites;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    public UserService(UserRepository users, RoleRepository roles, SiteRepository sites,
                       PasswordEncoder passwordEncoder, AuditService audit) {
        this.users = users;
        this.roles = roles;
        this.sites = sites;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    @Transactional
    public AppUser create(UserData data, List<String> roleCodes, String initialPassword) {
        UserData clean = normalize(data);
        checkUnique(clean, null);
        Set<Role> wanted = resolveRoles(roleCodes == null || roleCodes.isEmpty() ? List.of(Role.EMPLOYEE) : roleCodes);
        requireCanGrant(wanted);

        AppUser user = new AppUser(clean.email(), clean.registrationNo(), clean.phone(), clean.firstName(),
                clean.lastName());
        user.setSite(resolveSite(clean.siteId()));
        user.setRoles(wanted);
        if (initialPassword != null && !initialPassword.isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(PasswordPolicy.check(initialPassword)));
            user.setMustChangePassword(true);
        }
        users.save(user);
        audit.record("user.created", "user", user.getId(), Map.of("email", user.getEmail(),
                "registrationNo", user.getRegistrationNo(), "roles", user.roleCodes()));
        return user;
    }

    @Transactional
    public AppUser update(Long id, UserData data) {
        AppUser user = find(id);
        requireCanManage(user);
        UserData clean = normalize(data);
        checkUnique(clean, user.getId());
        Map<String, Object> changes = new LinkedHashMap<>();
        track(changes, "email", user.getEmail(), clean.email());
        track(changes, "registrationNo", user.getRegistrationNo(), clean.registrationNo());
        track(changes, "phone", user.getPhone(), clean.phone());
        track(changes, "firstName", user.getFirstName(), clean.firstName());
        track(changes, "lastName", user.getLastName(), clean.lastName());
        track(changes, "siteId", user.getSite() == null ? null : user.getSite().getId(), clean.siteId());
        user.setEmail(clean.email());
        user.setRegistrationNo(clean.registrationNo());
        user.setPhone(clean.phone());
        user.setFirstName(clean.firstName());
        user.setLastName(clean.lastName());
        user.setSite(resolveSite(clean.siteId()));
        if (!changes.isEmpty()) {
            audit.record("user.updated", "user", user.getId(), changes);
        }
        return user;
    }

    @Transactional
    public AppUser setEmployment(Long id, Long gradeCategoryId, java.time.LocalDate hireDate) {
        AppUser user = find(id);
        Map<String, Object> changes = new LinkedHashMap<>();
        track(changes, "gradeCategoryId", user.getGradeCategoryId(), gradeCategoryId);
        track(changes, "hireDate", user.getHireDate(), hireDate);
        user.setGradeCategoryId(gradeCategoryId);
        user.setHireDate(hireDate);
        if (!changes.isEmpty()) {
            audit.record("user.employment_changed", "user", id, changes);
        }
        return user;
    }

    @Transactional
    public AppUser setManager(Long id, Long managerId) {
        AppUser user = find(id);
        if (managerId != null) {
            if (managerId.equals(id)) {
                throw new BusinessRuleException("An employee cannot be their own manager.");
            }
            // No loops: walking up from the new manager must never reach this employee.
            Long current = find(managerId).getManagerId();
            for (int depth = 0; current != null && depth < 50; depth++) {
                if (current.equals(id)) {
                    throw new BusinessRuleException("This would make a reporting loop.");
                }
                current = find(current).getManagerId();
            }
        }
        if (!java.util.Objects.equals(user.getManagerId(), managerId)) {
            audit.record("user.manager_changed", "user", id, java.util.Collections.singletonMap("managerId", managerId));
            user.setManagerId(managerId);
        }
        return user;
    }

    @Transactional
    public AppUser setRoles(Long id, List<String> roleCodes) {
        AppUser user = find(id);
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw new BusinessRuleException("A user needs at least one role.");
        }
        Set<Role> wanted = resolveRoles(roleCodes);
        Set<String> before = user.roleCodes();
        if (Objects.equals(user.getId(), CurrentUser.id())
                && before.contains("ADMIN") && wanted.stream().noneMatch(r -> r.getCode().equals("ADMIN"))) {
            throw new BusinessRuleException("You cannot remove your own Administrator role.");
        }
        user.setRoles(wanted);
        audit.record("user.roles_changed", "user", user.getId(), Map.of("before", before, "after", user.roleCodes()));
        return user;
    }

    @Transactional
    public AppUser setStatus(Long id, UserStatus status) {
        AppUser user = find(id);
        requireCanManage(user);
        if (status == UserStatus.DISABLED && Objects.equals(user.getId(), CurrentUser.id())) {
            throw new BusinessRuleException("You cannot disable your own account.");
        }
        if (user.getStatus() != status) {
            user.setStatus(status);
            audit.record(status == UserStatus.DISABLED ? "user.disabled" : "user.enabled", "user", user.getId(), null);
        }
        return user;
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        AppUser user = find(id);
        requireCanManage(user);
        user.setPasswordHash(passwordEncoder.encode(PasswordPolicy.check(newPassword)));
        user.setMustChangePassword(true);
        audit.record("user.password_reset", "user", user.getId(), null);
    }

    @Transactional
    public void changeOwnPassword(Long id, String currentPassword, String newPassword) {
        AppUser user = find(id);
        if (user.getPasswordHash() == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessRuleException("The current password is not correct.");
        }
        user.setPasswordHash(passwordEncoder.encode(PasswordPolicy.check(newPassword)));
        user.setMustChangePassword(false);
        audit.record("user.password_changed", "user", user.getId(), null);
    }

    public AppUser find(Long id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("User not found."));
    }

    private void requireCanGrant(Set<Role> wanted) {
        boolean onlyEmployee = wanted.stream().allMatch(r -> r.getCode().equals(Role.EMPLOYEE));
        if (!onlyEmployee && !CurrentUser.has("role.assign")) {
            throw new BusinessRuleException("You can only create users with the Employee role.");
        }
    }

    private void requireCanManage(AppUser user) {
        boolean onlyEmployee = user.roleCodes().equals(Set.of(Role.EMPLOYEE));
        if (!onlyEmployee && !CurrentUser.has("role.assign")) {
            throw new BusinessRuleException("You can only manage users whose only role is Employee.");
        }
    }

    private Set<Role> resolveRoles(List<String> codes) {
        Set<Role> result = new HashSet<>();
        for (String code : codes) {
            result.add(roles.findByCode(code).orElseThrow(() -> new BusinessRuleException("Unknown role: " + code)));
        }
        return result;
    }

    private Site resolveSite(Long siteId) {
        return siteId == null ? null
                : sites.findById(siteId).orElseThrow(() -> new BusinessRuleException("Unknown site: " + siteId));
    }

    private void checkUnique(UserData data, Long selfId) {
        users.findByEmailIgnoreCase(data.email()).filter(u -> !u.getId().equals(selfId)).ifPresent(u -> {
            throw new ConflictException("This email address is already used.");
        });
        users.findByRegistrationNoIgnoreCase(data.registrationNo()).filter(u -> !u.getId().equals(selfId))
                .ifPresent(u -> {
                    throw new ConflictException("This registration number is already used.");
                });
        users.findByPhone(data.phone()).filter(u -> !u.getId().equals(selfId)).ifPresent(u -> {
            throw new ConflictException("This phone number is already used.");
        });
    }

    static UserData normalize(UserData d) {
        String phone = d.phone().replaceAll("[\\s()-]", "");
        if (!phone.matches("\\+[1-9][0-9]{7,14}")) {
            throw new BusinessRuleException("Enter the phone number in international format, for example +243812345678.");
        }
        return new UserData(d.email().trim().toLowerCase(), d.registrationNo().trim().toUpperCase(), phone,
                d.firstName().trim(), d.lastName().trim(), d.siteId());
    }

    private static void track(Map<String, Object> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("before", before);
            change.put("after", after);
            changes.put(field, change);
        }
    }
}
