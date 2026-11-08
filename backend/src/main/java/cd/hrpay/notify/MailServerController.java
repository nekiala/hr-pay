package cd.hrpay.notify;

import cd.hrpay.i18n.Texts;
import cd.hrpay.security.CurrentUser;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The email server of a company (its administrators) and of the platform (the provider). */
@RestController
public class MailServerController {

    private final MailServers servers;

    public MailServerController(MailServers servers) {
        this.servers = servers;
    }

    // ---- company ----

    @GetMapping("/api/company/mail-server")
    @PreAuthorize("hasAuthority('company.manage')")
    public MailServers.CompanyView company() {
        return servers.company();
    }

    @PutMapping("/api/company/mail-server")
    @PreAuthorize("hasAuthority('company.manage')")
    public MailServers.CompanyView saveCompany(@RequestBody MailServers.Input in) {
        return servers.saveCompany(in, CurrentUser.email());
    }

    @PostMapping("/api/company/mail-server/test")
    @PreAuthorize("hasAuthority('company.manage')")
    public Map<String, String> testCompany(@RequestBody MailServers.Input in) {
        return Map.of("message", Texts.translate(servers.testConnection(in, false)));
    }

    @PostMapping("/api/company/mail-server/test-email")
    @PreAuthorize("hasAuthority('company.manage')")
    public Map<String, String> testEmailCompany(@RequestBody MailServers.Input in) {
        return Map.of("message", testEmail(in, false));
    }

    // ---- platform ----

    @GetMapping("/api/platform/mail-server")
    @PreAuthorize("hasAuthority('platform.manage')")
    public Map<String, Object> platform() {
        MailServers.Settings s = servers.platform();
        return s == null ? Map.of() : Map.of("settings", s);
    }

    @PutMapping("/api/platform/mail-server")
    @PreAuthorize("hasAuthority('platform.manage')")
    public Map<String, Object> savePlatform(@RequestBody MailServers.Input in) {
        return Map.of("settings", servers.savePlatform(in, CurrentUser.email()));
    }

    @PostMapping("/api/platform/mail-server/test")
    @PreAuthorize("hasAuthority('platform.manage')")
    public Map<String, String> testPlatform(@RequestBody MailServers.Input in) {
        return Map.of("message", Texts.translate(servers.testConnection(in, true)));
    }

    @PostMapping("/api/platform/mail-server/test-email")
    @PreAuthorize("hasAuthority('platform.manage')")
    public Map<String, String> testEmailPlatform(@RequestBody MailServers.Input in) {
        return Map.of("message", testEmail(in, true));
    }

    private String testEmail(MailServers.Input in, boolean platform) {
        String to = CurrentUser.email();
        return Texts.translate(servers.sendTest(in, platform, to, Texts.t("HR Pay test email"),
                Texts.t("This email shows that HR Pay can send emails through this server.")));
    }
}
