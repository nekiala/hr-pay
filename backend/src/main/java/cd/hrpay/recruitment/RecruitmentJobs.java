package cd.hrpay.recruitment;

import cd.hrpay.ops.JobLocks;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every morning: deletes candidate files past their keeping date, and reminds HR to declare open vacancies to ONEM. */
@Component
public class RecruitmentJobs {

    private static final Logger log = LoggerFactory.getLogger(RecruitmentJobs.class);

    private final JobLocks jobs;
    private final Recruitment recruitment;

    public RecruitmentJobs(JobLocks jobs, Recruitment recruitment) {
        this.jobs = jobs;
        this.recruitment = recruitment;
    }

    @Scheduled(cron = "${app.recruitment.cron:0 35 6 * * *}", zone = "Africa/Kinshasa")
    public void scheduled() {
        jobs.runForEachCompany("recruitment.daily", Duration.ofMinutes(30), this::run);
    }

    public void run() {
        int deleted = recruitment.purge();
        int reminded = recruitment.remindOnem();
        if (deleted > 0 || reminded > 0) {
            log.info("Recruitment: {} candidate files deleted, {} vacancies to declare to ONEM", deleted, reminded);
        }
    }
}
