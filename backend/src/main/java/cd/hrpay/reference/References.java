package cd.hrpay.reference;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reference lists (master data) picked on the employee file instead of typed: countries and nationalities,
 * administrative places (DRC provinces, villes, territoires, communes; Congo departments, towns, arrondissements),
 * banks and mobile money operators. HR Pay keeps the shared lists (company_id null, written with platform.manage in
 * the provider's company); a company adds what is missing for itself (company_id set, masterdata.manage). A company
 * sees the shared entries and its own, never another company's.
 */
@Service
public class References {

    public record Country(String code, String name, String nationality) {}

    public record Place(long id, String countryCode, Long parentId, String kind, String name, boolean active,
                        boolean verified, boolean shared, int children) {}

    public record Institution(long id, String countryCode, String kind, String name, String shortName, String swiftCode,
                              String aliases, boolean active, boolean verified, boolean shared) {}

    public record PlaceInput(String countryCode, Long parentId, String kind, String name, Boolean active, Boolean verified,
                             Boolean shared) {}

    public record InstitutionInput(String countryCode, String kind, String name, String shortName, String swiftCode,
                                   String aliases, Boolean active, Boolean verified, Boolean shared) {}

    /** A value in an employee file typed before the lists, not yet picked from them. */
    public record ToPick(long userId, String name, String registrationNo, String field, String value) {}

    static final Set<String> PLACE_KINDS = Set.of("PROVINCE", "DEPARTMENT", "CITY", "TERRITORY", "COMMUNE", "SECTOR",
            "CHIEFDOM", "DISTRICT", "ARRONDISSEMENT");
    static final Set<String> TOP_KINDS = Set.of("PROVINCE", "DEPARTMENT");

    private static final String VISIBLE = "(company_id is null or company_id = current_company_id())";
    private static final String P_VISIBLE = "(p.company_id is null or p.company_id = current_company_id())";

    private static final String PLACE_SELECT = """
            select p.id, p.country_code, p.parent_id, p.kind, p.name, p.active, p.verified, p.company_id is null as shared,
                   (select count(*) from place c where c.parent_id = p.id and c.active
                      and (c.company_id is null or c.company_id = current_company_id()))::int as children
            from place p""";

    private static final String INSTITUTION_SELECT = """
            select id, country_code, kind, name, short_name, swift_code, aliases, active, verified, company_id is null as shared
            from financial_institution""";

    private final JdbcClient jdbc;
    private final AuditService audit;

    public References(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---- countries --------------------------------------------------------------------------------------------------

    private String countryColumns() {
        return Texts.french() ? "code, name_fr as name, coalesce(nationality_fr, name_fr) as nationality"
                : "code, name_en as name, coalesce(nationality_en, name_en) as nationality";
    }

    public List<Country> countries() {
        return jdbc.sql("select " + countryColumns() + " from country where active order by ref_norm(" +
                (Texts.french() ? "name_fr" : "name_en") + ")").query(Country.class).list();
    }

    public Optional<Country> country(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return jdbc.sql("select " + countryColumns() + " from country where code = ?").param(code.toUpperCase())
                .query(Country.class).optional();
    }

    public Country requireCountry(String code) {
        return country(code).orElseThrow(() -> new BusinessRuleException(Texts.t("Unknown country: %s.", code)));
    }

    /** The countries that have places in the lists (others: the town is typed). */
    public List<String> countriesWithPlaces() {
        return jdbc.sql("select distinct country_code from place where " + VISIBLE + " order by 1").query(String.class).list();
    }

    // ---- places -----------------------------------------------------------------------------------------------------

    /** The places under a parent (or the top level of a country), sorted by name. */
    public List<Place> places(String country, Long parentId, boolean all) {
        return jdbc.sql(PLACE_SELECT + " where p.country_code = ? and p.parent_id is not distinct from ? and "
                        + P_VISIBLE + (all ? "" : " and p.active")
                        + " order by case p.kind when 'COMMUNE' then 1 when 'ARRONDISSEMENT' then 1 when 'CITY' then 2 else 3 end, ref_norm(p.name)")
                .params(country.toUpperCase(), parentId).query(Place.class).list();
    }

    public Place place(long id) {
        return jdbc.sql(PLACE_SELECT + " where p.id = ? and " + P_VISIBLE).param(id)
                .query(Place.class).optional()
                .orElseThrow(() -> new NotFoundException(Texts.t("This place is not in the list.")));
    }

    /** The place and the places above it, the top one first. */
    public List<Place> path(Long id) {
        if (id == null) {
            return List.of();
        }
        List<Place> out = new ArrayList<>();
        Long at = id;
        while (at != null && out.size() < 10) {
            Place p = place(at);
            out.add(p);
            at = p.parentId();
        }
        Collections.reverse(out);
        return out;
    }

    /** "Ngaliema, Kinshasa" or "Masisi, Nord-Kivu": the place and the places above it, the same name said once. */
    public String label(Long id) {
        if (id == null) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (Place p : path(id).reversed()) {
            if (names.isEmpty() || !names.getLast().equals(p.name())) {
                names.add(p.name());
            }
        }
        return String.join(", ", names);
    }

    @Transactional
    public Place addPlace(PlaceInput in) {
        boolean shared = shared(in.shared());
        String country = requireCountry(in.countryCode()).code();
        String kind = kind(in.kind());
        String name = name(in.name());
        if (in.parentId() == null && !TOP_KINDS.contains(kind)) {
            throw new BusinessRuleException("Choose where it is: a commune, town or territory sits under a province or department.");
        }
        if (in.parentId() != null) {
            Place parent = place(in.parentId());
            if (!parent.countryCode().equals(country)) {
                throw new BusinessRuleException("The place above is in another country.");
            }
            if (shared && !parent.shared()) {
                throw new BusinessRuleException("A shared place cannot sit under a company's own place.");
            }
        }
        try {
            long id = jdbc.sql("""
                    insert into place (company_id, country_code, parent_id, kind, name, verified, created_by)
                    values (?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(shared ? null : TenantContext.companyId(), country, in.parentId(), kind, name,
                            shared && Boolean.TRUE.equals(in.verified()), CurrentUser.email())
                    .query(Long.class).single();
            audit.record("reference.place_added", "place", id, Map.of("name", name, "kind", kind, "shared", shared));
            return place(id);
        } catch (DuplicateKeyException e) {
            throw new ConflictException(Texts.t("%s is already in the list.", name));
        }
    }

    @Transactional
    public Place updatePlace(long id, PlaceInput in) {
        Place p = place(id);
        mayChange(p.shared());
        String name = in.name() == null ? p.name() : name(in.name());
        boolean active = in.active() == null ? p.active() : in.active();
        boolean verified = p.shared() && (in.verified() == null ? p.verified() : in.verified());
        try {
            jdbc.sql("update place set name = ?, active = ?, verified = ? where id = ?").params(name, active, verified, id).update();
        } catch (DuplicateKeyException e) {
            throw new ConflictException(Texts.t("%s is already in the list.", name));
        }
        audit.record("reference.place_changed", "place", id, Map.of("name", name, "active", active));
        return place(id);
    }

    // ---- banks and mobile money operators ---------------------------------------------------------------------------

    public List<Institution> institutions(String country, String kind, boolean all) {
        StringBuilder sql = new StringBuilder(INSTITUTION_SELECT).append(" where ").append(VISIBLE);
        List<Object> params = new ArrayList<>();
        if (country != null && !country.isBlank()) {
            sql.append(" and country_code = ?");
            params.add(country.toUpperCase());
        }
        if (kind != null && !kind.isBlank()) {
            sql.append(" and kind = ?");
            params.add(kind.toUpperCase());
        }
        if (!all) {
            sql.append(" and active");
        }
        sql.append(" order by country_code, kind, ref_norm(name)");
        return jdbc.sql(sql.toString()).params(params).query(Institution.class).list();
    }

    public Institution institution(long id) {
        return jdbc.sql(INSTITUTION_SELECT + " where id = ? and " + VISIBLE).param(id).query(Institution.class).optional()
                .orElseThrow(() -> new NotFoundException(Texts.t("This bank or operator is not in the list.")));
    }

    @Transactional
    public Institution addInstitution(InstitutionInput in) {
        boolean shared = shared(in.shared());
        String country = requireCountry(in.countryCode()).code();
        String kind = institutionKind(in.kind());
        String name = name(in.name());
        try {
            long id = jdbc.sql("""
                    insert into financial_institution (company_id, country_code, kind, name, short_name, swift_code, aliases,
                        verified, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(shared ? null : TenantContext.companyId(), country, kind, name, blank(in.shortName()),
                            swift(in.swiftCode()), blank(in.aliases()), shared && Boolean.TRUE.equals(in.verified()),
                            CurrentUser.email())
                    .query(Long.class).single();
            audit.record("reference.institution_added", "financial_institution", id, Map.of("name", name, "kind", kind,
                    "shared", shared));
            return institution(id);
        } catch (DuplicateKeyException e) {
            throw new ConflictException(Texts.t("%s is already in the list.", name));
        }
    }

    @Transactional
    public Institution updateInstitution(long id, InstitutionInput in) {
        Institution b = institution(id);
        mayChange(b.shared());
        String name = in.name() == null ? b.name() : name(in.name());
        boolean active = in.active() == null ? b.active() : in.active();
        boolean verified = b.shared() && (in.verified() == null ? b.verified() : in.verified());
        try {
            jdbc.sql("""
                    update financial_institution set name = ?, short_name = ?, swift_code = ?, aliases = ?, active = ?,
                        verified = ? where id = ?""")
                    .params(name, blank(in.shortName()), swift(in.swiftCode()), blank(in.aliases()), active, verified, id)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ConflictException(Texts.t("%s is already in the list.", name));
        }
        // The employee files keep the name of what was picked: they follow a rename.
        jdbc.sql("update employee_profile set bank_name = ? where bank_id = ?").params(name, id).update();
        jdbc.sql("update employee_profile set mobile_money_operator = ? where mobile_money_operator_id = ?").params(name, id).update();
        audit.record("reference.institution_changed", "financial_institution", id, Map.of("name", name, "active", active));
        return institution(id);
    }

    // ---- matching typed text (imports) ------------------------------------------------------------------------------

    /** The country a typed nationality or country name stands for; "Congolais(e)" alone is the company's Congo. */
    public Optional<String> matchCountry(String text, String home) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        if (text.trim().matches("[A-Za-z]{2}") && country(text.trim()).isPresent()) {
            return Optional.of(text.trim().toUpperCase());
        }
        return jdbc.sql("""
                with t as (select ref_norm(?) as n),
                names as (
                    select code, ref_norm(name_fr) as n from country
                    union select code, ref_norm(name_en) from country
                    union select code, ref_norm(nationality_fr) from country where nationality_fr is not null
                    union select code, regexp_replace(ref_norm(nationality_fr), 'e$', '') from country where nationality_fr is not null
                    union select code, regexp_replace(ref_norm(nationality_fr), 'nne$', 'n') from country where nationality_fr is not null
                    union select code, ref_norm(nationality_en) from country where nationality_en is not null
                    union select code, ref_norm(a) from country, unnest(string_to_array(aliases, '|')) a)
                select case when t.n ~ '^congolaise? (rdc|rd congo|drc|kinshasa)' then 'CD'
                            when t.n ~ '^congolaise? (brazza|congo brazza)' then 'CG'
                            when t.n ~ '^congolaise?$' then case when ? in ('CD', 'CG') then ? else 'CD' end
                            else (select min(code) from names where names.n = t.n having count(distinct code) = 1) end
                from t""").params(text, home, home).query(String.class).optional();
    }

    /** The bank or operator a typed name stands for in a country: its name, short name or another spelling. */
    public Optional<Institution> matchInstitution(String text, String country, String kind) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        List<Long> ids = jdbc.sql("""
                select distinct id from financial_institution, unnest(array[name, short_name] || string_to_array(coalesce(aliases, ''), '|')) a
                where """ + VISIBLE + " and active and country_code = ? and kind = ? and ref_norm(a) = ref_norm(?)")
                .params(country, kind, text).query(Long.class).list();
        return ids.size() == 1 ? Optional.of(institution(ids.getFirst())) : Optional.empty();
    }

    /**
     * The place a typed name stands for in a country: a commune first, then a town, then a territory. "Kalamu (Boma)"
     * or "Kalamu, Boma" names the place above when two have the same name.
     */
    public Optional<Place> matchPlace(String text, String country) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String name = text;
        String parent = null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(.+?)\\s*(?:\\((.+)\\)|,\\s*(.+))\\s*$").matcher(text);
        if (m.matches()) {
            name = m.group(1);
            parent = m.group(2) != null ? m.group(2) : m.group(3);
        }
        for (List<String> kinds : List.of(List.of("COMMUNE", "ARRONDISSEMENT"), List.of("CITY"),
                List.of("TERRITORY", "SECTOR", "CHIEFDOM", "DISTRICT"), List.of("PROVINCE", "DEPARTMENT"))) {
            List<Long> ids = jdbc.sql("""
                    select p.id from place p left join place up on up.id = p.parent_id
                    where (p.company_id is null or p.company_id = current_company_id()) and p.active
                      and p.country_code = :country and p.kind in (:kinds) and ref_norm(p.name) = ref_norm(:name)
                      and (cast(:parent as text) is null or ref_norm(up.name) = ref_norm(:parent))""")
                    .param("country", country).param("kinds", kinds).param("name", name).param("parent", parent)
                    .query(Long.class).list();
            if (ids.size() == 1) {
                return Optional.of(place(ids.getFirst()));
            }
            if (ids.size() > 1) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    // ---- what is left to pick ---------------------------------------------------------------------------------------

    /** Employee files with a value typed before the lists: HR picks it from the list. */
    public List<ToPick> toPick() {
        return jdbc.sql("""
                select u.id as user_id, person_name(u.first_name, u.middle_name, u.last_name) as name, u.registration_no, x.field, x.value
                from employee_profile e join app_user u on u.id = e.user_id
                cross join lateral (values
                    ('NATIONALITY', case when e.nationality_code is null then e.nationality end),
                    ('COMMUNE', case when e.address_place_id is null then e.commune end),
                    ('BANK', case when e.bank_id is null and e.payment_method = 'BANK' then e.bank_name end),
                    ('MOBILE_MONEY', case when e.mobile_money_operator_id is null and e.payment_method = 'MOBILE_MONEY'
                                          then e.mobile_money_operator end)) as x(field, value)
                where x.value is not null and u.status = 'ACTIVE'
                order by u.last_name, u.first_name, x.field""").query(ToPick.class).list();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** A shared entry is written by HR Pay's administrators (platform.manage, provider company only). */
    private boolean shared(Boolean wanted) {
        boolean shared = Boolean.TRUE.equals(wanted);
        if (shared && !platform()) {
            throw new BusinessRuleException("Only HR Pay's administrators change the shared lists: add it for your company.");
        }
        if (!shared && !CurrentUser.has("masterdata.manage")) {
            throw new BusinessRuleException("Adding to the lists needs the master data permission.");
        }
        return shared;
    }

    private void mayChange(boolean shared) {
        if (shared ? !platform() : !CurrentUser.has("masterdata.manage")) {
            throw new BusinessRuleException(shared ? "Only HR Pay's administrators change the shared lists."
                    : "Changing the lists needs the master data permission.");
        }
    }

    public static boolean platform() {
        return CurrentUser.has("platform.manage") && TenantContext.isProvider(TenantContext.companyId());
    }

    private static String kind(String kind) {
        String k = kind == null ? "" : kind.trim().toUpperCase();
        if (!PLACE_KINDS.contains(k)) {
            throw new BusinessRuleException("Choose the kind of place.");
        }
        return k;
    }

    private static String institutionKind(String kind) {
        String k = kind == null ? "" : kind.trim().toUpperCase();
        if (!k.equals("BANK") && !k.equals("MOBILE_MONEY")) {
            throw new BusinessRuleException("Choose bank or mobile money.");
        }
        return k;
    }

    private static String name(String name) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (n.isEmpty() || n.length() > 100) {
            throw new BusinessRuleException("Give the name in 1 to 100 characters.");
        }
        return n;
    }

    private static String swift(String code) {
        String s = blank(code);
        if (s == null) {
            return null;
        }
        s = s.toUpperCase().replace(" ", "");
        if (!s.matches("[A-Z0-9]{8}([A-Z0-9]{3})?")) {
            throw new BusinessRuleException("A SWIFT (BIC) code has 8 or 11 letters and digits.");
        }
        return s;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
