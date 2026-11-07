package cd.hrpay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HrPayApplication {
    public static void main(String[] args) {
        SpringApplication.run(HrPayApplication.class, args);
    }
}
