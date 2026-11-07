package cd.hrpay.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Blocks an email address for 15 minutes after 5 failed password attempts within 15 minutes. */
@Component
public class LoginAttempts {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttempts() {
        this(Clock.systemUTC());
    }

    LoginAttempts(Clock clock) {
        this.clock = clock;
    }

    public boolean isBlocked(String email) {
        Deque<Instant> list = failures.get(key(email));
        if (list == null) {
            return false;
        }
        synchronized (list) {
            prune(list);
            return list.size() >= MAX_FAILURES;
        }
    }

    public void failed(String email) {
        Deque<Instant> list = failures.computeIfAbsent(key(email), k -> new ArrayDeque<>());
        synchronized (list) {
            prune(list);
            list.addLast(clock.instant());
        }
    }

    public void succeeded(String email) {
        failures.remove(key(email));
    }

    private void prune(Deque<Instant> list) {
        Instant limit = clock.instant().minus(WINDOW);
        while (!list.isEmpty() && list.peekFirst().isBefore(limit)) {
            list.removeFirst();
        }
    }

    private static String key(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
