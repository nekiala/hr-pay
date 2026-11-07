package cd.hrpay.user;

import cd.hrpay.common.BusinessRuleException;

/** At least 10 characters with a letter and a digit. */
public final class PasswordPolicy {

    private PasswordPolicy() {}

    public static String check(String password) {
        if (password == null || password.length() < 10 || password.length() > 128
                || !password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            throw new BusinessRuleException("Passwords need 10 to 128 characters, with at least one letter and one digit.");
        }
        return password;
    }
}
