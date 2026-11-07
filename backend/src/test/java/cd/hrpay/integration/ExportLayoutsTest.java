package cd.hrpay.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExportLayoutsTest {

    @Test
    void writesTheColumnsTheWayTheLayoutSays() {
        ExportLayouts.Layout l = new ExportLayouts.Layout(1, "ACCOUNTING", "Sage", List.of(
                new ExportLayouts.Column("journal", "Journal", null), new ExportLayouts.Column("date", "Date", null),
                new ExportLayouts.Column("label", "Libellé", null), new ExportLayouts.Column("amount", "Montant", null),
                new ExportLayouts.Column("text", "Type", "G")),
                ";", true, "dd/MM/yyyy", true, "WINDOWS-1252", "txt", null, null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("journal", "PAIE");
        row.put("date", LocalDate.of(2026, 3, 31));
        row.put("label", "Rémunération; mars");
        row.put("amount", new BigDecimal("-1250.5"));
        ExportLayouts.File f = new ExportLayouts(null, null, null, null, null, null).write(l, List.of(row), "x");
        String text = new String(f.content(), Charset.forName("windows-1252"));
        assertThat(f.name()).isEqualTo("x.txt");
        assertThat(text).isEqualTo("Journal;Date;Libellé;Montant;Type\r\nPAIE;31/03/2026;\"Rémunération; mars\";-1250,50;G\r\n");
    }

    @Test
    void signsWebhooksWithHmacSha256() {
        // Known value: HMAC-SHA256("key", "The quick brown fox jumps over the lazy dog").
        assertThat(Webhooks.hmac("key", "The quick brown fox jumps over the lazy dog"))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }
}
