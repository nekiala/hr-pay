package cd.hrpay.common;

/** A request that is well formed but breaks a business rule, such as assigning a role without the right. */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}
