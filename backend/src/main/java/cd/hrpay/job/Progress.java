package cd.hrpay.job;

/**
 * How far the background job running on this thread has got, for its progress bar. Code deep in a long task (the
 * payroll loop) calls it without knowing whether it runs as a job: outside one it does nothing.
 */
public final class Progress {

    /** Receives the reports of the job running on a thread. */
    interface Sink {
        void report(long done, long total, String step);
    }

    private static final ThreadLocal<Sink> CURRENT = new ThreadLocal<>();

    private Progress() {}

    /** {@code done} of {@code total} items; {@code step} is a short text shown under the bar ("Payslip 1,200 of 5,000"). */
    public static void report(long done, long total, String step) {
        Sink sink = CURRENT.get();
        if (sink != null) {
            sink.report(done, total, step);
        }
    }

    /** A step without a count. */
    public static void step(String step) {
        report(-1, -1, step);
    }

    static void bind(Sink sink) {
        if (sink == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(sink);
        }
    }
}
