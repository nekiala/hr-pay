package cd.hrpay.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class GeoFenceTest {

    @Test
    void distanceFromGombeToCentralBrazzavilleIsAboutNineKilometres() {
        double d = GeoFence.distanceMeters(-4.3037, 15.3108, -4.2634, 15.2429);
        assertThat(d).isCloseTo(8_760, within(50.0));
    }

    @Test
    void containsPointsInsideTheRadiusOnly() {
        GeoFence fence = new GeoFence(-4.3037, 15.3108, 150);
        assertThat(fence.contains(-4.3037, 15.3108)).isTrue();
        assertThat(fence.contains(-4.3040, 15.3110)).isTrue();   // about 40 m away
        assertThat(fence.contains(-4.3060, 15.3108)).isFalse();  // about 255 m away
    }
}
