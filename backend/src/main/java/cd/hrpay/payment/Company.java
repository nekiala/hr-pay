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

    private final JdbcClient jdbc;
    private final AuditService audit;

    public Company(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public Details get() {
        return jdbc.sql("""
                select name, address, cnss_number, tax_number, national_id, rccm, inpp_number, onem_number,
                       bank_name, bank_account from company where id = 1""").query(Details.class).single();
    }

    @Transactional
    public Details save(Details d) {
        jdbc.sql("""
                update company set name = ?, address = ?, cnss_number = ?, tax_number = ?, national_id = ?, rccm = ?,
                       inpp_number = ?, onem_number = ?, bank_name = ?, bank_account = ?, updated_by = ?, updated_at = now()
                where id = 1""")
                .params(d.name().trim(), blank(d.address()), blank(d.cnssNumber()), blank(d.taxNumber()),
                        blank(d.nationalId()), blank(d.rccm()), blank(d.inppNumber()), blank(d.onemNumber()),
                        blank(d.bankName()), blank(d.bankAccount()), CurrentUser.email())
                .update();
        audit.record("company.updated", "company", 1, Map.of("name", d.name().trim()));
        return get();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
