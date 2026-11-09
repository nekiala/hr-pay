package cd.hrpay.payment;

import cd.hrpay.security.CurrentUser;
import java.time.OffsetDateTime;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** NTPay: the company's keys, sending a mobile money file, following it, and NTPay's notifications. */
@RestController
public class NtpayController {

    public record SendInput(OffsetDateTime scheduledFor) {}

    private final Ntpay ntpay;

    public NtpayController(Ntpay ntpay) {
        this.ntpay = ntpay;
    }

    @GetMapping("/api/ntpay/settings")
    @PreAuthorize("hasAuthority('company.manage') or hasAuthority('payroll.pay')")
    public Ntpay.Settings settings() {
        return ntpay.settings();
    }

    @PutMapping("/api/ntpay/settings")
    @PreAuthorize("hasAuthority('company.manage')")
    public Ntpay.Settings save(@RequestBody Ntpay.SettingsInput in) {
        return ntpay.save(in, CurrentUser.email());
    }

    @PostMapping("/api/ntpay/settings/test")
    @PreAuthorize("hasAuthority('company.manage') or hasAuthority('payroll.pay')")
    public Ntpay.Settings test() {
        return ntpay.test();
    }

    @PostMapping("/api/payment-batches/{id}/ntpay")
    @PreAuthorize("hasAuthority('payroll.pay')")
    public Payments.BatchView send(@PathVariable long id, @RequestBody(required = false) SendInput in) {
        return ntpay.send(id, in == null ? null : in.scheduledFor(), CurrentUser.email());
    }

    @PostMapping("/api/payment-batches/{id}/ntpay/refresh")
    @PreAuthorize("hasAuthority('payroll.pay')")
    public Payments.BatchView refresh(@PathVariable long id) {
        return ntpay.refresh(id);
    }

    @PostMapping("/api/payment-batches/{id}/ntpay/cancel")
    @PreAuthorize("hasAuthority('payroll.pay')")
    public Payments.BatchView cancel(@PathVariable long id) {
        return ntpay.cancel(id, CurrentUser.email());
    }

    /** NTPay's notifications (public; every call is checked against the company's signature key). */
    @PostMapping("/api/public/ntpay/{token}")
    public ResponseEntity<Void> notify(@PathVariable String token, @RequestBody byte[] body,
                                       @RequestHeader(name = "X-NTPay-Timestamp", required = false) String timestamp,
                                       @RequestHeader(name = "X-NTPay-Signature", required = false) String signature,
                                       @RequestHeader(name = "X-NTPay-Notification-Id", required = false) String notificationId) {
        return ResponseEntity.status(ntpay.notification(token, body, timestamp, signature, notificationId)).build();
    }
}
