package cd.hrpay.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpreadsheetReaderTest {

    @Test
    void readsBackAWorkbookItWrote() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Sheet.XlsxSheet.write(List.of(new Object[] {"Matricule", "Nom", "Salaire"},
                new Object[] {"0012", "Mbuyi, Aline", new BigDecimal("1250.50")},
                new Object[] {"0013", null, 900}), out);
        List<List<String>> rows = SpreadsheetReader.read("staff.xlsx", out.toByteArray());
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1)).containsExactly("0012", "Mbuyi, Aline", "1250.5");
        // An empty cell keeps the columns in place.
        assertThat(rows.get(2)).containsExactly("0013", "", "900");
    }

    @Test
    void readsCsvWithSemicolonsQuotesAndABom() {
        String csv = "﻿a;b;c\r\n1;\"x; \"\"y\"\"\";3\r\n\r\n";
        List<List<String>> rows = SpreadsheetReader.read("f.csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(rows).containsExactly(List.of("a", "b", "c"), List.of("1", "x; \"y\"", "3"));
    }

    @Test
    void refusesTheOldExcelFormat() {
        assertThatThrownBy(() -> SpreadsheetReader.read("old.xls", new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0}))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void columnLetters() {
        assertThat(SpreadsheetReader.column("A1")).isZero();
        assertThat(SpreadsheetReader.column("AA10")).isEqualTo(26);
    }
}
