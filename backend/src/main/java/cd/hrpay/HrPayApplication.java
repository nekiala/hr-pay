package cd.hrpay;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
@org.springframework.scheduling.annotation.EnableScheduling
public class HrPayApplication {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("reset-password")) {
            // A server command for on-premise installations; it does not start the app.
            System.exit(cd.hrpay.security.ResetPasswordCommand.run(java.util.Arrays.copyOfRange(args, 1, args.length)));
        }
        SpringApplication.run(HrPayApplication.class, args);
    }

    /** Time source for attendance calculations; tests replace it to fix "now". */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
