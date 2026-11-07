package cd.hrpay.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IpRangeTest {

    @Test
    void matchesIpv4Ranges() {
        IpRange range = IpRange.parse("203.0.113.0/24");
        assertThat(range.contains("203.0.113.1")).isTrue();
        assertThat(range.contains("203.0.113.255")).isTrue();
        assertThat(range.contains("203.0.114.1")).isFalse();
    }

    @Test
    void normalizesHostBits() {
        assertThat(IpRange.parse("10.1.2.3/8")).hasToString("10.0.0.0/8");
        assertThat(IpRange.parse("192.168.1.10")).hasToString("192.168.1.10/32");
    }

    @Test
    void handlesOddPrefixes() {
        IpRange range = IpRange.parse("41.243.16.0/21");
        assertThat(range.contains("41.243.23.254")).isTrue();
        assertThat(range.contains("41.243.24.1")).isFalse();
    }

    @Test
    void matchesIpv6AndKeepsFamiliesApart() {
        IpRange range = IpRange.parse("2001:db8::/32");
        assertThat(range.contains("2001:db8:1::5")).isTrue();
        assertThat(range.contains("2001:db9::1")).isFalse();
        assertThat(range.contains("203.0.113.1")).isFalse();
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> IpRange.parse("300.1.1.1/24")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IpRange.parse("10.0.0.0/33")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IpRange.parse("office.example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IpRange.parse("10.0.0.0/8/1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(IpRange.parse("10.0.0.0/8").contains("not-an-ip")).isFalse();
    }
}
