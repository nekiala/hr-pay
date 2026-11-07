package cd.hrpay.integration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Access keys, webhooks and time clocks, for the administrator. */
@RestController
@RequestMapping("/api/integrations")
@PreAuthorize("hasAuthority('api.manage')")
public class IntegrationController {

    public record KeyInput(@NotBlank @Size(max = 100) String name, @NotEmpty List<String> permissions, LocalDate expiresOn) {}

    public record WebhookInput(@NotBlank @Size(max = 500) String url, @NotEmpty List<String> events, Boolean active) {}

    public record ClockInput(@NotBlank @Size(max = 40) String serialNo, @NotBlank @Size(max = 100) String name,
                             @NotNull Long siteId, Boolean active, @Size(max = 300) String allowedIps, Boolean useStatus) {}

    public record LinkInput(@NotBlank @Size(max = 20) String pin, @NotBlank @Size(max = 32) String registrationNo) {}

    public record Linked(int recorded) {}

    public record Catalogue(Map<String, String> scopes, List<String> events) {}

    private final ApiKeys keys;
    private final Webhooks webhooks;
    private final TimeClocks clocks;

    public IntegrationController(ApiKeys keys, Webhooks webhooks, TimeClocks clocks) {
        this.keys = keys;
        this.webhooks = webhooks;
        this.clocks = clocks;
    }

    @GetMapping("/catalogue")
    public Catalogue catalogue() {
        Map<String, String> scopes = new java.util.LinkedHashMap<>();
        ApiKeys.SCOPES.forEach((k, v) -> scopes.put(k, cd.hrpay.i18n.Texts.t(v)));
        return new Catalogue(scopes, List.copyOf(Webhooks.EVENTS.keySet()));
    }

    // ---- keys ---------------------------------------------------------------------------------------------------

    @GetMapping("/keys")
    public List<ApiKeys.Key> keys() {
        return keys.list();
    }

    @PostMapping("/keys")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeys.Created createKey(@Valid @RequestBody KeyInput in) {
        return keys.create(in.name(), in.permissions(), in.expiresOn());
    }

    @PostMapping("/keys/{id}/revoke")
    public ApiKeys.Key revoke(@PathVariable long id) {
        return keys.revoke(id);
    }

    // ---- webhooks -----------------------------------------------------------------------------------------------

    @GetMapping("/webhooks")
    public List<Webhooks.Webhook> webhooks() {
        return webhooks.list();
    }

    @PostMapping("/webhooks")
    @ResponseStatus(HttpStatus.CREATED)
    public Webhooks.Created createWebhook(@Valid @RequestBody WebhookInput in) {
        return webhooks.create(in.url(), in.events());
    }

    @PutMapping("/webhooks/{id}")
    public Webhooks.Webhook updateWebhook(@PathVariable long id, @Valid @RequestBody WebhookInput in) {
        return webhooks.update(id, in.url(), in.events(), in.active() == null || in.active());
    }

    @PostMapping("/webhooks/{id}/secret")
    public Webhooks.Created rotate(@PathVariable long id) {
        return webhooks.rotate(id);
    }

    @PostMapping("/webhooks/{id}/ping")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void ping(@PathVariable long id) {
        webhooks.ping(id);
    }

    @DeleteMapping("/webhooks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWebhook(@PathVariable long id) {
        webhooks.delete(id);
    }

    @GetMapping("/webhooks/{id}/deliveries")
    public List<Webhooks.Delivery> deliveries(@PathVariable long id) {
        return webhooks.deliveries(id);
    }

    @PostMapping("/webhooks/{id}/deliveries/{delivery}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retry(@PathVariable long id, @PathVariable long delivery) {
        webhooks.retry(id, delivery);
    }

    // ---- time clocks --------------------------------------------------------------------------------------------

    @GetMapping("/clocks")
    public List<TimeClocks.Device> clocks() {
        return clocks.devices();
    }

    @PostMapping("/clocks")
    @ResponseStatus(HttpStatus.CREATED)
    public TimeClocks.Device addClock(@Valid @RequestBody ClockInput in) {
        return clocks.save(null, in.serialNo(), in.name(), in.siteId(), in.active() == null || in.active(), in.allowedIps(),
                Boolean.TRUE.equals(in.useStatus()));
    }

    @PutMapping("/clocks/{id}")
    public TimeClocks.Device updateClock(@PathVariable long id, @Valid @RequestBody ClockInput in) {
        return clocks.save(id, in.serialNo(), in.name(), in.siteId(), in.active() == null || in.active(), in.allowedIps(),
                Boolean.TRUE.equals(in.useStatus()));
    }

    @DeleteMapping("/clocks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteClock(@PathVariable long id) {
        clocks.delete(id);
    }

    @PostMapping("/clocks/link")
    public Linked link(@Valid @RequestBody LinkInput in) {
        return new Linked(clocks.link(in.pin(), in.registrationNo()));
    }

    @GetMapping("/clocks/punches")
    public List<TimeClocks.Punch> punches(@RequestParam(required = false) Long device, @RequestParam(required = false) String outcome) {
        return clocks.punches(device, outcome);
    }
}
