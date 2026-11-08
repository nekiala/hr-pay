package cd.hrpay.job;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The kinds of background job: what each runs, the permission it needs and its title on the My jobs page. Each area
 * registers its own (see {@link PayrollJobs}).
 */
@Component
public class JobTypes {

    /**
     * @param permission checked when the job is started and again when it runs; its holders may download the file
     * @param title      the job's title in English (translated when shown), from its parameters
     * @param work       the job itself: returns the answer the page shows (any JSON value) or a {@link JobFile}
     */
    public record Type(String name, String permission, Function<JsonNode, String> title, Function<JobContext, Object> work) {}

    private final Map<String, Type> types = new ConcurrentHashMap<>();

    public void register(String name, String permission, Function<JsonNode, String> title, Function<JobContext, Object> work) {
        if (types.putIfAbsent(name, new Type(name, permission, title, work)) != null) {
            throw new IllegalStateException("Job type " + name + " is registered twice");
        }
    }

    public Type get(String name) {
        Type t = types.get(name);
        if (t == null) {
            throw new IllegalArgumentException("Unknown job type " + name);
        }
        return t;
    }

    public boolean has(String name) {
        return types.containsKey(name);
    }
}
