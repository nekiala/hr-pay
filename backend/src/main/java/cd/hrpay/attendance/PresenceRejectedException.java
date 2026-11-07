package cd.hrpay.attendance;

import cd.hrpay.common.BusinessRuleException;

/** A check-in or check-out refused because the employee does not appear to be on site. */
public class PresenceRejectedException extends BusinessRuleException {

    private final String reason;

    public PresenceRejectedException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
