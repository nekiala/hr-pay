package cd.hrpay.user;

import cd.hrpay.role.Role;
import cd.hrpay.site.Site;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String email;
    private String registrationNo;
    @Column(name = "phone_e164")
    private String phone;
    private String firstName;
    private String lastName;
    private String passwordHash;
    @Enumerated(EnumType.STRING)
    private UserStatus status = UserStatus.ACTIVE;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "site_id")
    private Site site;
    private boolean mustChangePassword;
    private Long managerId;
    private Long gradeCategoryId;
    private java.time.LocalDate hireDate;
    private OffsetDateTime lastLoginAt;
    private OffsetDateTime privacyAcceptedAt;
    private OffsetDateTime createdAt = OffsetDateTime.now();
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_role", joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    protected AppUser() {}

    public AppUser(String email, String registrationNo, String phone, String firstName, String lastName) {
        this.email = email;
        this.registrationNo = registrationNo;
        this.phone = phone;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    @PreUpdate
    void touch() { updatedAt = OffsetDateTime.now(); }

    public Set<String> permissionCodes() {
        return roles.stream().flatMap(r -> r.getPermissions().stream()).collect(Collectors.toCollection(TreeSet::new));
    }

    public Set<String> roleCodes() {
        return roles.stream().map(Role::getCode).collect(Collectors.toCollection(TreeSet::new));
    }

    public boolean isActive() { return status == UserStatus.ACTIVE; }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getRegistrationNo() { return registrationNo; }
    public void setRegistrationNo(String registrationNo) { this.registrationNo = registrationNo; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus status) { this.status = status; }
    public Site getSite() { return site; }
    public Long getManagerId() { return managerId; }
    public Long getGradeCategoryId() { return gradeCategoryId; }
    public void setGradeCategoryId(Long gradeCategoryId) { this.gradeCategoryId = gradeCategoryId; }
    public java.time.LocalDate getHireDate() { return hireDate; }
    public void setHireDate(java.time.LocalDate hireDate) { this.hireDate = hireDate; }
    public void setManagerId(Long managerId) { this.managerId = managerId; }
    public void setSite(Site site) { this.site = site; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public OffsetDateTime getPrivacyAcceptedAt() { return privacyAcceptedAt; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }
    public OffsetDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(OffsetDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) {
        this.roles.clear();
        this.roles.addAll(roles);
    }
}
