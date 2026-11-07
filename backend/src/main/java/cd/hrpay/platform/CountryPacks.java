package cd.hrpay.platform;

import cd.hrpay.tenancy.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A country's starting set-up for a new company: grade tables, leave types, the structure levels, a standard
 * schedule, overtime rules, public holidays, pay elements, statutory rates and the income tax scale, evaluation
 * criteria, and the standard roles. Kept in packs/{country}.json; each row is copied into the new company with new
 * ids, its references to other rows of the pack following them.
 */
@Service
public class CountryPacks {

    /** The countries with a pack, and how they are named on screen. */
    public static final Map<String, String> COUNTRIES = Map.of("CD", "DR Congo");

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public CountryPacks(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Copies the country's pack into the current company. Run inside the new company. */
    public void apply(String country) {
        if (TenantContext.companyId() == null || TenantContext.bypass()) {
            throw new IllegalStateException("A country pack is applied within one company");
        }
        JsonNode pack = read(country);
        Map<String, Map<Long, Long>> ids = new HashMap<>();
        for (JsonNode table : pack.get("tables")) {
            String name = table.get("table").asString();
            Map<String, String> refs = new LinkedHashMap<>();
            table.get("refs").properties().forEach(e -> refs.put(e.getKey(), e.getValue().asString()));
            Map<Long, Long> mine = ids.computeIfAbsent(name, k -> new HashMap<>());
            for (JsonNode source : table.get("rows")) {
                ObjectNode row = ((ObjectNode) source).deepCopy();
                if ("role_permission".equals(name)
                        && TenantContext.PLATFORM_PERMISSIONS.contains(row.get("permission_code").asString())) {
                    continue; // installation-wide permissions stay with the provider's company
                }
                for (Map.Entry<String, String> ref : refs.entrySet()) {
                    JsonNode old = row.get(ref.getKey());
                    if (old != null && !old.isNull()) {
                        Long mapped = ids.getOrDefault(ref.getValue(), Map.of()).get(old.asLong());
                        if (mapped == null) {
                            throw new IllegalStateException(name + "." + ref.getKey() + " points to a missing "
                                    + ref.getValue() + " row in the " + country + " pack");
                        }
                        row.put(ref.getKey(), mapped);
                    }
                }
                JsonNode oldId = row.remove("id");
                row.remove("company_id");
                List<String> columns = row.propertyNames().stream().toList();
                String list = String.join(", ", columns);
                String sql = "insert into " + name + " (" + list + ") select " + list
                        + " from json_populate_record(null::" + name + ", cast(? as json))"
                        + (oldId == null ? "" : " returning id");
                if (oldId == null) {
                    jdbc.sql(sql).param(row.toString()).update();
                } else {
                    mine.put(oldId.asLong(), jdbc.sql(sql).param(row.toString()).query(Long.class).single());
                }
            }
        }
        // Anniversary raises start from the company's first month on HR Pay, not from the pack's date.
        jdbc.sql("update payroll_setting set value = ? where key = 'RAISES_FROM'")
                .param(LocalDate.now().withDayOfMonth(1).toString()).update();
    }

    private JsonNode read(String country) {
        if (!COUNTRIES.containsKey(country)) {
            throw new cd.hrpay.common.BusinessRuleException("There is no set-up for this country yet.");
        }
        try (InputStream in = CountryPacks.class.getResourceAsStream("/packs/" + country + ".json")) {
            if (in == null) {
                throw new IllegalStateException("The " + country + " pack is missing from the build");
            }
            return json.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("The " + country + " pack cannot be read", e);
        }
    }
}
