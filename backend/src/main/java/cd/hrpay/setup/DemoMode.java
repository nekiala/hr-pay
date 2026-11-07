package cd.hrpay.setup;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The public demo server: it wipes its database at each start, loads the sample company again, and stops every night
 * so its restart policy starts it fresh. Refuses to run without the sample data, so it cannot be turned on by mistake
 * on a server with real data and an empty sample setting.
 */
@Configuration
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
public class DemoMode {

    private static final Logger log = LoggerFactory.getLogger(DemoMode.class);

    private final ApplicationContext context;

    public DemoMode(ApplicationContext context, @Value("${app.sample-data.enabled:false}") boolean sample) {
        if (!sample) {
            throw new IllegalStateException("DEMO_MODE wipes the database at each start: it needs SAMPLE_DATA=true and a server of its own.");
        }
        this.context = context;
        log.warn("Demo mode: the database is wiped at each start and the app restarts every night.");
    }

    @Bean
    FlywayConfigurationCustomizer demoCanClean() {
        return configuration -> configuration.cleanDisabled(false);
    }

    @Bean
    FlywayMigrationStrategy cleanThenMigrate() {
        return (Flyway flyway) -> {
            flyway.clean();
            flyway.migrate();
        };
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Africa/Kinshasa")
    public void nightlyFreshStart() {
        log.warn("Demo mode: stopping for the nightly fresh start.");
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
