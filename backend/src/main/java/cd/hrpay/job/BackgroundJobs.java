package cd.hrpay.job;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.tenancy.Tenants;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Long tasks put in the queue (table background_job) instead of run inside the web request. A page starts a job and
 * waits a few seconds: a small job's answer comes back at once as before; a longer one answers 202 with the job, whose
 * progress the page follows, and the person is told in the bell when it ends. See {@link JobWorkers} for who runs them.
 */
@Service
public class BackgroundJobs {

    /** A job as its requester sees it. {@code result} (the answer, as JSON) is filled only when asked for. */
    public record Job(long id, String type, String title, String status, int progress, String step, String requestedBy,
                      OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime finishedAt, int attempts,
                      String error, Integer errorStatus, String fileName, Long fileSize, boolean existing, JsonNode result) {

        public boolean finished() {
            return status.equals("DONE") || status.equals("FAILED") || status.equals("CANCELLED");
        }
    }

    /** The 202 answer: the job to follow at /api/jobs/{id}. */
    public record Started(Job job) {}

    /** The queue across every company, for the System page. */
    public record Queue(long waiting, long running, long failedToday, long doneToday, Long oldestWaitSeconds,
                        Double averageSeconds, List<Recent> recent) {}

    public record Recent(long id, long companyId, String company, String type, String status, int progress,
                         OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime finishedAt, String worker,
                         String error) {}

    private final JdbcClient jdbc;
    private final JobTypes types;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final AuditService audit;
    private final Tenants tenants;
    private final JobWorkers workers;
    private final Duration inlineWait;

    public BackgroundJobs(JdbcClient jdbc, JobTypes types, JsonMapper json, PlatformTransactionManager transactions,
                          AuditService audit, Tenants tenants, JobWorkers workers,
                          @Value("${app.jobs.inline-wait:PT3S}") Duration inlineWait) {
        this.jdbc = jdbc;
        this.types = types;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
        this.audit = audit;
        this.tenants = tenants;
        this.workers = workers;
        this.inlineWait = inlineWait;
    }

    // ---- starting a job ------------------------------------------------------------------------------------------

    /**
     * Puts a job in the queue for the current company and person. {@code key} names what it works on: while a job
     * with the same key waits or runs, that one is returned instead (marked existing), so no work is done twice.
     */
    public Job submit(String type, String key, Map<String, ?> params, JobFile input) {
        JobTypes.Type t = types.get(type);
        if (t.permission() != null && !CurrentUser.has(t.permission())) {
            throw new AccessDeniedException("Missing " + t.permission());
        }
        String paramsJson = json.writeValueAsString(params == null ? Map.of() : params);
        Long id = tx.execute(s -> {
            Long created = jdbc.sql("""
                    insert into background_job (type, job_key, params, permission, language, requested_by, requested_by_email)
                    values (?, ?, cast(? as jsonb), ?, ?, ?, ?)
                    on conflict (company_id, job_key) where job_key is not null and status in ('WAITING', 'RUNNING')
                    do nothing returning id""")
                    .params(type, key, paramsJson, t.permission(), Texts.current(), CurrentUser.id(), CurrentUser.email())
                    .query(Long.class).optional().orElse(null);
            if (created != null && input != null) {
                jdbc.sql("""
                        insert into background_job_file (job_id, role, file_name, content_type, size_bytes, data)
                        values (?, 'INPUT', ?, ?, ?, ?)""")
                        .params(created, cut(input.name() == null ? "file" : input.name(), 200), input.contentType(),
                                input.data().length, input.data()).update();
            }
            return created;
        });
        if (id == null) {
            Job open = jdbc.sql(SELECT + " where j.job_key = ? and j.status in ('WAITING', 'RUNNING') order by j.id limit 1")
                    .param(key).query((rs, i) -> row(rs, true, false)).optional().orElse(null);
            if (open == null) {
                // It ended between the two statements: start it again.
                return submit(type, key, params, input);
            }
            return open;
        }
        audit.record("job.started", "background_job", id, Map.of("type", type, "key", key == null ? "" : key));
        wake(id);
        return require(id, false);
    }

