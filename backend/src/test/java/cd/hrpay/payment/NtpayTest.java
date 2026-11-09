package cd.hrpay.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class NtpayTest {

    static String hmac(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aNotificationIsSignedWithTheKeyAndRecent() throws Exception {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String good = "v1=" + hmac("key", "1700000000.{\"a\":1}");
        assertThat(Ntpay.signed("key", body, "1700000000", good, 1700000100)).isTrue();
        // During a key change two signatures come; one is enough.
        assertThat(Ntpay.signed("key", body, "1700000000", "v1=" + hmac("old", "1700000000.{\"a\":1}") + ", " + good, 1700000100)).isTrue();
        assertThat(Ntpay.signed("other", body, "1700000000", good, 1700000100)).isFalse();
        assertThat(Ntpay.signed("key", body, "1700000000", good, 1700000400)).isFalse();
        assertThat(Ntpay.signed("key", "{\"a\":2}".getBytes(StandardCharsets.UTF_8), "1700000000", good, 1700000100)).isFalse();
        assertThat(Ntpay.signed(null, body, "1700000000", good, 1700000100)).isFalse();
    }

    @Test
    void linesAreFoundByTheirReference() {
        assertThat(Ntpay.lineId(Ntpay.lineReference(42))).isEqualTo(42L);
        assertThat(Ntpay.lineId("CMD-1042")).isNull();
        assertThat(Ntpay.mask("243810000007")).isEqualTo("…0007");
    }
}
