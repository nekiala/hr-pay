package cd.hrpay.integration;

import cd.hrpay.attendance.ClockEvent;
import cd.hrpay.attendance.ClockIngest;
import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.site.IpRange;
import cd.hrpay.tenancy.Tenants;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserRepository;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Biometric time clocks (ZKTeco and compatible) that push their punches over HTTP, the "ADMS" or "push" protocol
 * set in the clock's Cloud server menu. HR registers each clock by its serial number and site; the clock then
 * calls {@code /iclock/cdata} with its punches, which become check-ins and check-outs of the person enrolled under
 * that number.
 */
@Service
public class TimeClocks {

    private static final Logger log = LoggerFactory.getLogger(TimeClocks.class);

    public record Device(long id, long companyId, String serialNo, String name, long siteId, String siteName, String timeZone,
                         boolean active, String allowedIps, boolean useStatus, String attlogStamp, OffsetDateTime lastSeenAt,
                         String lastIp) {}

    public record Punch(long id, String device, String pin, LocalDateTime punchedAt, String person, String outcome,
                        String detail, OffsetDateTime receivedAt) {}

    private static final String DEVICE = """
            select d.id, d.company_id, d.serial_no, d.name, d.site_id, s.name as site_name, s.time_zone, d.active, d.allowed_ips,
                   d.use_status, d.attlog_stamp, d.last_seen_at, d.last_ip
            from biometric_device d join site s on s.id = d.site_id""";

    private final JdbcClient jdbc;
    private final Tenants tenants;
    private final UserRepository users;
    private final ClockIngest ingest;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public TimeClocks(JdbcClient jdbc, Tenants tenants, UserRepository users, ClockIngest ingest, AuditService audit,
                      PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.users = users;
        this.ingest = ingest;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
    }

    // ---- managed by HR ----------------------------------------------------------------------------------------

    public List<Device> devices() {
        return jdbc.sql(DEVICE + " order by d.name").query(Device.class).list();
    }

