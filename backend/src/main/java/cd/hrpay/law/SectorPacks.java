package cd.hrpay.law;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.ConflictException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.overtime.OvertimePolicy;
import cd.hrpay.overtime.OvertimePolicyRepository;
import cd.hrpay.overtime.OvertimeRateTier;
import cd.hrpay.tenancy.Tenants;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sector packs: the rules of a collective agreement laid over the country pack. HR Pay keeps them (shared tables);
 * a company chooses the pack of its sector, sees what would change, and applies it: payroll and leave settings,
 * family-event leave days, overtime rates from a date, statutory rates from a date. Notes are rules HR Pay does not
 * apply by itself. A pack without its agreement's text is a frame: shown, not applicable.
 */
@Service
public class SectorPacks {

    public static final Set<String> KINDS = Set.of("SETTING", "LEAVE_DAYS", "OVERTIME", "RATE", "NOTE");
    public static final Set<String> STATUSES = Set.of("FRAME", "READY", "RETIRED");

    /** The payroll and leave settings a pack may set. */
    public static final Set<String> SETTINGS = Set.of("LEAVE_MIN_DAYS_MONTH", "LEAVE_MIN_DAYS_MONTH_MINOR",
            "LEAVE_SENIORITY_YEARS", "LEAVE_SENIORITY_DAYS", "LEAVE_CIRCUMSTANCE_YEAR_MAX", "LEAVE_CARRY_PERCENT",
            "SENIORITY_BONUS_PERCENT_PER_YEAR", "SENIORITY_BONUS_MAX_PERCENT", "SENIORITY_BONUS_FROM_YEARS",
            "END_OF_CAREER_DAYS_PER_YEAR", "END_OF_CAREER_MAX_MONTHS", "THIRTEENTH_ENABLED", "THIRTEENTH_FALLBACK_PERCENT");

    public record Rule(Long id, String kind, String key, Integer fromHour, Integer toHour, String value, String labelEn,
                       String labelFr, String article, Integer position) {}

    public record Pack(String code, String countryCode, String nameEn, String nameFr, String scopeEn, String scopeFr,
                       String reference, String sourceUrl, String status, String verifiedBy, LocalDate verifiedOn,
                       int displayOrder, OffsetDateTime updatedAt, int companies, List<Rule> rules) {}

    /** What the provider writes. */
    public record PackInput(String countryCode, String nameEn, String nameFr, String scopeEn, String scopeFr,
                            String reference, String sourceUrl, String status, String verifiedBy, LocalDate verifiedOn,
                            Integer displayOrder, List<Rule> rules) {}

    /** One line of the preview: what the company has now and what the pack gives. */
    public record Line(String kind, String key, String labelEn, String labelFr, String article, String current,
                       String next, boolean changes, boolean applies) {}

    public record Preview(Pack pack, LocalDate from, List<Line> lines, int changes) {}

    /** The company's sector: its pack and the packs of its country. */
    public record CompanySector(String country, String current, OffsetDateTime appliedAt, String appliedBy,
                                List<Pack> packs, String activity, List<String> suggested) {}

    private record PackRow(String code, String countryCode, String nameEn, String nameFr, String scopeEn, String scopeFr,
                           String reference, String sourceUrl, String status, String verifiedBy, LocalDate verifiedOn,
                           int displayOrder, OffsetDateTime updatedAt, int companies) {}

    private static final String PACK_SELECT = """
            select p.code, p.country_code, p.name_en, p.name_fr, p.scope_en, p.scope_fr, p.reference, p.source_url, p.status,
                   p.verified_by, p.verified_on, p.display_order, p.updated_at,
                   (select count(*) from company c where c.sector_pack_code = p.code) as companies
            from sector_pack p""";

    private final JdbcClient jdbc;
    private final Tenants tenants;
    private final TransactionTemplate tx;
    private final OvertimePolicyRepository policies;
    private final AuditService audit;

