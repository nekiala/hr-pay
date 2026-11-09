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
    public static final Map<String, String> COUNTRIES = Map.of("CD", "DR Congo", "CG", "Republic of the Congo",
            "CM", "Cameroon", "GA", "Gabon");

    /** Each country's own currency, the one taxes are declared in. */
    public static final Map<String, String> CURRENCIES = Map.of("CD", "CDF", "CG", "XAF", "CM", "XAF", "GA", "XAF");

    /** The countries whose payroll rules are data (contributions and scales), not code. */
    public static final java.util.Set<String> RULES_AS_DATA = java.util.Set.of("CM", "GA");

    /** The income tax on salaries as the country names it. */
    public static String taxName(String country) {
        return switch (country == null ? "CD" : country) {
            case "CG" -> "ITS";
            // The DRC's IPR is the IRPP since 2026 (Loi 23/053); the DGI's forms and the declarations still say IPR.
            default -> "IRPP";
        };
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    private cd.hrpay.law.LawChanges lawChanges;

    @org.springframework.beans.factory.annotation.Autowired
    void setLawChanges(@org.springframework.context.annotation.Lazy cd.hrpay.law.LawChanges lawChanges) {
        this.lawChanges = lawChanges;
    }

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
        boolean french = "fr".equals(jdbc.sql("select language from company where id = current_company_id()")
                .query(String.class).optional().orElse("fr"));
        JsonNode names = french ? names() : null;
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
                if (names != null) {   // the starting names in the company's language; codes stay as they are
                    for (String column : List.of("name", "description", "head_title")) {
                        JsonNode v = row.get(column);
                        if (v != null && v.isString() && names.has(v.asString())) {
                            row.put(column, names.get(v.asString()).asString());
                        }
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
        // The rates published since the pack was written, and the link of each rate to its change.
        if (lawChanges != null) {
            lawChanges.applyAllHere(country);
        }
    }

    /** The pack's names in French, by their English name. */
    private JsonNode names() {
        try (InputStream in = CountryPacks.class.getResourceAsStream("/packs/names-fr.json")) {
            return in == null ? json.createObjectNode() : json.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("The French names of the packs cannot be read", e);
        }
    }

    /** The country's pack as it ships, read-only (the public calculator takes its rates from it). */
    public JsonNode pack(String country) {
        return read(country);
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
