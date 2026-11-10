package cd.hrpay.security;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.tenancy.Tenants;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The Google Workspace domains a company signs in from. Google sign-in is accepted only for a Workspace account (its
 * "hd" claim, which personal Gmail accounts do not have) whose organisation and email are both on the company's
 * domains. With "Google only" on, people on those domains cannot use a password.
 */
@Component
public class GoogleDomains {

    private final JdbcClient jdbc;
    private final Tenants tenants;

    GoogleDomains(JdbcClient jdbc, Tenants tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    /** The current company's domains, in order. */
    public List<String> list() {
        return jdbc.sql("select domain from company_sign_in_domain order by domain").query(String.class).list();
    }

    public boolean googleOnly() {
        return jdbc.sql("select google_only from company where id = current_company_id()").query(Boolean.class)
                .optional().orElse(false);
    }

    /**
     * Replaces the current company's domains. A domain another company already uses is refused, so two customers
     * cannot both let the same Google accounts in.
     */
    public void replace(List<String> domains, boolean googleOnly, String by) {
        List<String> clean = domains == null ? List.of()
                : domains.stream().map(GoogleDomains::normalize).filter(d -> !d.isEmpty()).distinct().sorted().toList();
        for (String d : clean) {
            if (!d.matches("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$") || d.length() > 253) {
                throw new BusinessRuleException("\"%s\" is not a domain name, for example mycompany.com.".formatted(d));
            }
            if (List.of("gmail.com", "googlemail.com").contains(d)) {
                throw new BusinessRuleException("Personal Gmail addresses cannot be a company domain.");
            }
        }
        Long me = cd.hrpay.tenancy.TenantContext.companyId();
        for (String d : clean) {
            boolean taken = tenants.callAsPlatform(() -> jdbc.sql(
                    "select count(*) from company_sign_in_domain where company_id <> ? and domain = ?")
                    .params(me, d).query(Long.class).single()) > 0;
            if (taken) {
                throw new BusinessRuleException("The domain %s is already used by another company. Contact support.".formatted(d));
            }
        }
        if (googleOnly && clean.isEmpty()) {
            throw new BusinessRuleException("Add at least one domain before requiring Google sign-in.");
        }
        jdbc.sql("delete from company_sign_in_domain").update();
        for (String d : clean) {
            jdbc.sql("insert into company_sign_in_domain (domain, created_by) values (?, ?)").params(d, by).update();
        }
        jdbc.sql("update company set google_only = ? where id = current_company_id()").param(googleOnly).update();
    }

    /**
     * Why the current company refuses this Google account, or null when it accepts it.
     *
     * @param email the email Google verified
     * @param hd    Google's hosted-domain claim: the account's Workspace organisation, null for a personal account
     */
    public String refusal(String email, String hd) {
        if (hd == null || hd.isBlank()) {
            return "personal_account";
        }
        List<String> mine = list();
        if (!mine.contains(normalize(hd)) || !mine.contains(domainOf(email))) {
            return "domain_not_allowed";
        }
        return null;
    }

    /** True when the current company makes this email sign in with Google rather than a password. */
    public boolean passwordRefused(String email) {
        return googleOnly() && list().contains(domainOf(email));
    }

    static String domainOf(String email) {
        int at = email == null ? -1 : email.lastIndexOf('@');
        return at < 0 ? "" : normalize(email.substring(at + 1));
    }

    static String normalize(String domain) {
        String d = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
        if (d.startsWith("@")) {
            d = d.substring(1);
        }
        return d.endsWith(".") ? d.substring(0, d.length() - 1) : d;
    }
}