    /** Tells this server's workers about a new job, once it is committed. */
    private void wake(long id) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    workers.wake(id);
                }
            });
        } else {
            workers.wake(id);
        }
    }

    /**
     * The web answer for a started job: when it ends within a few seconds, what the page always had (or its error);
     * otherwise 202 with the job. "Prefer: respond-async" asks for the 202 at once.
     */
    public ResponseEntity<Object> answer(Job job) {
        return answer(job, preferAsync());
    }

    public ResponseEntity<Object> answer(Job job, boolean async) {
        Job now = async ? job : await(job.id(), inlineWait);
        if (now.status().equals("DONE") && now.fileName() == null) {
            String result = jdbc.sql("select result::text from background_job where id = ?").param(now.id())
                    .query(String.class).optional().orElse(null);
            jdbc.sql("update background_job set notified_at = coalesce(notified_at, now()) where id = ?").param(now.id()).update();
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header("X-Job-Id", String.valueOf(now.id()))
                    .body(result == null ? "null" : result);
        }
        if (now.status().equals("FAILED") && !async) {
            jdbc.sql("update background_job set notified_at = coalesce(notified_at, now()) where id = ?").param(now.id()).update();
            throw failure(now);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).header("X-Job-Id", String.valueOf(now.id())).body(new Started(now));
    }

    private static boolean preferAsync() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            HttpServletRequest r = a.getRequest();
            String prefer = r.getHeader("Prefer");
            return prefer != null && prefer.toLowerCase(java.util.Locale.ROOT).contains("respond-async");
        }
        return false;
    }

    /** The job once it ends, or as it is when the time is up. */
    Job await(long id, Duration limit) {
        long end = System.nanoTime() + limit.toNanos();
        long pause = 50;
        while (true) {
            Job j = require(id, false);
            if (j.finished() || System.nanoTime() >= end) {
                return j;
            }
            try {
                Thread.sleep(Math.min(pause, Math.max(1, (end - System.nanoTime()) / 1_000_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return j;
            }
            pause = Math.min(250, pause * 2);
        }
    }

    /** The error a failed job's page would have had, had it run in the request. */
    static RuntimeException failure(Job j) {
        String message = j.error() == null ? "The job failed." : j.error();
        int status = j.errorStatus() == null ? 500 : j.errorStatus();
        return switch (status) {
            case 422 -> new BusinessRuleException(message);
            case 403 -> new AccessDeniedException(message);
            case 404 -> new NotFoundException(message);
            case 409 -> new ConflictException(message);
            default -> new ResponseStatusException(HttpStatus.valueOf(status), message);
        };
    }

    /** Refuses a change while a job works on the same thing (approving a payroll being calculated). */
    public void requireIdle(String key) {
        Boolean busy = jdbc.sql("select exists (select 1 from background_job where job_key = ? and status in ('WAITING', 'RUNNING'))")
                .param(key).query(Boolean.class).single();
        if (Boolean.TRUE.equals(busy)) {
            throw new BusinessRuleException("This is being worked on in the background: wait until it ends (My jobs).");
        }
    }

    // ---- the person's jobs ---------------------------------------------------------------------------------------

    private static final String SELECT = """
            select j.id, j.type, j.params::text as params, j.status, j.progress, j.step, j.requested_by_email, j.created_at,
                   j.started_at, j.finished_at, j.attempts, j.error, j.error_status, j.result::text as result,
                   j.permission, j.requested_by, f.file_name, f.size_bytes
            from background_job j left join background_job_file f on f.job_id = j.id and f.role = 'RESULT'""";

    private Job row(java.sql.ResultSet rs, boolean existing, boolean withResult) throws java.sql.SQLException {
        String type = rs.getString("type");
        JsonNode params = json.readTree(rs.getString("params"));
        String title;
        try {
            title = types.has(type) ? types.get(type).title().apply(params) : type;
        } catch (RuntimeException e) {
            title = type;
        }
        String result = withResult ? rs.getString("result") : null;
        return new Job(rs.getLong("id"), type, title, rs.getString("status"), rs.getInt("progress"),
                rs.getString("step") == null ? null : Texts.translate(rs.getString("step")), rs.getString("requested_by_email"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class), rs.getInt("attempts"),
                rs.getString("error") == null ? null : Texts.translate(rs.getString("error")),
                (Integer) rs.getObject("error_status"), rs.getString("file_name"), (Long) rs.getObject("size_bytes"),
                existing, result == null ? null : json.readTree(result));
    }

    private Job require(long id, boolean withResult) {
        return jdbc.sql(SELECT + " where j.id = ?").param(id).query((rs, i) -> row(rs, false, withResult)).optional()
                .orElseThrow(() -> new NotFoundException("Job not found"));
    }

    /** One job, for its requester or a holder of its permission; with its answer once done. */
    public Job view(long id) {
        requireAccess(id);
        return require(id, true);
    }

    private void requireAccess(long id) {
        record Access(Long requestedBy, String permission) {}
        Access a = jdbc.sql("select requested_by, permission from background_job where id = ?").param(id)
                .query((rs, i) -> new Access((Long) rs.getObject(1), rs.getString(2))).optional()
                .orElseThrow(() -> new NotFoundException("Job not found"));
        boolean mine = a.requestedBy() != null && a.requestedBy().equals(CurrentUser.id());
        if (!mine && (a.permission() == null || !CurrentUser.has(a.permission()))) {
            throw new NotFoundException("Job not found");
        }
    }

    /** The person's own jobs of the last 30 days, newest first. */
    public List<Job> mine() {
        return jdbc.sql(SELECT + " where j.requested_by = ? and j.created_at > now() - interval '30 days' order by j.id desc limit 100")
                .param(CurrentUser.id()).query((rs, i) -> row(rs, false, false)).list();
    }

    /** Only a job still waiting can be cancelled; one running finishes its step. */
    public Job cancel(long id) {
        requireAccess(id);
        int n = jdbc.sql("""
                update background_job set status = 'CANCELLED', finished_at = now(), notified_at = now()
                where id = ? and status = 'WAITING'""").param(id).update();
        if (n == 0) {
            throw new BusinessRuleException("Only a job still waiting can be cancelled.");
        }
        audit.record("job.cancelled", "background_job", id, Map.of());
        return require(id, false);
    }

    /** Starts a failed or cancelled job again, as a new job with the same parameters and file. */
    public Job retry(long id) {
        requireAccess(id);
        record Old(String type, String key, String params, String status) {}
        Old old = jdbc.sql("select type, job_key, params::text, status from background_job where id = ?").param(id)
                .query((rs, i) -> new Old(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).single();
        if (!old.status().equals("FAILED") && !old.status().equals("CANCELLED")) {
            throw new BusinessRuleException("Only a failed or cancelled job can be started again.");
        }
        JobFile input = file(id, "INPUT");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = json.readValue(old.params(), Map.class);
        return submit(old.type(), old.key(), params, input);
    }

    /** The file the job made; every download is audited. */
    public JobFile download(long id) {
        requireAccess(id);
        JobFile f = file(id, "RESULT");
        if (f == null) {
            throw new NotFoundException("This job's file is no longer kept (files are deleted after 7 days).");
        }
        audit.record("job.file_downloaded", "background_job", id, Map.of("file", f.name()));
        return f;
    }

    JobFile file(long id, String role) {
        return jdbc.sql("select file_name, content_type, data from background_job_file where job_id = ? and role = ?")
                .params(id, role).query((rs, i) -> new JobFile(rs.getString(1), rs.getString(2), rs.getBytes(3)))
                .optional().orElse(null);
    }

    // ---- the queue, for the System page --------------------------------------------------------------------------

    public Queue queue() {
        return tenants.callAsPlatform(() -> {
            record Counts(long waiting, long running, long failed, long done, Long oldest, Double average) {}
            Counts c = jdbc.sql("""
                    select count(*) filter (where status = 'WAITING'),
                           count(*) filter (where status = 'RUNNING'),
                           count(*) filter (where status = 'FAILED' and finished_at > now() - interval '24 hours'),
                           count(*) filter (where status = 'DONE' and finished_at > now() - interval '24 hours'),
                           extract(epoch from now() - min(created_at) filter (where status = 'WAITING'))::bigint,
                           avg(extract(epoch from finished_at - started_at)) filter (where status = 'DONE'
                               and finished_at > now() - interval '24 hours')
                    from background_job where status in ('WAITING', 'RUNNING') or finished_at > now() - interval '24 hours'""")
                    .query((rs, i) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                            (Long) rs.getObject(5), rs.getObject(6) == null ? null : rs.getDouble(6))).single();
            List<Recent> recent = jdbc.sql("""
                    select j.id, j.company_id, c.name, j.type, j.status, j.progress, j.created_at, j.started_at, j.finished_at,
                           j.worker, j.error
                    from background_job j join company c on c.id = j.company_id order by j.id desc limit 20""")
                    .query((rs, i) -> new Recent(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getInt(6), rs.getObject(7, OffsetDateTime.class),
                            rs.getObject(8, OffsetDateTime.class), rs.getObject(9, OffsetDateTime.class), rs.getString(10),
                            rs.getString(11)))
                    .list();
            return new Queue(c.waiting(), c.running(), c.failed(), c.done(), c.oldest(), c.average(), recent);
        });
    }

    static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
