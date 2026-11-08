package cd.hrpay.job;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.NotificationService;
import cd.hrpay.notify.Notifications;
import cd.hrpay.ops.ErrorAlertFilter;
import cd.hrpay.ops.SystemAlerts;
import cd.hrpay.security.Authorities;
import cd.hrpay.tenancy.Tenants;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The threads that run background jobs, on every server unless APP_ROLE=web; a server started with APP_ROLE=worker
 * only runs jobs (and still answers its health check). A worker takes the oldest waiting job with
 * {@code FOR UPDATE SKIP LOCKED}, so two servers never take the same one. While a job runs, its server writes a
 * heartbeat; a job whose heartbeat stops (the server died) is put back in the queue, at most three times. On shutdown a
 * server takes no new job and waits for its running ones.
 */
@Component
public class JobWorkers implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobWorkers.class);

    /** Unique per running app, so a job is never finished by a server that no longer owns it. */
    private static final String ME = ManagementFactory.getRuntimeMXBean().getName() + "/" + UUID.randomUUID().toString().substring(0, 8);

    record Claimed(long id, long companyId, String type, String params, String permission, String language,
                   Long requestedBy, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;
    private final JobTypes types;
    private final JsonMapper json;
    private final Tenants tenants;
    private final UserRepository users;
    private final SystemAlerts alerts;
    private final ObjectProvider<Notifications> notifications;
    private final ObjectProvider<NotificationService> messages;
    private final TransactionTemplate tx;
    private final TransactionTemplate separate;

    private final int threads;
    private final Duration poll;
    private final Duration heartbeat;
    private final Duration staleAfter;
    private final Duration inlineWait;
    private final Duration emailAfter;
    private final Duration alertWait;
    private final Duration shutdownWait;

    private final BlockingQueue<Long> hints = new LinkedBlockingQueue<>();
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private volatile boolean active;
    private ExecutorService pool;
    private ScheduledExecutorService ticker;

    public JobWorkers(JdbcClient jdbc, JobTypes types, JsonMapper json, Tenants tenants, UserRepository users,
                      SystemAlerts alerts, ObjectProvider<Notifications> notifications,
                      ObjectProvider<NotificationService> messages, PlatformTransactionManager transactions,
                      @Value("${app.role:all}") String role,
                      @Value("${app.jobs.workers:2}") int threads,
                      @Value("${app.jobs.poll:PT2S}") Duration poll,
                      @Value("${app.jobs.heartbeat:PT15S}") Duration heartbeat,
                      @Value("${app.jobs.stale-after:PT2M}") Duration staleAfter,
                      @Value("${app.jobs.inline-wait:PT3S}") Duration inlineWait,
                      @Value("${app.jobs.email-after:PT2M}") Duration emailAfter,
                      @Value("${app.jobs.alert-wait:PT10M}") Duration alertWait,
                      @Value("${app.jobs.shutdown-wait:PT60S}") Duration shutdownWait) {
        this.jdbc = jdbc;
        this.types = types;
        this.json = json;
        this.tenants = tenants;
        this.users = users;
        this.alerts = alerts;
        this.notifications = notifications;
        this.messages = messages;
        this.tx = new TransactionTemplate(transactions);
        this.separate = new TransactionTemplate(transactions);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.threads = "web".equalsIgnoreCase(role.trim()) ? 0 : Math.max(0, threads);
        this.poll = poll;
        this.heartbeat = heartbeat;
        this.staleAfter = staleAfter;
        this.inlineWait = inlineWait;
        this.emailAfter = emailAfter;
        this.alertWait = alertWait;
        this.shutdownWait = shutdownWait;
    }

    public int threads() {
        return threads;
    }

    /** A new job is in the queue: a free worker of this server takes it now rather than at its next look. */
    void wake(long id) {
        if (threads > 0 && active) {
            hints.offer(id);
        }
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------

    @Override
    public void start() {
        active = true;
        ticker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("jobs-heartbeat").daemon().factory());
        long beat = Math.max(1, heartbeat.toMillis());
        ticker.scheduleWithFixedDelay(this::tick, beat, beat, TimeUnit.MILLISECONDS);
        if (threads > 0) {
            pool = Executors.newFixedThreadPool(threads, Thread.ofPlatform().name("job-worker-", 1).factory());
            for (int i = 0; i < threads; i++) {
                pool.submit(this::loop);
            }
            log.info("{} background job worker(s) started ({})", threads, ME);
        }
    }

    @Override
    public void stop() {
        active = false;
        if (pool != null) {
            pool.shutdown();
            // Wake the idle workers so they see the stop.
            for (int i = 0; i < threads; i++) {
                hints.offer(-1L);
            }
            try {
                if (!pool.awaitTermination(shutdownWait.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("Background jobs {} still running at shutdown: they will run again on another server", running);
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (ticker != null) {
            ticker.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return active;
    }

    /** Stopped before the web server and the database pool, started after them. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }

    // ---- the worker loop -----------------------------------------------------------------------------------------

    private void loop() {
        while (active) {
            Long hint;
            try {
                hint = hints.poll(poll.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (!active) {
                return;
            }
            try {
                // After one job, look for the next at once: the queue may hold more.
                boolean worked = runNext(hint != null && hint > 0 ? hint : null);
                while (worked && active) {
                    worked = runNext(null);
                }
            } catch (RuntimeException e) {
                log.error("Background job worker error", e);
            }
        }
    }

    /** Runs the given waiting job, or the oldest waiting one; false when there was none to take. */
    boolean runNext(Long preferred) {
        Claimed c = claim(preferred);
        if (c == null && preferred != null) {
            c = claim(null);
        }
        if (c == null) {
            return false;
        }
        execute(c);
        return true;
    }

    private Claimed claim(Long preferred) {
        return tenants.callAsPlatform(() -> jdbc.sql("""
                update background_job set status = 'RUNNING', started_at = now(), heartbeat_at = now(), worker = :me,
                       attempts = attempts + 1, progress = 0, step = null
                where id = (select id from background_job where status = 'WAITING' and (cast(:id as bigint) is null or id = cast(:id as bigint))
                            order by created_at, id for update skip locked limit 1)
                returning id, company_id, type, params::text, permission, language, requested_by, created_at""")
                .param("me", ME).param("id", preferred)
                .query((rs, i) -> new Claimed(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), (Long) rs.getObject(7), rs.getObject(8, OffsetDateTime.class)))
                .optional().orElse(null));
    }

    private void execute(Claimed c) {
        running.add(c.id());
        try {
            tenants.runAs(c.companyId(), () -> runInCompany(c));
        } catch (RuntimeException e) {
            log.error("Background job {} could not be finished", c.id(), e);
        } finally {
            running.remove(c.id());
        }
    }

    private void runInCompany(Claimed c) {
        SecurityContext before = SecurityContextHolder.getContext();
        Object result;
        try {
            AppUser user = c.requestedBy() == null ? null : users.findById(c.requestedBy()).orElse(null);
            if (user == null || !user.isActive()) {
                throw new AccessDeniedException("The person who started the job can no longer sign in.");
            }
            UsernamePasswordAuthenticationToken auth = Authorities.token(user);
            if (c.permission() != null && auth.getAuthorities().stream().noneMatch(a -> c.permission().equals(a.getAuthority()))) {
                throw new AccessDeniedException("The person who started the job no longer has the right to do it.");
            }
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            SecurityContextHolder.setContext(ctx);
            JobTypes.Type type = types.get(c.type());
            JobFile input = file(c.id());
            JobContext job = new JobContext(c.id(), c.type(), json.readTree(c.params()), input);
            Progress.bind(new Sink(c.id()));
            try {
                result = Texts.in(c.language(), () -> type.work().apply(job));
            } finally {
                Progress.bind(null);
            }
        } catch (RuntimeException e) {
            fail(c, e);
            return;
        } finally {
            SecurityContextHolder.setContext(before);
        }
        finish(c, result);
    }

    private JobFile file(long id) {
        return jdbc.sql("select file_name, content_type, data from background_job_file where job_id = ? and role = 'INPUT'")
                .param(id).query((rs, i) -> new JobFile(rs.getString(1), rs.getString(2), rs.getBytes(3))).optional().orElse(null);
    }

    private void finish(Claimed c, Object result) {
        Integer n = tx.execute(s -> {
            String answer;
            if (result instanceof JobFile f) {
                jdbc.sql("delete from background_job_file where job_id = ? and role = 'RESULT'").param(c.id()).update();
                jdbc.sql("""
                        insert into background_job_file (job_id, role, file_name, content_type, size_bytes, data)
                        values (?, 'RESULT', ?, ?, ?, ?)""")
                        .params(c.id(), BackgroundJobs.cut(f.name(), 200), f.contentType(), f.data().length, f.data()).update();
                answer = json.writeValueAsString(Map.of("jobId", c.id(), "fileName", f.name(), "size", f.data().length,
                        "download", "/api/jobs/" + c.id() + "/file"));
            } else {
                answer = json.writeValueAsString(result);
            }
            return jdbc.sql("""
                    update background_job set status = 'DONE', progress = 100, finished_at = now(), result = cast(? as jsonb),
                           error = null, error_status = null
                    where id = ? and status = 'RUNNING' and worker = ?""")
                    .params(answer, c.id(), ME).update();
        });
        if (n == null || n == 0) {
            log.warn("Background job {} ended here but was taken back from this server meanwhile", c.id());
            return;
        }
        tell(c, true);
    }

    private void fail(Claimed c, RuntimeException e) {
        int status = statusOf(e);
        String message = e instanceof AccessDeniedException || e instanceof AuthorizationDeniedException
                ? (e.getMessage() != null && e.getMessage().startsWith("The person") ? e.getMessage() : "You do not have permission to do this.")
                : e instanceof ResponseStatusException r ? r.getReason() : e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        if (status == 500) {
            log.error("Background job {} ({}) failed", c.id(), c.type(), e);
            alerts.raise("JOB_FAILED", c.type(), "The background job \"" + c.type() + "\" (#" + c.id() + ") failed: " + message,
                    ErrorAlertFilter.trace(e));
        }
        String m = message;
        int updated = jdbc.sql("""
                update background_job set status = 'FAILED', finished_at = now(), error = ?, error_status = ?
                where id = ? and status = 'RUNNING' and worker = ?""")
                .params(BackgroundJobs.cut(m, 2000), status, c.id(), ME).update();
        if (updated > 0) {
            tell(c, false);
        }
    }

    static int statusOf(Throwable e) {
        if (e instanceof BusinessRuleException) {
            return 422;
        }
        if (e instanceof AccessDeniedException || e instanceof AuthorizationDeniedException) {
            return 403;
        }
        if (e instanceof NotFoundException) {
            return 404;
        }
        if (e instanceof ConflictException) {
            return 409;
        }
        if (e instanceof ResponseStatusException r) {
            return r.getStatusCode().value();
        }
        return 500;
    }

    /**
     * Tells the requester in the bell (and by email for a long job), unless their page got the answer while waiting:
     * a job that ended within the page's wait is not announced.
     */
    private void tell(Claimed c, boolean done) {
        if (c.requestedBy() == null) {
            return;
        }
        try {
            Duration took = Duration.between(c.createdAt(), OffsetDateTime.now());
            if (took.compareTo(inlineWait.plusSeconds(2)) <= 0) {
                return;
            }
            int marked = jdbc.sql("update background_job set notified_at = now() where id = ? and notified_at is null")
                    .param(c.id()).update();
            if (marked == 0) {
                return;
            }
            JobTypes.Type type = types.get(c.type());
            var params = json.readTree(c.params());
            java.util.function.Supplier<String> title = () -> done
                    ? Texts.t("Finished: %s", type.title().apply(params))
                    : Texts.t("Failed: %s", type.title().apply(params));
            java.util.function.Supplier<String> body = () -> done
                    ? Texts.t("The job you started is done. Open My jobs to see it.")
                    : Texts.t("The job you started stopped with an error. Open My jobs to see why and start it again.");
            notifications.getObject().send(c.requestedBy(), done ? "JOB_DONE" : "JOB_FAILED", title, body, "/jobs");
            if (took.compareTo(emailAfter) > 0) {
                users.findById(c.requestedBy()).filter(AppUser::isActive).ifPresent(u -> messages.getObject().email(u, title, body));
            }
        } catch (RuntimeException e) {
            log.warn("Could not tell the requester of job {}: {}", c.id(), e.getMessage());
        }
    }

    // ---- progress, heartbeat and the jobs of servers that stopped ----------------------------------------------

    /** Writes a job's progress at most once a second, in a transaction of its own (the job's may last minutes). */
    private final class Sink implements Progress.Sink {
        private final long id;
        private long lastWrite;
        private int lastPercent = -1;

        Sink(long id) {
            this.id = id;
        }

        @Override
        public void report(long done, long total, String step) {
            int percent = total > 0 ? (int) Math.min(99, Math.max(0, done * 100 / total)) : lastPercent;
            long now = System.currentTimeMillis();
            if (now - lastWrite < 1000 && !(total > 0 && done >= total)) {
                return;
            }
            lastWrite = now;
            int p = Math.max(percent, 0);
            lastPercent = p;
            try {
                separate.executeWithoutResult(s -> jdbc.sql("""
                        update background_job set progress = greatest(progress, ?), step = ?, heartbeat_at = now()
                        where id = ? and status = 'RUNNING'""").params(p, BackgroundJobs.cut(step, 200), id).update());
            } catch (RuntimeException e) {
                log.debug("Progress of job {} not written: {}", id, e.getMessage());
            }
        }
    }

    /** Every few seconds: this server's heartbeat, then the jobs of servers that stopped, and long waits. */
    private void tick() {
        try {
            tenants.callAsPlatform(() -> {
                if (!running.isEmpty()) {
                    jdbc.sql("update background_job set heartbeat_at = now() where worker = :me and status = 'RUNNING' and id in (:ids)")
                            .param("me", ME).param("ids", java.util.List.copyOf(running)).update();
                }
                recoverStale();
                alertLongWaits();
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Background job heartbeat failed: {}", e.getMessage());
        }
    }

    /** Jobs whose server stopped beating: back in the queue, or failed after too many tries. Their work was rolled back. */
    public int recoverStale() {
        var rows = jdbc.sql("""
                update background_job
                set status = case when attempts >= max_attempts then 'FAILED' else 'WAITING' end,
                    finished_at = case when attempts >= max_attempts then now() end,
                    error = case when attempts >= max_attempts then 'The server stopped during the job, several times.' end,
                    error_status = case when attempts >= max_attempts then 500 end,
                    worker = null, step = null, progress = 0
                where status = 'RUNNING' and heartbeat_at < now() - make_interval(secs => :secs)
                returning id, status""")
                .param("secs", staleAfter.toSeconds())
                .query((rs, i) -> Map.entry(rs.getLong(1), rs.getString(2))).list();
        for (var r : rows) {
            log.warn("Background job {} lost its server: {}", r.getKey(), r.getValue().equals("WAITING") ? "queued again" : "failed");
            if (r.getValue().equals("WAITING")) {
                wake(r.getKey());
            } else {
                alerts.raise("JOB_FAILED", "stale", "Background job #" + r.getKey() + " failed: its server stopped several times.", null);
            }
        }
        return rows.size();
    }

    private void alertLongWaits() {
        var late = jdbc.sql("""
                update background_job set alerted_at = now()
                where status = 'WAITING' and alerted_at is null and created_at < now() - make_interval(secs => :secs)
                returning id, type""")
                .param("secs", alertWait.toSeconds()).query((rs, i) -> rs.getLong(1) + " (" + rs.getString(2) + ")").list();
        if (!late.isEmpty()) {
            alerts.raise("JOB_WAITING", "queue", "Background jobs wait more than " + alertWait.toMinutes()
                    + " minutes: " + String.join(", ", late) + ". Are the workers running (APP_ROLE, JOBS_WORKERS)?", null);
        }
    }
}
