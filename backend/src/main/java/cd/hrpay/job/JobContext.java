package cd.hrpay.job;

import tools.jackson.databind.JsonNode;

/** What a job's code gets: its parameters and input file. It runs in the company, language and rights of its requester. */
public record JobContext(long id, String type, JsonNode params, JobFile input) {

    public String text(String name) {
        JsonNode n = params.get(name);
        return n == null || n.isNull() ? null : n.asString();
    }

    public boolean flag(String name) {
        JsonNode n = params.get(name);
        return n != null && n.asBoolean();
    }

    public java.util.List<Long> ids(String name) {
        JsonNode n = params.get(name);
        if (n == null || !n.isArray()) {
            return java.util.List.of();
        }
        java.util.List<Long> out = new java.util.ArrayList<>();
        n.forEach(x -> out.add(x.asLong()));
        return out;
    }
}
