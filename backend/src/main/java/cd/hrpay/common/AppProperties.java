package cd.hrpay.common;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Bootstrap bootstrap, SampleData sampleData, List<String> corsOrigins, String publicUrl,
                            Notifications notifications) {

    /** Email sender and the WhatsApp Cloud API. Unset WhatsApp settings turn WhatsApp off. */
    public record Notifications(String mailFrom, String whatsappToken, String whatsappPhoneNumberId,
                                String whatsappTemplate, String whatsappLanguage) {
        public boolean whatsappEnabled() {
            return whatsappToken != null && !whatsappToken.isBlank() && whatsappPhoneNumberId != null
                    && !whatsappPhoneNumberId.isBlank();
        }
    }


    public record Bootstrap(String adminEmail, String adminPassword) {}

    public record SampleData(boolean enabled, String password) {}
}