    @Transactional
    public Device save(Long id, String serial, String name, long siteId, boolean active, String allowedIps, boolean useStatus) {
        String sn = serial.trim().toUpperCase();
        if (!sn.matches("[A-Z0-9-]{4,40}")) {
            throw new BusinessRuleException("Enter the serial number as shown on the clock (letters and digits).");
        }
        String ips = allowedIps == null || allowedIps.isBlank() ? null : allowedIps.trim();
        if (ips != null) {
            for (String r : ips.split("[,\\s]+")) {
                IpRange.parse(r);
            }
        }
        Long owner = tenants.callAsPlatform(() -> jdbc.sql("select company_id from biometric_device where serial_no = ? and id <> ?")
                .params(sn, id == null ? -1 : id).query(Long.class).optional().orElse(null));
        if (owner != null) {
            throw new ConflictException("A clock with this serial number is already registered.");
        }
        long saved;
        if (id == null) {
            saved = jdbc.sql("""
                    insert into biometric_device (serial_no, name, site_id, active, allowed_ips, use_status, created_by)
                    values (?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(sn, name.trim(), siteId, active, ips, useStatus, CurrentUser.email()).query(Long.class).single();
        } else {
            find(id);
            jdbc.sql("update biometric_device set serial_no = ?, name = ?, site_id = ?, active = ?, allowed_ips = ?, use_status = ? where id = ?")
                    .params(sn, name.trim(), siteId, active, ips, useStatus, id).update();
            saved = id;
        }
        audit.record("time_clock.saved", "biometric_device", saved, Map.of("serial", sn, "site", siteId, "active", active));
        return find(saved);
    }

    public Device find(long id) {
        return jdbc.sql(DEVICE + " where d.id = ?").param(id).query(Device.class).optional()
                .orElseThrow(() -> new NotFoundException("Time clock not found."));
    }

    @Transactional
    public void delete(long id) {
        find(id);
        jdbc.sql("delete from biometric_device where id = ?").param(id).update();
        audit.record("time_clock.removed", "biometric_device", id, Map.of());
    }

    public List<Punch> punches(Long deviceId, String outcome) {
        StringBuilder sql = new StringBuilder("""
                select p.id, d.name as device, p.pin, p.punched_at, u.first_name || ' ' || u.last_name as person, p.outcome,
                       p.detail, p.received_at
                from biometric_punch p join biometric_device d on d.id = p.device_id left join app_user u on u.id = p.user_id
                where true""");
        List<Object> params = new ArrayList<>();
        if (deviceId != null) {
            sql.append(" and p.device_id = ?");
            params.add(deviceId);
        }
        if (outcome != null && !outcome.isBlank()) {
            sql.append(" and p.outcome = ?");
            params.add(outcome);
        }
        sql.append(" order by p.received_at desc, p.id desc limit 300");
        return jdbc.sql(sql.toString()).params(params).query(Punch.class).list();
    }

    // ---- the clock's side -------------------------------------------------------------------------------------

    /** The clock by its serial number, whatever its company; empty for an unknown or disabled clock or a wrong address. */
    public Optional<Device> clock(String serial, String ip) {
        if (serial == null || serial.isBlank()) {
            return Optional.empty();
        }
        Optional<Device> d = tenants.callAsPlatform(() -> jdbc.sql(DEVICE + " where d.serial_no = ?")
                .param(serial.trim().toUpperCase()).query(Device.class).optional());
        if (d.isEmpty()) {
            log.info("Time clock {} from {} is not registered", serial, ip);
            return Optional.empty();
        }
        Device device = d.get();
        if (device.allowedIps() != null && (ip == null
                || Arrays.stream(device.allowedIps().split("[,\\s]+")).noneMatch(r -> IpRange.parse(r).contains(ip)))) {
            log.warn("Time clock {} called from {}, outside its allowed addresses", serial, ip);
            return Optional.empty();
        }
        tenants.runAs(device.companyId(), () -> jdbc.sql("update biometric_device set last_seen_at = now(), last_ip = ? where id = ?")
                .params(ip, device.id()).update());
        return device.active() ? Optional.of(device) : Optional.empty();
    }

    /** The options the clock asks for when it starts: send every new punch at once. */
    public String options(Device d) {
        return String.join("\n",
                "GET OPTION FROM: " + d.serialNo(),
                "ATTLOGStamp=" + (d.attlogStamp() == null ? "None" : d.attlogStamp()),
                "OPERLOGStamp=9999",
                "ATTPHOTOStamp=None",
                "ErrorDelay=60",
                "Delay=30",
                "TransTimes=00:00;14:05",
                "TransInterval=1",
                "TransFlag=TransData AttLog",
                "Realtime=1",
                "Encrypt=None",
                "");
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** The punches of an ATTLOG upload, one per line: number, time, status, how it was verified... Returns how many lines. */
    public int attendance(Device d, String body, String stamp) {
        String[] lines = body == null ? new String[0] : body.split("\\r?\\n");
        int n = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            n++;
            String[] f = line.split("\\t");
            if (f.length < 2) {
                continue;
            }
            String pin = f[0].trim();
            LocalDateTime at;
            try {
                at = LocalDateTime.parse(f[1].trim(), TIME);
            } catch (DateTimeParseException e) {
                log.warn("Time clock {} sent a punch with an unreadable time: {}", d.serialNo(), line);
                continue;
            }
            Integer status = f.length > 2 ? integer(f[2]) : null;
            Integer verify = f.length > 3 ? integer(f[3]) : null;
            try {
                tenants.runAs(d.companyId(), () -> tx.executeWithoutResult(s -> punch(d, pin, at, status, verify)));
            } catch (RuntimeException e) {
                log.warn("Time clock {}: punch {} at {} failed: {}", d.serialNo(), pin, at, e.getMessage());
            }
        }
        if (stamp != null && stamp.matches("[0-9]{1,20}")) {
            tenants.runAs(d.companyId(), () -> jdbc.sql("update biometric_device set attlog_stamp = ? where id = ?")
                    .params(stamp, d.id()).update());
        }
        return n;
    }

    private static Integer integer(String s) {
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void punch(Device d, String pin, LocalDateTime at, Integer status, Integer verify) {
        Long id = jdbc.sql("""
                insert into biometric_punch (device_id, pin, punched_at, status_code, verify_code, outcome)
                values (?, ?, ?, ?, ?, 'IGNORED') on conflict (device_id, pin, punched_at) do nothing returning id""")
                .params(d.id(), pin, at, status, verify).query(Long.class).optional().orElse(null);
        if (id == null) {
            return;   // sent before
        }
        resolve(d, id, pin, at, status);
    }

    /**
     * Enrols a person under a clock number (the number they have on the clock), then records the punches that
     * came under that number before anyone was known by it. Returns how many such punches were recorded.
     */
    public int link(String pin, String registrationNo) {
        String p = pin == null ? "" : pin.trim();
        if (!p.matches("[0-9A-Za-z]{1,20}")) {
            throw new BusinessRuleException("Enter the number the person has on the clock.");
        }
        AppUser user = users.findByRegistrationNoIgnoreCase(registrationNo == null ? "" : registrationNo.trim())
                .orElseThrow(() -> new BusinessRuleException("No one has the registration number %s.".formatted(registrationNo)));
        tx.executeWithoutResult(s -> {
            jdbc.sql("select id from app_user where clock_pin = ? and id <> ?").params(p, user.getId()).query(Long.class).optional()
                    .ifPresent(other -> {
                        throw new ConflictException("Someone else already has the clock number %s.".formatted(p));
                    });
            jdbc.sql("update app_user set clock_pin = ? where id = ?").params(p, user.getId()).update();
            audit.record("user.clock_pin_set", "user", user.getId(), Map.of("registrationNo", user.getRegistrationNo()));
        });
        record Waiting(long id, long deviceId, LocalDateTime punchedAt, Integer statusCode) {}
        List<Waiting> waiting = jdbc.sql("""
                select id, device_id, punched_at, status_code from biometric_punch
                where pin = ? and outcome = 'UNKNOWN_PIN' order by punched_at""").param(p).query(Waiting.class).list();
        int recorded = 0;
        for (Waiting w : waiting) {
            Device d = find(w.deviceId());
            tx.executeWithoutResult(s -> resolve(d, w.id(), p, w.punchedAt(), w.statusCode()));
            recorded += jdbc.sql("select count(*) from biometric_punch where id = ? and outcome = 'RECORDED'").param(w.id())
                    .query(Integer.class).single();
        }
        return recorded;
    }

    private void resolve(Device d, long id, String pin, LocalDateTime at, Integer status) {
        Optional<Long> userId = jdbc.sql("""
                select id from app_user where clock_pin = :pin
                union all
                select id from app_user where clock_pin is null and (upper(registration_no) = upper(:pin)
                    or (:pin ~ '^[0-9]+$' and ltrim(registration_no, '0') = ltrim(:pin, '0')))
                limit 1""").param("pin", pin).query(Long.class).optional();
        if (userId.isEmpty()) {
            outcome(id, null, null, "UNKNOWN_PIN", "No one is enrolled under this number");
            return;
        }
        AppUser user = users.findById(userId.get()).orElseThrow();
        if (!user.isActive()) {
            outcome(id, user.getId(), null, "IGNORED", "Account disabled");
            return;
        }
        String run = jdbc.sql("select status from payroll_run where month = ?").param(YearMonth.from(at).atDay(1))
                .query(String.class).optional().orElse("OPEN");
        if (run.equals("APPROVED") || run.equals("CLOSED")) {
            outcome(id, user.getId(), null, "IGNORED", "The payroll of that month is approved");
            return;
        }
        ClockEvent.Type type = null;
        if (d.useStatus() && status != null) {
            type = switch (status) {
                case 0, 4 -> ClockEvent.Type.IN;
                case 1, 5 -> ClockEvent.Type.OUT;
                default -> null;
            };
        }
        OffsetDateTime when = at.atZone(ZoneId.of(d.timeZone())).toOffsetDateTime();
        ClockIngest.Outcome o = ingest.record(user, type, when, ClockEvent.Source.BIOMETRIC, d.siteId(), d.name(),
                "clock:" + d.serialNo());
        if (o.event() != null) {
            outcome(id, user.getId(), o.event().getId(), "RECORDED", o.event().getType().name());
        } else {
            outcome(id, user.getId(), null, "IGNORED", o.ignored());
        }
    }

    private void outcome(long id, Long userId, Long eventId, String outcome, String detail) {
        jdbc.sql("update biometric_punch set user_id = ?, clock_event_id = ?, outcome = ?, detail = ? where id = ?")
                .params(userId, eventId, outcome, detail, id).update();
    }
}
