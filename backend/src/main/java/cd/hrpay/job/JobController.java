package cd.hrpay.job;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** My jobs: the long tasks a person started, their progress, and the files they made. */
@RestController
@PreAuthorize("isAuthenticated()")
public class JobController {

    private final BackgroundJobs jobs;

    public JobController(BackgroundJobs jobs) {
        this.jobs = jobs;
    }

    @GetMapping("/api/jobs")
    public List<BackgroundJobs.Job> mine() {
        return jobs.mine();
    }

    @GetMapping("/api/jobs/{id}")
    public BackgroundJobs.Job job(@PathVariable long id) {
        return jobs.view(id);
    }

    @PostMapping("/api/jobs/{id}/cancel")
    public BackgroundJobs.Job cancel(@PathVariable long id) {
        return jobs.cancel(id);
    }

    @PostMapping("/api/jobs/{id}/retry")
    public BackgroundJobs.Job retry(@PathVariable long id) {
        return jobs.retry(id);
    }

    @GetMapping("/api/jobs/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable long id) {
        JobFile f = jobs.download(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .header("Content-Disposition", ContentDisposition.attachment().filename(f.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(f.data());
    }

    /** The queue across every company, for the installation's administrators. */
    @GetMapping("/api/system/jobs")
    @PreAuthorize("hasAuthority('system.monitor')")
    public BackgroundJobs.Queue queue() {
        return jobs.queue();
    }
}
