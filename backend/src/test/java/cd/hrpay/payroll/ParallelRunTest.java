package cd.hrpay.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cd.hrpay.common.BusinessRuleException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParallelRunTest {

    @Test
    void readsAmountsAsSpreadsheetsWriteThem() {
        assertThat(ParallelRun.number("1 234,56", ';')).isEqualByComparingTo("1234.56");
        assertThat(ParallelRun.number("1,234.56", ',')).isEqualByComparingTo("1234.56");
        assertThat(ParallelRun.number("1.234,56", ';')).isEqualByComparingTo("1234.56");
        assertThat(ParallelRun.number("12,5", ';')).isEqualByComparingTo("12.5");
        assertThat(ParallelRun.number("1,234", ',')).isEqualByComparingTo("1234");
        assertThat(ParallelRun.number("$ 980.10", ',')).isEqualByComparingTo("980.10");
        assertThat(ParallelRun.number("", ';')).isNull();
    }

    @Test
    void findsTheColumnsInEnglishOrFrench() {
        List<String> warnings = new ArrayList<>();
        var rows = ParallelRun.parse("Matricule;Salaire brut;CNSS;IPR;Net à payer\nemp-1;1 000,00;50;80,5;869,50\nEMP-1;1;1;1;1\n", warnings);
        assertThat(rows).containsOnlyKeys("EMP-1");
        assertThat(rows.get("EMP-1").net()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(warnings).hasSize(1);
        assertThatThrownBy(() -> ParallelRun.parse("name,amount\nx,1\n", warnings)).isInstanceOf(BusinessRuleException.class);
    }
}
