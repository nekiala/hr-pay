package cd.hrpay.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    static final String PHONE = "\\+?[0-9\\s()-]{8,20}";

    public record UserInput(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 32) String registrationNo,
            @NotBlank @Pattern(regexp = PHONE, message = "Use international format, for example +243 81 234 5678")
            String phone,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            Long siteId,
            @Size(max = 100) String middleName) {
        UserService.UserData data() {
            return new UserService.UserData(email, registrationNo, phone, firstName, lastName, siteId, middleName);
        }
    }

    public record CreateUser(@Valid UserInput user, List<String> roles, String initialPassword,
                             @jakarta.validation.constraints.NotNull(message = "Choose the unit this person works in") Long orgUnitId) {}

    public record RolesInput(@NotEmpty List<String> roles) {}

    public record PasswordInput(@NotBlank String password) {}

    public record ManagerInput(Long managerId) {}

    public record EmploymentInput(Long gradeCategoryId, java.time.LocalDate hireDate) {}

    public record UserView(Long id, String email, String registrationNo, String phone, String firstName,
                           String lastName, Long siteId, String siteName, UserStatus status, Set<String> roles,
                           boolean hasPassword, OffsetDateTime lastLoginAt, OffsetDateTime createdAt,
                           Long managerId, Long gradeCategoryId, java.time.LocalDate hireDate, Long unitId, String unitName,
                           String middleName, String fullName) {
        static UserView of(AppUser u) {
            return new UserView(u.getId(), u.getEmail(), u.getRegistrationNo(), u.getPhone(), u.getFirstName(),
                    u.getLastName(), u.getSite() == null ? null : u.getSite().getId(),
                    u.getSite() == null ? null : u.getSite().getName(), u.getStatus(), u.roleCodes(),
                    u.getPasswordHash() != null, u.getLastLoginAt(), u.getCreatedAt(), u.getManagerId(),
                    u.getGradeCategoryId(), u.getHireDate(), null, null, u.getMiddleName(), u.fullName());
        }

        UserView withUnit(Long unitId, String unitName) {
            return new UserView(id, email, registrationNo, phone, firstName, lastName, siteId, siteName, status, roles,
                    hasPassword, lastLoginAt, createdAt, managerId, gradeCategoryId, hireDate, unitId, unitName,
                    middleName, fullName);
        }
    }

    public record UserPage(List<UserView> items, int page, int size, long total) {}

    private final UserRepository users;
    private final UserService service;

    private cd.hrpay.org.OrgStructure org;

    private cd.hrpay.security.UserSessions sessions;

    @org.springframework.beans.factory.annotation.Autowired
    void setSessions(cd.hrpay.security.UserSessions sessions) {
        this.sessions = sessions;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setOrg(cd.hrpay.org.OrgStructure org) {
        this.org = org;
    }

    public UserController(UserRepository users, UserService service) {
        this.users = users;
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('user.read')")
    @Transactional(readOnly = true)
    public UserPage list(@RequestParam(required = false) String q,
                         @RequestParam(required = false) Long unitId,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "25") int size) {
        String query = q == null || q.isBlank() ? null : q.trim();
        PageRequest paging = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by("lastName", "firstName"));
        var unitOf = org.unitsOn(org.today());
        var units = org.placementsOfUnits();
        Page<AppUser> result;
        if (unitId != null) {
            // The unit and everything below it.
            Set<Long> branch = org.branch(unitId);
            List<Long> ids = unitOf.entrySet().stream().filter(e -> branch.contains(e.getValue())).map(java.util.Map.Entry::getKey).toList();
            result = ids.isEmpty() ? Page.empty(paging) : users.searchIn(query, ids, paging);
        } else {
            result = users.search(query, paging);
        }
        return new UserPage(result.map(u -> withUnit(UserView.of(u), unitOf, units)).toList(), result.getNumber(),
                result.getSize(), result.getTotalElements());
    }

    private static UserView withUnit(UserView v, java.util.Map<Long, Long> unitOf, java.util.Map<Long, cd.hrpay.org.OrgStructure.Placement> units) {
        Long unit = unitOf.get(v.id());
        var p = unit == null ? null : units.get(unit);
        return p == null ? v : v.withUnit(p.unitId(), p.unitName());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user.read')")
    @Transactional(readOnly = true)
    public UserView get(@PathVariable Long id) {
        return withUnit(UserView.of(service.find(id)), org.unitsOn(org.today()), org.placementsOfUnits());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('user.create')")
    @org.springframework.transaction.annotation.Transactional
    public UserView create(@Valid @RequestBody CreateUser body) {
        org.requireOpenUnit(body.orgUnitId());
        AppUser created = service.create(body.user().data(), body.roles(), body.initialPassword());
        org.assign(created.getId(), body.orgUnitId(), null, "HIRE", null);
        return UserView.of(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('user.update')")
    public UserView update(@PathVariable Long id, @Valid @RequestBody UserInput body) {
        return UserView.of(service.update(id, body.data()));
    }

    @PutMapping("/{id}/manager")
    @PreAuthorize("hasAuthority('user.update')")
    public UserView setManager(@PathVariable Long id, @RequestBody ManagerInput body) {
        return UserView.of(service.setManager(id, body.managerId()));
    }

    @PutMapping("/{id}/employment")
    @PreAuthorize("hasAuthority('user.update')")
    public UserView setEmployment(@PathVariable Long id, @RequestBody EmploymentInput body) {
        return UserView.of(service.setEmployment(id, body.gradeCategoryId(), body.hireDate()));
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('role.assign')")
    public UserView setRoles(@PathVariable Long id, @Valid @RequestBody RolesInput body) {
        return UserView.of(service.setRoles(id, body.roles()));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('user.disable')")
    public UserView disable(@PathVariable Long id) {
        UserView view = UserView.of(service.setStatus(id, UserStatus.DISABLED));
        sessions.endAll(id, null, cd.hrpay.security.CurrentUser.email());
        return view;
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('user.disable')")
    public UserView enable(@PathVariable Long id) {
        return UserView.of(service.setStatus(id, UserStatus.ACTIVE));
    }

    @PostMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('user.update')")
    public void resetPassword(@PathVariable Long id, @Valid @RequestBody PasswordInput body) {
        service.resetPassword(id, body.password());
        sessions.endAll(id, null, cd.hrpay.security.CurrentUser.email());
    }
}
