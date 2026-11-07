package cd.hrpay.platform;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The module a controller belongs to: ATTENDANCE, PAYROLL or EVALUATIONS. Without the module in the company's plan
 * (or with the licence locked), its changes are refused; reading stays possible, so no data is ever out of reach.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RequiresModule {
    String value();
}
