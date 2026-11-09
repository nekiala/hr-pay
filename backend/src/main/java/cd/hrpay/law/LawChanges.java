package cd.hrpay.law;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.i18n.Texts;
import cd.hrpay.notify.Notifications;
import cd.hrpay.tenancy.TenantContext;
import cd.hrpay.tenancy.Tenants;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The law and rate changes HR Pay follows: written by the provider, public once published. A change may carry dated
 * rates; publishing it adds each rate to every company of its country that has that rate and no value of its own on
 * that date, and tells the people who run the payroll there. A company may change the value afterwards.
 */
@Service
public class LawChanges {

    public static final Set<String> COUNTRIES = Set.of("CD", "CG", "CM", "GA");
    public static final Set<String> CATEGORIES = Set.of("TAX", "SOCIAL", "WAGE", "LABOUR", "DECLARATION");
    public static final Set<String> IN_APP = Set.of("APPLIED", "COMING", "INFO");

    public record Rate(String code, LocalDate effectiveFrom, BigDecimal value) {}

    public record Change(long id, String countryCode, LocalDate effectiveFrom, String category, String titleEn, String titleFr,
                         String summaryEn, String summaryFr, String reference, String sourceUrl, String inApp,
                         String inAppNoteEn, String inAppNoteFr, String verifiedBy, LocalDate verifiedOn, String status,
                         OffsetDateTime publishedAt, int appliedTo, List<Rate> rates, String sectorPackCode,
                         String sectorNameEn, String sectorNameFr) {}

    /** What the provider writes. */
    public record Draft(String countryCode, LocalDate effectiveFrom, String category, String titleEn, String titleFr,
                        String summaryEn, String summaryFr, String reference, String sourceUrl, String inApp,
                        String inAppNoteEn, String inAppNoteFr, String verifiedBy, LocalDate verifiedOn, List<Rate> rates,
                        String sectorPackCode) {}

    private record Row(long id, String countryCode, LocalDate effectiveFrom, String category, String titleEn, String titleFr,
                       String summaryEn, String summaryFr, String reference, String sourceUrl, String inApp,
                       String inAppNoteEn, String inAppNoteFr, String verifiedBy, LocalDate verifiedOn, String status,
                       OffsetDateTime publishedAt, int appliedTo, String sectorPackCode, String sectorNameEn,
                       String sectorNameFr) {}

    private static final String COLUMNS = """
            id, country_code, effective_from, category, title_en, title_fr, summary_en, summary_fr, reference, source_url,
            in_app, in_app_note_en, in_app_note_fr, verified_by, verified_on, status, published_at, applied_to,
            sector_pack_code, (select s.name_en from sector_pack s where s.code = law_change.sector_pack_code) as sector_name_en,
            (select s.name_fr from sector_pack s where s.code = law_change.sector_pack_code) as sector_name_fr""";

    private final JdbcClient jdbc;
    private final Tenants tenants;
    private final TransactionTemplate tx;
    private final AuditService audit;
    private final Notifications notifications;

