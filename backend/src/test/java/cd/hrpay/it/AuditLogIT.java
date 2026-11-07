package cd.hrpay.it;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class AuditLogIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void auditRowsCannotBeChangedOrDeleted() {
        assertThatThrownBy(() -> jdbc.sql("update audit_log set actor = 'someone'").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from audit_log").update())
                .hasMessageContaining("append-only");
    }
}
