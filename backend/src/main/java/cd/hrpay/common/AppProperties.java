package cd.hrpay.common;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Bootstrap bootstrap, SampleData sampleData, List<String> corsOrigins) {

    public record Bootstrap(String adminEmail, String adminPassword) {}

    public record SampleData(boolean enabled, String password) {}
}