    public SectorPacks(JdbcClient jdbc, Tenants tenants, TransactionTemplate tx, OvertimePolicyRepository policies,
                       AuditService audit) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.tx = tx;
        this.policies = policies;
        this.audit = audit;
    }

    // ---- reading ------------------------------------------------------------------------------------------------

    public List<Pack> all() {
        return tenants.callAsPlatform(() -> withRules(jdbc.sql(PACK_SELECT + " order by p.country_code, p.display_order, p.code")
                .query(PackRow.class).list()));
    }

    public List<Pack> forCountry(String country) {
        return tenants.callAsPlatform(() -> withRules(jdbc.sql(PACK_SELECT + " " + """
                 where p.country_code = ? and p.status <> 'RETIRED' order by p.display_order, p.code""")
                .param(country).query(PackRow.class).list()));
    }

    public Pack get(String code) {
        return tenants.callAsPlatform(() -> withRules(jdbc.sql(PACK_SELECT + " where p.code = ?").param(code)
                .query(PackRow.class).list())).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Sector pack not found."));
    }

    private List<Pack> withRules(List<PackRow> rows) {
        List<Pack> out = new ArrayList<>();
        for (PackRow p : rows) {
            List<Rule> rules = jdbc.sql("""
                    select id, kind, key, from_hour, to_hour, value, label_en, label_fr, article, position
                    from sector_pack_rule where pack_code = ? order by position, id""").param(p.code()).query(Rule.class).list();
            out.add(new Pack(p.code(), p.countryCode(), p.nameEn(), p.nameFr(), p.scopeEn(), p.scopeFr(), p.reference(),
                    p.sourceUrl(), p.status(), p.verifiedBy(), p.verifiedOn(), p.displayOrder(), p.updatedAt(), p.companies(), rules));
        }
        return out;
    }

    // ---- the provider -------------------------------------------------------------------------------------------

    static void check(String code, PackInput in) {
        if (code == null || !code.matches("[A-Z0-9_]{2,40}")) {
            throw new BusinessRuleException("The code is 2 to 40 capital letters, digits or _.");
        }
        if (in.countryCode() == null || !LawChanges.COUNTRIES.contains(in.countryCode())) {
            throw new BusinessRuleException("Choose the country: DR Congo or Republic of the Congo.");
        }
        if (blank(in.nameEn()) || blank(in.nameFr()) || in.status() == null || !STATUSES.contains(in.status())) {
            throw new BusinessRuleException("A sector pack needs its name in both languages and its status.");
        }
        if (in.sourceUrl() != null && !in.sourceUrl().isBlank() && !in.sourceUrl().startsWith("https://")) {
            throw new BusinessRuleException("The source link must start with https://.");
        }
        List<Rule> rules = in.rules() == null ? List.of() : in.rules();
        if ("READY".equals(in.status()) && (blank(in.reference()) || rules.stream().noneMatch(r -> !"NOTE".equals(r.kind())))) {
            throw new BusinessRuleException("A pack ready to apply needs its agreement's reference and at least one rule.");
        }
        for (Rule r : rules) {
            if (r.kind() == null || !KINDS.contains(r.kind()) || blank(r.labelEn()) || blank(r.labelFr())) {
                throw new BusinessRuleException("Each rule needs its kind and its wording in both languages.");
            }
            switch (r.kind()) {
                case "SETTING" -> {
                    if (!SETTINGS.contains(r.key()) || !validSetting(r.key(), r.value())) {
                        throw new BusinessRuleException("Invalid setting in the pack: " + r.key() + " = " + r.value() + ".");
                    }
                }
                case "LEAVE_DAYS" -> {
                    if (r.key() == null || !r.key().matches("[A-Z0-9_]{2,32}") || r.value() == null || !r.value().matches("\\d{1,3}")
                            || Integer.parseInt(r.value()) < 1) {
                        throw new BusinessRuleException("A leave rule needs the leave type's code and a number of days.");
                    }
                }
                case "OVERTIME" -> {
                    if (r.key() == null || !Set.of("WEEKDAY", "REST_DAY", "HOLIDAY").contains(r.key()) || r.fromHour() == null
                            || r.fromHour() < 0 || r.toHour() != null && r.toHour() <= r.fromHour() || !decimal(r.value())
                            || new BigDecimal(r.value()).compareTo(BigDecimal.ONE) < 0 || new BigDecimal(r.value()).compareTo(BigDecimal.TEN) >= 0) {
                        throw new BusinessRuleException("An overtime rule needs its kind, its hours and a rate from 1 to 9.99.");
                    }
                }
                case "RATE" -> {
                    if (r.key() == null || !r.key().matches("[A-Z0-9_]{2,40}") || !decimal(r.value())) {
                        throw new BusinessRuleException("A rate rule needs the rate's code and a value of zero or more.");
                    }
                }
                default -> { }
            }
        }
        // Overtime rates of a kind follow each other from hour 0, the last one open-ended.
        Map<String, List<Rule>> tiers = rules.stream().filter(r -> "OVERTIME".equals(r.kind()))
                .collect(Collectors.groupingBy(Rule::key));
        for (var e : tiers.entrySet()) {
            List<Rule> ofKind = e.getValue().stream().sorted(Comparator.comparingInt(Rule::fromHour)).toList();
            if (ofKind.getFirst().fromHour() != 0) {
                throw new BusinessRuleException("Overtime rates for " + e.getKey() + " must start at hour 0.");
            }
            for (int i = 0; i < ofKind.size(); i++) {
                Integer to = ofKind.get(i).toHour();
                boolean last = i == ofKind.size() - 1;
                if (last ? to != null : (to == null || !to.equals(ofKind.get(i + 1).fromHour()))) {
                    throw new BusinessRuleException("Overtime rates for " + e.getKey() + " must follow each other without gaps, the last one open-ended.");
                }
            }
        }
    }

    static boolean validSetting(String key, String value) {
        if (value == null) {
            return false;
        }
        if (key.equals("THIRTEENTH_ENABLED")) {
            return value.equals("true") || value.equals("false");
        }
        return decimal(value) && new BigDecimal(value).compareTo(BigDecimal.valueOf(1000)) < 0;
    }

    private static boolean decimal(String v) {
        return v != null && v.matches("\\d{1,6}(\\.\\d{1,4})?");
    }

    public Pack save(String code, PackInput in, String by) {
        check(code, in);
        tenants.callAsPlatform(() -> tx.execute(s -> {
            jdbc.sql("""
                    insert into sector_pack (code, country_code, name_en, name_fr, scope_en, scope_fr, reference, source_url,
                        status, verified_by, verified_on, display_order, updated_by, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    on conflict (code) do update set country_code = excluded.country_code, name_en = excluded.name_en,
                        name_fr = excluded.name_fr, scope_en = excluded.scope_en, scope_fr = excluded.scope_fr,
                        reference = excluded.reference, source_url = excluded.source_url, status = excluded.status,
                        verified_by = excluded.verified_by, verified_on = excluded.verified_on,
                        display_order = excluded.display_order, updated_by = excluded.updated_by, updated_at = now()""")
                    .params(code, in.countryCode(), in.nameEn().trim(), in.nameFr().trim(), trim(in.scopeEn()), trim(in.scopeFr()),
                            trim(in.reference()), trim(in.sourceUrl()), in.status(), trim(in.verifiedBy()), in.verifiedOn(),
                            in.displayOrder() == null ? 100 : in.displayOrder(), by).update();
            jdbc.sql("delete from sector_pack_rule where pack_code = ?").param(code).update();
            int position = 0;
            for (Rule r : in.rules() == null ? List.<Rule>of() : in.rules()) {
                position += 10;
                boolean note = "NOTE".equals(r.kind());
                jdbc.sql("""
                        insert into sector_pack_rule (pack_code, kind, key, from_hour, to_hour, value, label_en, label_fr, article, position)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                        .params(code, r.kind(), note ? null : r.key(), "OVERTIME".equals(r.kind()) ? r.fromHour() : null,
                                "OVERTIME".equals(r.kind()) ? r.toHour() : null, note ? null : r.value().trim(),
                                r.labelEn().trim(), r.labelFr().trim(), trim(r.article()), position).update();
            }
            return null;
        }));
        audit.record("sector_pack.saved", "sector_pack", code, Map.of("code", code, "status", in.status(), "by", by));
        return get(code);
    }

    public void delete(String code) {
        Pack p = get(code);
        if (p.companies() > 0) {
            throw new ConflictException("Companies use this pack: retire it instead.");
        }
        tenants.callAsPlatform(() -> jdbc.sql("delete from sector_pack where code = ?").param(code).update());
        audit.record("sector_pack.deleted", "sector_pack", code, Map.of("code", code));
    }

    // ---- the company --------------------------------------------------------------------------------------------

    private String country() {
        return jdbc.sql("select country_code from company where id = current_company_id()").query(String.class)
                .optional().orElse("CD");
    }

    public CompanySector forCompany() {
        var row = jdbc.sql("""
                select country_code, sector_pack_code, sector_pack_applied_at, sector_pack_applied_by
                from company where id = current_company_id()""")
                .query((rs, i) -> new Object[] {rs.getString(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class), rs.getString(4)})
                .optional().orElse(new Object[] {"CD", null, null, null});
        String country = (String) row[0];
        // The packs of the company's branch of activity (the branch picked, or its section).
        List<String> suggested = jdbc.sql("""
                select p.code from company c
                join activity_branch b on b.id = c.activity_branch_id
                left join activity_branch s on s.id = b.parent_id
                join sector_pack p on p.country_code = c.country_code and p.status <> 'RETIRED'
                                  and p.activity_code in (b.code, s.code)
                where c.id = current_company_id() order by p.display_order, p.code""").query(String.class).list();
        String activity = jdbc.sql("select activity from company where id = current_company_id()").query(String.class)
                .optional().orElse(null);
        return new CompanySector(country, (String) row[1], (OffsetDateTime) row[2], (String) row[3], forCountry(country),
                activity, suggested);
    }

    /** The day new overtime and statutory rates start when none is given: the first day of next month. */
    public static LocalDate defaultFrom(LocalDate today) {
        return YearMonth.from(today).plusMonths(1).atDay(1);
    }

    private Pack applicable(String code) {
        Pack p = get(code);
        if (!p.countryCode().equals(country())) {
            throw new BusinessRuleException("This pack is for another country.");
        }
        if (!p.status().equals("READY")) {
            throw new BusinessRuleException("This pack has no rules yet: HR Pay adds them once it has the agreement's text.");
        }
        return p;
    }

    public Preview preview(String code, LocalDate from) {
        Pack p = applicable(code);
        List<Line> lines = new ArrayList<>();
        Map<String, String> settings = new LinkedHashMap<>();
        jdbc.sql("select key, value from payroll_setting").query((rs, i) -> settings.put(rs.getString(1), rs.getString(2))).list();
        OvertimePolicy policy = policies.findFirstByCountryCodeAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(p.countryCode(), from)
                .orElse(null);
        Set<String> overtimeDone = new java.util.HashSet<>();
        for (Rule r : p.rules()) {
            switch (r.kind()) {
                case "SETTING" -> {
                    String now = settings.get(r.key());
                    lines.add(new Line(r.kind(), r.key(), r.labelEn(), r.labelFr(), r.article(), now, r.value(),
                            now == null || !same(now, r.value()), true));
                }
                case "LEAVE_DAYS" -> {
                    var days = jdbc.sql("select max_days from leave_type where code = ?").param(r.key())
                            .query((rs, i) -> rs.getObject(1, Integer.class) == null ? "—" : String.valueOf(rs.getInt(1))).optional();
                    lines.add(new Line(r.kind(), r.key(), r.labelEn(), r.labelFr(), r.article(), days.orElse(null), r.value(),
                            days.isPresent() && !same(days.get(), r.value()), days.isPresent()));
                }
                case "OVERTIME" -> {
                    if (!overtimeDone.add(r.key())) {
                        continue;
                    }
                    List<Rule> ofKind = p.rules().stream().filter(x -> "OVERTIME".equals(x.kind()) && x.key().equals(r.key()))
                            .sorted(Comparator.comparingInt(Rule::fromHour)).toList();
                    String next = ofKind.stream().map(x -> tier(x.fromHour(), x.toHour(), new BigDecimal(x.value())))
                            .collect(Collectors.joining(" · "));
                    String now = policy == null ? null : policy.getTiers().stream()
                            .filter(t -> t.getKind().name().equals(r.key()))
                            .sorted(Comparator.comparingInt(OvertimeRateTier::getFromHour))
                            .map(t -> tier(t.getFromHour(), t.getToHour(), t.getMultiplier())).collect(Collectors.joining(" · "));
                    lines.add(new Line(r.kind(), r.key(),
                            ofKind.stream().map(Rule::labelEn).collect(Collectors.joining("; ")),
                            ofKind.stream().map(Rule::labelFr).collect(Collectors.joining(" ; ")),
                            r.article(), now, next, !next.equals(now), true));
                }
                case "RATE" -> {
                    boolean exists = jdbc.sql("select count(*) from statutory_rate_type where code = ?").param(r.key())
                            .query(Integer.class).single() > 0;
                    String now = jdbc.sql("""
                            select value from statutory_rate where code = ? and effective_from <= ? order by effective_from desc limit 1""")
                            .params(r.key(), from).query(BigDecimal.class).optional().map(v -> v.stripTrailingZeros().toPlainString()).orElse(null);
                    lines.add(new Line(r.kind(), r.key(), r.labelEn(), r.labelFr(), r.article(), now, r.value(),
                            exists && (now == null || !same(now, r.value())), exists));
                }
                default -> lines.add(new Line(r.kind(), null, r.labelEn(), r.labelFr(), r.article(), null, null, false, false));
            }
        }
        return new Preview(p, from, lines, (int) lines.stream().filter(Line::changes).count());
    }

    private static boolean same(String a, String b) {
        try {
            return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0;
        } catch (NumberFormatException e) {
            return a.equals(b);
        }
    }

    static String tier(int from, Integer to, BigDecimal multiplier) {
        return from + (to == null ? "+ h" : "–" + to + " h") + " ×" + multiplier.stripTrailingZeros().toPlainString();
    }

    /**
     * Applies the pack to the current company: settings and leave days now, overtime and statutory rates from
     * {@code from}. Rules already in force are left alone. Payroll months already closed do not change.
     */
    @Transactional
    public Preview apply(String code, LocalDate from, String by) {
        Pack p = applicable(code);
        if (from == null) {
            throw new BusinessRuleException("Give the day the overtime and other rates start.");
        }
        cd.hrpay.payroll.PayrollRuns.requireOpenMonth(jdbc, YearMonth.from(from));
        Preview before = preview(code, from);
        for (Line l : before.lines()) {
            if (!l.changes() || !l.applies()) {
                continue;
            }
            switch (l.kind()) {
                case "SETTING" -> jdbc.sql("""
                        insert into payroll_setting (key, value) values (?, ?)
                        on conflict (company_id, key) do update set value = excluded.value""").params(l.key(), l.next()).update();
                case "LEAVE_DAYS" -> jdbc.sql("update leave_type set max_days = ? where code = ?")
                        .params(Integer.parseInt(l.next()), l.key()).update();
                case "RATE" -> {
                    int n = jdbc.sql("update statutory_rate set value = ?, note = ?, created_by = ? where code = ? and effective_from = ?")
                            .params(new BigDecimal(l.next()), cut(p.reference()), by, l.key(), from).update();
                    if (n == 0) {
                        jdbc.sql("insert into statutory_rate (code, effective_from, value, note, created_by) values (?, ?, ?, ?, ?)")
                                .params(l.key(), from, new BigDecimal(l.next()), cut(p.reference()), by).update();
                    }
                }
                default -> { }
            }
        }
        if (before.lines().stream().anyMatch(l -> l.kind().equals("OVERTIME") && l.changes())) {
            applyOvertime(p, from);
        }
        jdbc.sql("update company set sector_pack_code = ?, sector_pack_applied_at = now(), sector_pack_applied_by = ? where id = current_company_id()")
                .params(code, by).update();
        audit.record("sector_pack.applied", "sector_pack", code, Map.of("code", code, "from", from.toString(),
                "changes", before.changes(), "by", by));
        return preview(code, from);
    }

    private void applyOvertime(Pack p, LocalDate from) {
        OvertimePolicy current = policies.findFirstByCountryCodeAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(p.countryCode(), from)
                .orElse(null);
        OvertimePolicy policy = policies.findByCountryCodeAndEffectiveFrom(p.countryCode(), from)
                .orElseGet(() -> {
                    OvertimePolicy n = new OvertimePolicy(p.countryCode(), from);
                    if (current != null) {
                        n.setAllowed(current.isAllowed());
                        n.setRequiresApproval(current.isRequiresApproval());
                        n.setRoundingMinutes(current.getRoundingMinutes());
                        n.setMaxWeeklyOvertimeMinutes(current.getMaxWeeklyOvertimeMinutes());
                    }
                    return n;
                });
        Set<String> packKinds = p.rules().stream().filter(r -> "OVERTIME".equals(r.kind())).map(Rule::key).collect(Collectors.toSet());
        List<OvertimeRateTier> tiers = new ArrayList<>();
        // The kinds the pack does not set keep the rates in force.
        if (current != null) {
            current.getTiers().stream().filter(t -> !packKinds.contains(t.getKind().name()))
                    .forEach(t -> tiers.add(new OvertimeRateTier(t.getKind(), t.getFromHour(), t.getToHour(), t.getMultiplier())));
        }
        p.rules().stream().filter(r -> "OVERTIME".equals(r.kind()))
                .forEach(r -> tiers.add(new OvertimeRateTier(OvertimeRateTier.Kind.valueOf(r.key()), r.fromHour(), r.toHour(),
                        new BigDecimal(r.value()))));
        policy.setTiers(tiers);
        policies.save(policy);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return blank(s) ? null : s.trim();
    }

    private static String cut(String s) {
        return s == null ? null : s.length() > 300 ? s.substring(0, 300) : s;
    }
}
