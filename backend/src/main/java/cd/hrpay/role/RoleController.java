package cd.hrpay.role;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RoleController {

    public record PermissionView(String code, String category, String description) {}

    public record RoleView(Long id, String code, String name, String description, boolean systemRole,
                           Set<String> permissions, boolean twoStepRequired) {
        static RoleView of(Role role) {
            return new RoleView(role.getId(), role.getCode(), role.getName(), role.getDescription(),
                    role.isSystemRole(), new TreeSet<>(role.getPermissions()), role.isTwoStepRequired());
        }
    }

    public record CreateRole(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,31}", message = "Use 2 to 32 capital letters, digits or _")
            String code,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 255) String description,
            @NotNull Set<String> permissions,
            Boolean twoStepRequired) {}

    /** twoStepRequired left out keeps the role's current setting. */
    public record UpdateRole(@NotBlank @Size(max = 100) String name, @Size(max = 255) String description,
                             @NotNull Set<String> permissions, Boolean twoStepRequired) {}

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final AuditService audit;

    public RoleController(RoleRepository roles, PermissionRepository permissions, AuditService audit) {
        this.roles = roles;
        this.permissions = permissions;
        this.audit = audit;
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('role.read')")
    public List<PermissionView> permissions() {
        boolean provider = cd.hrpay.tenancy.TenantContext.isProvider(cd.hrpay.tenancy.TenantContext.companyId());
        return permissions.findAllByOrderByCategoryAscCodeAsc().stream()
                .filter(p -> provider || !cd.hrpay.tenancy.TenantContext.PLATFORM_PERMISSIONS.contains(p.getCode()))
                .map(p -> new PermissionView(p.getCode(), p.getCategory(), p.getDescription()))
                .toList();
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('role.read') or hasAuthority('user.create')")
    public List<RoleView> list() {
        return roles.findAllByOrderByNameAsc().stream().map(RoleView::of).toList();
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('role.manage')")
    @Transactional
    public RoleView create(@Valid @RequestBody CreateRole body) {
        if (roles.existsByCodeIgnoreCase(body.code())) {
            throw new ConflictException("A role with code " + body.code() + " already exists.");
        }
        Role role = new Role(body.code(), body.name().trim(), body.description());
        role.setPermissions(checkedPermissions(body.permissions()));
        role.setTwoStepRequired(Boolean.TRUE.equals(body.twoStepRequired()));
        roles.save(role);
        audit.record("role.created", "role", role.getId(),
                Map.of("code", role.getCode(), "permissions", new TreeSet<>(role.getPermissions())));
        return RoleView.of(role);
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('role.manage')")
    @Transactional
    public RoleView update(@PathVariable Long id, @Valid @RequestBody UpdateRole body) {
        Role role = roles.findById(id).orElseThrow(() -> new NotFoundException("Role not found."));
        Set<String> before = new TreeSet<>(role.getPermissions());
        Set<String> after = checkedPermissions(body.permissions());
        if ("ADMIN".equals(role.getCode()) && !after.containsAll(Set.of("role.manage", "role.assign"))) {
            throw new BusinessRuleException("The Administrator role must keep role.manage and role.assign.");
        }
        role.setName(body.name().trim());
        role.setDescription(body.description());
        role.setPermissions(after);
        boolean twoStepBefore = role.isTwoStepRequired();
        if (body.twoStepRequired() != null) {
            role.setTwoStepRequired(body.twoStepRequired());
        }
        audit.record("role.updated", "role", role.getId(),
                Map.of("added", difference(after, before), "removed", difference(before, after),
                        "twoStepRequired", role.isTwoStepRequired()));
        if (twoStepBefore != role.isTwoStepRequired()) {
            audit.record("role.two_step_" + (role.isTwoStepRequired() ? "required" : "optional"), "role", role.getId(),
                    Map.of("code", role.getCode()));
        }
        return RoleView.of(role);
    }

    @DeleteMapping("/roles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('role.manage')")
    @Transactional
    public void delete(@PathVariable Long id) {
        Role role = roles.findById(id).orElseThrow(() -> new NotFoundException("Role not found."));
        if (role.isSystemRole()) {
            throw new BusinessRuleException("Built-in roles cannot be deleted.");
        }
        if (roles.countUsers(id) > 0) {
            throw new BusinessRuleException("Remove this role from all users before deleting it.");
        }
        roles.delete(role);
        audit.record("role.deleted", "role", id, Map.of("code", role.getCode()));
    }

    private Set<String> checkedPermissions(Set<String> requested) {
        boolean provider = cd.hrpay.tenancy.TenantContext.isProvider(cd.hrpay.tenancy.TenantContext.companyId());
        Set<String> known = permissions.findAll().stream().map(Permission::getCode)
                .filter(code -> provider || !cd.hrpay.tenancy.TenantContext.PLATFORM_PERMISSIONS.contains(code))
                .collect(Collectors.toSet());
        Set<String> unknown = difference(requested, known);
        if (!unknown.isEmpty()) {
            throw new BusinessRuleException("Unknown permissions: " + String.join(", ", unknown));
        }
        return new TreeSet<>(requested);
    }

    private static Set<String> difference(Set<String> a, Set<String> b) {
        Set<String> result = new TreeSet<>(a);
        result.removeAll(b);
        return result;
    }
}
