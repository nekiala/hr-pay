package cd.hrpay.training;

import cd.hrpay.ops.JobLocks;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every morning: asks managers about the effect of their team's training, and reminds HR of apprentices to follow. */
@Component
public class TrainingJobs {

    private static final Logger log = LoggerFactory.getLogger(TrainingJobs.class);

    private final JobLocks jobs;
    private final Training training;
    private final Apprentices apprentices;

    public TrainingJobs(JobLocks jobs, Training training, Apprentices apprentices) {
        this.jobs = jobs;
        this.training = training;
        this.apprentices = apprentices;
    }

    @Scheduled(cron = "${app.training.cron:0 40 6 * * *}", zone = "Africa/Kinshasa")
    public void scheduled() {
        jobs.runForEachCompany("training.daily", Duration.ofMinutes(30), this::run);
    }

    public void run() {
        int effects = training.remind();
        int followed = apprentices.remind();
        if (effects > 0 || followed > 0) {
            log.info("Training: {} effect checks asked, {} apprentices to follow", effects, followed);
        }
    }
}
