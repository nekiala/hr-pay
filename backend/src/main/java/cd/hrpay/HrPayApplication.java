package cd.hrpay;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HrPayApplication {
    public static void main(String[] args) {
        SpringApplication.run(HrPayApplication.class, args);
    }

    /** Time source for attendance calculations; tests replace it to fix "now". */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
