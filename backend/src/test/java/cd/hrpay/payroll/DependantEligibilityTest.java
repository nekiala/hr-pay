package cd.hrpay.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Who counts in the worker's family: DRC Code du travail art. 7 point 11, with the limits 18 and 25. */
class DependantEligibilityTest {

    static final LocalDate ON = LocalDate.of(2026, 11, 1);

    static EmployeeFile.Eligibility of(String relationship, LocalDate birth, boolean inCharge, boolean student,
                                       boolean disabled) {
        return EmployeeFile.eligibility(new EmployeeFile.Dependant(1, "X", relationship, birth, inCharge, student,
                disabled, null), ON, 18, 25);
    }

    @Test
    void aMinorCountsUntilTheDayBeforeTurningEighteen() {
        var e = of("CHILD", LocalDate.of(2010, 5, 10), true, false, false);
        assertThat(e.counts()).isTrue();
        assertThat(e.until()).isEqualTo(LocalDate.of(2028, 5, 9));
    }

    @Test
    void anAdultChildCountsOnlyWhileStudyingUntilTwentyFive() {
        LocalDate twenty = LocalDate.of(2006, 3, 1);
        assertThat(of("CHILD", twenty, true, false, false).counts()).isFalse();
        var student = of("CHILD", twenty, true, true, false);
        assertThat(student.counts()).isTrue();
        assertThat(student.until()).isEqualTo(LocalDate.of(2031, 2, 28));
        assertThat(of("CHILD", LocalDate.of(2001, 10, 31), true, true, false).counts()).isFalse();
    }

    @Test
    void aDisabledChildHasNoAgeLimit() {
        var e = of("CHILD", LocalDate.of(1990, 1, 1), true, false, true);
        assertThat(e.counts()).isTrue();
        assertThat(e.until()).isNull();
    }

    @Test
    void theSpouseCountsWhenInChargeAndNobodyCountsOutOfCharge() {
        assertThat(of("SPOUSE", LocalDate.of(1960, 1, 1), true, false, false).counts()).isTrue();
        assertThat(of("CHILD", LocalDate.of(2015, 1, 1), false, false, false).counts()).isFalse();
    }

    @Test
    void withoutAnAgeLimitEveryChildInChargeCounts() {
        var e = EmployeeFile.eligibility(new EmployeeFile.Dependant(1, "X", "CHILD", LocalDate.of(1990, 1, 1), true,
                false, false, null), ON, null, null);
        assertThat(e.counts()).isTrue();
    }
}
