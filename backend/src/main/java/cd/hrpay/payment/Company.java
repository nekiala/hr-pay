package cd.hrpay.payment;

import cd.hrpay.audit.AuditService;
import cd.hrpay.security.CurrentUser;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The employer's own details, printed on declarations and payment files. */
@Service
public class Company {

    public record Details(String name, String address, String cnssNumber, String taxNumber, String nationalId,
                          String rccm, String inppNumber, String onemNumber, String bankName, String bankAccount) {}

    /** How payslips look: contact details and legal mentions next to the logo, the footer, and the layout. */
    public record PayslipSettings(String phone, String email, String website, String legalForm, String footer,
                                  String template, boolean hasLogo) {}

    public record Logo(String contentType, byte[] data) {}

    private static final java.util.Set<String> LOGO_TYPES = java.util.Set.of("image/png", "image/jpeg");
    private static final int LOGO_MAX_BYTES = 512 * 1024;

    private final JdbcClient jdbc;
    private final AuditService audit;

    public Company(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** The language the company's documents are written in (fr or en). */
    public String language() {
        return jdbc.sql("select language from company where id = current_company_id()").query(String.class).optional().orElse("fr");
    }

    /** The company's country (CD, CG) and its local currency (CDF, XAF). */
    public String country() {
        return jdbc.sql("select country_code from company where id = current_company_id()").query(String.class).optional().orElse("CD");
    }

    public String localCurrency() {
        return jdbc.sql("select local_currency from company where id = current_company_id()").query(String.class).optional().orElse("CDF");
    }

    public Details get() {
        return jdbc.sql("""
                select name, address, cnss_number, tax_number, national_id, rccm, inpp_number, onem_number,
                       bank_name, bank_account from company where id = current_company_id()""").query(Details.class).single();
    }

    @Transactional
    public Details save(Details d) {
        jdbc.sql("""
                update company set name = ?, address = ?, cnss_number = ?, tax_number = ?, national_id = ?, rccm = ?,
                       inpp_number = ?, onem_number = ?, bank_name = ?, bank_account = ?, updated_by = ?, updated_at = now()
                where id = current_company_id()""")
                .params(d.name().trim(), blank(d.address()), blank(d.cnssNumber()), blank(d.taxNumber()),
                        blank(d.nationalId()), blank(d.rccm()), blank(d.inppNumber()), blank(d.onemNumber()),
                        blank(d.bankName()), blank(d.bankAccount()), CurrentUser.email())
                .update();
        audit.record("company.updated", "company", cd.hrpay.tenancy.TenantContext.companyId(), Map.of("name", d.name().trim()));
        return get();
    }

    public PayslipSettings payslipSettings() {
        return jdbc.sql("""
                select phone, email, website, legal_form, payslip_footer as footer, payslip_template as template,
                       logo is not null as has_logo from company where id = current_company_id()""").query(PayslipSettings.class).single();
    }

    @Transactional
    public PayslipSettings savePayslipSettings(PayslipSettings s) {
        jdbc.sql("""
                update company set phone = ?, email = ?, website = ?, legal_form = ?, payslip_footer = ?, payslip_template = ?,
                       updated_by = ?, updated_at = now() where id = current_company_id()""")
                .params(blank(s.phone()), blank(s.email()), blank(s.website()), blank(s.legalForm()), blank(s.footer()),
                        s.template(), CurrentUser.email()).update();
        audit.record("company.payslip_settings_updated", "company", cd.hrpay.tenancy.TenantContext.companyId(), Map.of("template", s.template()));
        return payslipSettings();
    }

    public java.util.Optional<Logo> logo() {
        return jdbc.sql("select logo_content_type, logo from company where id = current_company_id() and logo is not null")
                .query((rs, i) -> new Logo(rs.getString(1), rs.getBytes(2))).optional();
    }

    @Transactional
    public void saveLogo(String contentType, byte[] data) {
        if (!LOGO_TYPES.contains(contentType)) {
            throw new cd.hrpay.common.BusinessRuleException("Send the logo as a PNG or JPEG image.");
        }
        if (data.length == 0 || data.length > LOGO_MAX_BYTES) {
            throw new cd.hrpay.common.BusinessRuleException("The logo must be at most 500 KB.");
        }
        // Check it really is an image the PDF can draw.
        try {
            if (javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data)) == null) {
                throw new cd.hrpay.common.BusinessRuleException("The logo could not be read as an image.");
            }
        } catch (java.io.IOException e) {
            throw new cd.hrpay.common.BusinessRuleException("The logo could not be read as an image.");
        }
        jdbc.sql("update company set logo = ?, logo_content_type = ?, updated_by = ?, updated_at = now() where id = current_company_id()")
                .params(data, contentType, CurrentUser.email()).update();
        audit.record("company.logo_changed", "company", cd.hrpay.tenancy.TenantContext.companyId(), Map.of("bytes", data.length));
    }

    @Transactional
    public void removeLogo() {
        jdbc.sql("update company set logo = null, logo_content_type = null, updated_by = ?, updated_at = now() where id = current_company_id()")
                .param(CurrentUser.email()).update();
        audit.record("company.logo_removed", "company", cd.hrpay.tenancy.TenantContext.companyId(), Map.of());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