    public LawChanges(JdbcClient jdbc, Tenants tenants, TransactionTemplate tx, AuditService audit, Notifications notifications) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.tx = tx;
        this.audit = audit;
        this.notifications = notifications;
    }

    // ---- reading ------------------------------------------------------------------------------------------------

    /** The published changes, newest first, for one country or both. */
    public List<Change> published(String country) {
        return tenants.callAsPlatform(() -> withRates(jdbc.sql("select " + COLUMNS + " " + """
                 from law_change where status = 'PUBLISHED' and (cast(? as text) is null or country_code = ?)
                order by effective_from desc, id desc""").params(country, country).query(Row.class).list()));
    }

    /** Every change, drafts and withdrawn ones included, for the provider. */
    public List<Change> all() {
        return tenants.callAsPlatform(() -> withRates(jdbc.sql("select " + COLUMNS + " " + """
                 from law_change order by case status when 'DRAFT' then 0 else 1 end, effective_from desc, id desc""")
                .query(Row.class).list()));
    }

    /** The changes published for the current company's country in the last days, for its home page. */
    public List<Change> recentForCompany(int days) {
        String[] company = jdbc.sql("select country_code, sector_pack_code from company where id = current_company_id()")
                .query((rs, i) -> new String[] {rs.getString(1), rs.getString(2)}).optional().orElse(null);
        if (company == null) {
            return List.of();
        }
        return tenants.callAsPlatform(() -> withRates(jdbc.sql("select " + COLUMNS + " " + """
                 from law_change where status = 'PUBLISHED' and country_code = ? and published_at > now() - make_interval(days => ?)
                  and (sector_pack_code is null or sector_pack_code = ?)
                order by published_at desc""").params(company[0], days, company[1]).query(Row.class).list()));
    }

    public Change get(long id) {
        return tenants.callAsPlatform(() -> withRates(jdbc.sql("select " + COLUMNS + " from law_change where id = ?").param(id)
                .query(Row.class).list())).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Change not found."));
    }

    private List<Change> withRates(List<Row> rows) {
        List<Change> out = new ArrayList<>();
        for (Row r : rows) {
            List<Rate> rates = jdbc.sql("select code, effective_from, value from law_change_rate where change_id = ? order by effective_from, code")
                    .param(r.id()).query(Rate.class).list();
            out.add(new Change(r.id(), r.countryCode(), r.effectiveFrom(), r.category(), r.titleEn(), r.titleFr(), r.summaryEn(),
                    r.summaryFr(), r.reference(), r.sourceUrl(), r.inApp(), r.inAppNoteEn(), r.inAppNoteFr(), r.verifiedBy(),
                    r.verifiedOn(), r.status(), r.publishedAt(), r.appliedTo(), rates, r.sectorPackCode(), r.sectorNameEn(),
                    r.sectorNameFr()));
        }
        return out;
    }

    // ---- writing (the provider) -----------------------------------------------------------------------------------

    private static void check(Draft d) {
        if (d.countryCode() == null || !COUNTRIES.contains(d.countryCode())) {
            throw new BusinessRuleException("Choose the country: DR Congo or Republic of the Congo.");
        }
        if (d.category() == null || !CATEGORIES.contains(d.category()) || d.inApp() == null || !IN_APP.contains(d.inApp())) {
            throw new BusinessRuleException("Choose the kind of change and what HR Pay does about it.");
        }
        if (d.effectiveFrom() == null || blank(d.titleEn()) || blank(d.titleFr()) || blank(d.summaryEn()) || blank(d.summaryFr())
                || blank(d.reference())) {
            throw new BusinessRuleException("A change needs its date, a title and a summary in both languages, and its legal reference.");
        }
        if (d.sourceUrl() != null && !d.sourceUrl().isBlank() && !d.sourceUrl().startsWith("https://")) {
            throw new BusinessRuleException("The source link must start with https://.");
        }
        if (d.sectorPackCode() != null && !d.sectorPackCode().isBlank() && !d.sectorPackCode().matches("[A-Z0-9_]{2,40}")) {
            throw new BusinessRuleException("Unknown sector pack.");
        }
        for (Rate r : d.rates() == null ? List.<Rate>of() : d.rates()) {
            if (r.code() == null || !r.code().matches("[A-Z0-9_]{2,40}") || r.effectiveFrom() == null || r.value() == null
                    || r.value().signum() < 0) {
                throw new BusinessRuleException("Each rate needs its code, its date and a value of zero or more.");
            }
        }
    }

    private String sector(Draft d) {
        if (d.sectorPackCode() == null || d.sectorPackCode().isBlank()) {
            return null;
        }
        String country = tenants.callAsPlatform(() -> jdbc.sql("select country_code from sector_pack where code = ?")
                .param(d.sectorPackCode()).query(String.class).optional().orElse(null));
        if (country == null || !country.equals(d.countryCode())) {
            throw new BusinessRuleException("Unknown sector pack for this country.");
        }
        return d.sectorPackCode();
    }

    public Change create(Draft d, String by) {
        check(d);
        String sector = sector(d);
        long id = tenants.callAsPlatform(() -> tx.execute(s -> {
            long n = jdbc.sql("""
                    insert into law_change (country_code, effective_from, category, title_en, title_fr, summary_en, summary_fr,
                        reference, source_url, in_app, in_app_note_en, in_app_note_fr, verified_by, verified_on, created_by,
                        sector_pack_code)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(d.countryCode(), d.effectiveFrom(), d.category(), d.titleEn().trim(), d.titleFr().trim(),
                            d.summaryEn().trim(), d.summaryFr().trim(), d.reference().trim(), trim(d.sourceUrl()), d.inApp(),
                            trim(d.inAppNoteEn()), trim(d.inAppNoteFr()), trim(d.verifiedBy()), d.verifiedOn(), by, sector)
                    .query(Long.class).single();
            saveRates(n, d.rates());
            return n;
        }));
        audit.record("law_change.created", "law_change", id, Map.of("title", d.titleEn()));
        return get(id);
    }

    /** A draft can change freely; a published change only its words, its source and who verified it. */
    public Change update(long id, Draft d, String by) {
        check(d);
        String sector = sector(d);
        Change old = get(id);
        if (!old.status().equals("DRAFT") && (!old.countryCode().equals(d.countryCode())
                || !sameRates(old.rates(), d.rates() == null ? List.of() : d.rates())
                || !java.util.Objects.equals(old.sectorPackCode(), sector))) {
            throw new BusinessRuleException("A published change keeps its country, its sector and its rates; withdraw it and write a new one.");
        }
        tenants.callAsPlatform(() -> tx.execute(s -> {
            jdbc.sql("""
                    update law_change set country_code = ?, effective_from = ?, category = ?, title_en = ?, title_fr = ?,
                        summary_en = ?, summary_fr = ?, reference = ?, source_url = ?, in_app = ?, in_app_note_en = ?,
                        in_app_note_fr = ?, verified_by = ?, verified_on = ?, sector_pack_code = ?, updated_at = now() where id = ?""")
                    .params(d.countryCode(), d.effectiveFrom(), d.category(), d.titleEn().trim(), d.titleFr().trim(),
                            d.summaryEn().trim(), d.summaryFr().trim(), d.reference().trim(), trim(d.sourceUrl()), d.inApp(),
                            trim(d.inAppNoteEn()), trim(d.inAppNoteFr()), trim(d.verifiedBy()), d.verifiedOn(), sector, id).update();
            if (old.status().equals("DRAFT")) {
                jdbc.sql("delete from law_change_rate where change_id = ?").param(id).update();
                saveRates(id, d.rates());
            }
            return null;
        }));
        audit.record("law_change.updated", "law_change", id, Map.of("by", by));
        return get(id);
    }

    private static boolean sameRates(List<Rate> a, List<Rate> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Rate r : b) {
            if (a.stream().noneMatch(x -> x.code().equals(r.code()) && x.effectiveFrom().equals(r.effectiveFrom())
                    && x.value().compareTo(r.value()) == 0)) {
                return false;
            }
        }
        return true;
    }

    private void saveRates(long id, List<Rate> rates) {
        for (Rate r : rates == null ? List.<Rate>of() : rates) {
            jdbc.sql("insert into law_change_rate (change_id, code, effective_from, value) values (?, ?, ?, ?) on conflict do nothing")
                    .params(id, r.code(), r.effectiveFrom(), r.value()).update();
        }
    }

    public void delete(long id) {
        Change c = get(id);
        if (!c.status().equals("DRAFT")) {
            throw new BusinessRuleException("Only a draft can be deleted; withdraw a published change.");
        }
        tenants.callAsPlatform(() -> jdbc.sql("delete from law_change where id = ?").param(id).update());
        audit.record("law_change.deleted", "law_change", id, Map.of("title", c.titleEn()));
    }

    /** Takes a change off the public page. The rates it gave the companies stay: each company decides. */
    public Change withdraw(long id, String by) {
        Change c = get(id);
        if (!c.status().equals("PUBLISHED")) {
            throw new BusinessRuleException("Only a published change can be withdrawn.");
        }
        tenants.callAsPlatform(() -> jdbc.sql("update law_change set status = 'WITHDRAWN', updated_at = now() where id = ?").param(id).update());
        audit.record("law_change.withdrawn", "law_change", id, Map.of("by", by));
        return get(id);
    }

    /** Publishes a draft: on the public page, and its rates into every company of the country. */
    public Change publish(long id, String by) {
        Change c = get(id);
        if (!c.status().equals("DRAFT")) {
            throw new BusinessRuleException("This change is already published.");
        }
        tenants.callAsPlatform(() -> jdbc.sql("update law_change set status = 'PUBLISHED', published_by = ?, published_at = now(), updated_at = now() where id = ?")
                .params(by, id).update());
        int applied = 0;
        if (!c.rates().isEmpty() || c.sectorPackCode() != null) {
            // A sector's change reaches only the companies on that sector pack.
            List<Long> companies = tenants.callAsPlatform(() -> jdbc.sql("""
                    select id from company where country_code = ? and kind = 'COMPANY'
                      and (cast(? as text) is null or sector_pack_code = ?) order by id""")
                    .params(c.countryCode(), c.sectorPackCode(), c.sectorPackCode()).query(Long.class).list());
            for (long company : companies) {
                if (tenants.callAs(company, () -> applyHere(c))) {
                    applied++;
                }
            }
        }
        int count = applied;
        tenants.callAsPlatform(() -> jdbc.sql("update law_change set applied_to = ? where id = ?").params(count, id).update());
        audit.record("law_change.published", "law_change", id, Map.of("title", c.titleEn(), "companies", count));
        return get(id);
    }

    /**
     * Adds the change's rates to the current company: only rates the company has, and never over a value it set for
     * the same date. Tells those who run the payroll. True when a rate was added.
     */
    boolean applyHere(Change c) {
        Boolean added = tx.execute(s -> {
            int n = 0;
            for (Rate r : c.rates()) {
                n += jdbc.sql("""
                        insert into statutory_rate (code, effective_from, value, note, created_by, law_change_id)
                        select ?, ?, ?, ?, 'HR Pay', ?
                        where exists (select 1 from statutory_rate_type t where t.code = ?)
                          and not exists (select 1 from statutory_rate x where x.code = ? and x.effective_from = ?)""")
                        .params(r.code(), r.effectiveFrom(), r.value(), cut(c.reference(), 300), c.id(), r.code(), r.code(),
                                r.effectiveFrom()).update();
            }
            if (n > 0) {
                audit.recordAs("HR Pay", "statutory.rate_from_law", "law_change", c.id(), Map.of("title", c.titleEn(), "rates", n));
            }
            return n > 0;
        });
        if (c.sectorPackCode() != null) {
            // The company's agreement changed: those who keep the settings review the pack and apply it again.
            for (Long user : notifications.holders("masterdata.manage")) {
                notifications.send(user, "law.sector", () -> Texts.t("Your sector agreement changed: %s", title(c)),
                        () -> Texts.t("From %s. %s Review the sector pack and apply it again.", Texts.day(c.effectiveFrom()),
                                c.reference()), "/payroll/sector");
            }
        }
        if (Boolean.TRUE.equals(added)) {
            for (Long user : notifications.holders("payroll.run")) {
                notifications.send(user, "law.change", () -> Texts.t("New rate: %s", title(c)),
                        () -> Texts.t("From %s. %s Payrolls already closed do not change; recalculate an open month it concerns.",
                                Texts.day(c.effectiveFrom()), c.reference()), "/changes#" + c.id());
            }
        }
        return Boolean.TRUE.equals(added);
    }

    /** For a new company: the published rates of its country that its starting set-up does not have yet. */
    public void applyAllHere(String country) {
        if (TenantContext.companyId() == null) {
            return;
        }
        for (Change c : published(country)) {
            if (!c.rates().isEmpty() && c.sectorPackCode() == null) {
                tx.execute(s -> {
                    for (Rate r : c.rates()) {
                        jdbc.sql("""
                                insert into statutory_rate (code, effective_from, value, note, created_by, law_change_id)
                                select ?, ?, ?, ?, 'HR Pay', ?
                                where exists (select 1 from statutory_rate_type t where t.code = ?)
                                  and not exists (select 1 from statutory_rate x where x.code = ? and x.effective_from = ?)""")
                                .params(r.code(), r.effectiveFrom(), r.value(), cut(c.reference(), 300), c.id(), r.code(),
                                        r.code(), r.effectiveFrom()).update();
                        jdbc.sql("update statutory_rate set law_change_id = ? where code = ? and effective_from = ? and law_change_id is null")
                                .params(c.id(), r.code(), r.effectiveFrom()).update();
                    }
                    return null;
                });
            }
        }
    }

    private static String title(Change c) {
        return Texts.french() ? c.titleFr() : c.titleEn();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
