package cd.hrpay.job;

/** A file a job makes (or is given): kept with the job, the result downloaded from the My jobs page for 7 days. */
public record JobFile(String name, String contentType, byte[] data) {}
