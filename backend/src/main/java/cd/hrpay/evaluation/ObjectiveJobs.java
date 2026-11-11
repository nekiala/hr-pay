package cd.hrpay.evaluation;

import cd.hrpay.ops.JobLocks;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every morning from July: reminds managers once a year of objectives with no mid-year check-in. */
@Component
public class ObjectiveJobs {

    private static final Logger log = LoggerFactory.getLogger(ObjectiveJobs.class);

    private final JobLocks jobs;
    private final Objectives objectives;

    public ObjectiveJobs(JobLocks jobs, Objectives objectives) {
        this.jobs = jobs;
        this.objectives = objectives;
    }

    @Scheduled(cron = "${app.objectives.cron:0 25 6 * * *}", zone = "Africa/Kinshasa")
    public void scheduled() {
        jobs.runForEachCompany("objectives.daily", Duration.ofMinutes(30), this::run);
    }

    public void run() {
        int n = objectives.remindCheckins();
        if (n > 0) {
            log.info("Objectives: {} managers reminded of mid-year check-ins", n);
        }
    }
}
