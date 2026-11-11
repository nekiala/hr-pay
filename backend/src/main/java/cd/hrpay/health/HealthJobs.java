package cd.hrpay.health;

import cd.hrpay.ops.JobLocks;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every morning: return-to-work visits planned after long absences, medical visits due, accident declarations, the
 * committee's meetings and inspection walks, and the yearly reports of the first quarter.
 */
@Component
public class HealthJobs {

    private static final Logger log = LoggerFactory.getLogger(HealthJobs.class);

    private final JobLocks jobs;
    private final MedicalVisits visits;
    private final WorkAccidents accidents;
    private final SafetyCommittee committee;

    public HealthJobs(JobLocks jobs, MedicalVisits visits, WorkAccidents accidents, SafetyCommittee committee) {
        this.jobs = jobs;
        this.visits = visits;
        this.accidents = accidents;
        this.committee = committee;
    }

    @Scheduled(cron = "${app.health.cron:0 15 6 * * *}", zone = "Africa/Kinshasa")
    public void scheduled() {
        jobs.runForEachCompany("health.daily", Duration.ofMinutes(30), this::run);
    }

    public void run() {
        int medical = visits.run();
        int declared = accidents.run();
        int safety = committee.run();
        if (medical + declared + safety > 0) {
            log.info("Health and safety: {} medical reminders, {} accident reminders, {} committee reminders", medical, declared, safety);
        }
    }
}
