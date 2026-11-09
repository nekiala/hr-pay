package cd.hrpay.notify;

import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** WhatsApp: the platform's and a company's account, an employee's agreement, and Meta's webhook. */
@RestController
public class WhatsAppController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppController.class);

    public record ConfirmInput(@NotNull UUID challengeId, @NotBlank String code) {}

    private final WhatsApp whatsapp;
    private final WhatsAppConsent consent;
    private final UserService users;
    private final JsonMapper json = JsonMapper.builder().build();

    public WhatsAppController(WhatsApp whatsapp, WhatsAppConsent consent, UserService users) {
        this.whatsapp = whatsapp;
        this.consent = consent;
        this.users = users;
    }

    // ---- the employee ----

    @GetMapping("/api/my/whatsapp")
    @PreAuthorize("isAuthenticated()")
    public WhatsAppConsent.Mine mine() {
        return consent.mine(users.find(CurrentUser.id()));
    }

    @PostMapping("/api/my/whatsapp/code")
    @PreAuthorize("isAuthenticated()")
    public WhatsAppConsent.CodeSent code() {
        return consent.sendCode(users.find(CurrentUser.id()));
    }

    @PostMapping("/api/my/whatsapp/confirm")
    @PreAuthorize("isAuthenticated()")
    public WhatsAppConsent.Mine confirm(@Valid @RequestBody ConfirmInput b) {
        return consent.confirm(users.find(CurrentUser.id()), b.challengeId(), b.code().trim());
    }

    @PostMapping("/api/my/whatsapp/stop")
    @PreAuthorize("isAuthenticated()")
    public WhatsAppConsent.Mine stop() {
        return consent.stop(users.find(CurrentUser.id()));
    }

    // ---- company ----

    @GetMapping("/api/company/whatsapp")
    @PreAuthorize("hasAuthority('company.manage')")
    public WhatsApp.CompanyView company() {
        return whatsapp.company();
    }

    @PutMapping("/api/company/whatsapp")
    @PreAuthorize("hasAuthority('company.manage')")
    public WhatsApp.CompanyView saveCompany(@RequestBody WhatsApp.CompanyInput in) {
        return whatsapp.saveCompany(in, CurrentUser.email());
    }

    @PostMapping("/api/company/whatsapp/templates/submit")
    @PreAuthorize("hasAuthority('company.manage')")
    public WhatsApp.Settings submitCompany() {
        return whatsapp.submitTemplates(false);
    }

    @PostMapping("/api/company/whatsapp/templates/refresh")
    @PreAuthorize("hasAuthority('company.manage')")
    public WhatsApp.Settings refreshCompany() {
        return whatsapp.refreshTemplates(false);
    }

    // ---- platform ----

    @GetMapping("/api/platform/whatsapp")
    @PreAuthorize("hasAuthority('platform.manage')")
    public Map<String, Object> platform() {
        WhatsApp.Settings s = whatsapp.platform();
        return s == null ? Map.of() : Map.of("settings", s);
    }

    @PutMapping("/api/platform/whatsapp")
    @PreAuthorize("hasAuthority('platform.manage')")
    public Map<String, Object> savePlatform(@RequestBody WhatsApp.Input in) {
        return Map.of("settings", whatsapp.savePlatform(in, CurrentUser.email()));
    }

    @PostMapping("/api/platform/whatsapp/templates/submit")
    @PreAuthorize("hasAuthority('platform.manage')")
    public Map<String, Object> submitPlatform() {
        return Map.of("settings", whatsapp.submitTemplates(true));
    }

    @PostMapping("/api/platform/whatsapp/templates/refresh")
    @PreAuthorize("hasAuthority('platform.manage')")
    public Map<String, Object> refreshPlatform() {
        return Map.of("settings", whatsapp.refreshTemplates(true));
    }

    // ---- Meta's webhook (public; every call is checked against the account's app secret) ----

    @GetMapping("/api/public/whatsapp/webhook")
    public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
                                         @RequestParam(name = "hub.verify_token", required = false) String token,
                                         @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && challenge != null && whatsapp.verifyToken(token)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Unknown verify token.");
    }

    @PostMapping("/api/public/whatsapp/webhook")
    public ResponseEntity<Void> receive(@RequestBody byte[] body,
                                        @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
                String phoneNumberId = value.path("metadata").path("phone_number_id").asText("");
                WhatsApp.Account account = whatsapp.byPhoneNumberId(phoneNumberId).orElse(null);
                if (account == null) {
                    continue;
                }
                if (!WhatsApp.signed(account.appSecret(), body, signature)) {
                    log.warn("WhatsApp webhook call for {} with a wrong signature: ignored", phoneNumberId);
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
                for (JsonNode s : value.path("statuses")) {
                    JsonNode error = s.path("errors").path(0);
                    whatsapp.status(s.path("id").asText(), s.path("status").asText(),
                            error.isMissingNode() ? null : error.path("code").asText() + " " + error.path("title").asText());
                }
                for (JsonNode m : value.path("messages")) {
                    if ("text".equals(m.path("type").asText())) {
                        consent.received(account, m.path("from").asText(), m.path("text").path("body").asText());
                    } else if ("button".equals(m.path("type").asText())) {
                        consent.received(account, m.path("from").asText(), m.path("button").path("text").asText());
                    }
                }
            }
        }
        // Meta only needs to know the call arrived.
        return ResponseEntity.ok().build();
    }
}
