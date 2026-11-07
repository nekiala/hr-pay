package cd.hrpay.legal;

import cd.hrpay.audit.AuditService;
import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.tenancy.TenantContext;
import cd.hrpay.tenancy.Tenants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The provider's terms of service, data processing agreement, privacy policy and service levels. Anyone can read
 * them; a company's administrator accepts each new version of the ones that must be accepted before going on.
 * The starting texts are drafts for a lawyer to review.
 */
@RestController
public class LegalDocuments {

    private static final Logger log = LoggerFactory.getLogger(LegalDocuments.class);

    static final List<String> KINDS = List.of("TERMS", "DPA", "PRIVACY", "SLA");
    static final java.util.Set<String> MUST_ACCEPT = java.util.Set.of("TERMS", "DPA");

    public record Document(String kind, int version, String language, String title, String body, boolean mustAccept,
                           OffsetDateTime publishedAt) {}

    public record Pending(String kind, int version, String title) {}

    public record Agreement(String kind, int version, String acceptedBy, OffsetDateTime acceptedAt) {}

    public record Accept(@NotEmpty List<@Valid Pending> documents) {}

    public record Text(@NotBlank @Size(max = 150) String title, @NotBlank @Size(max = 100_000) String body) {}

    public record Publish(@NotBlank @Pattern(regexp = "TERMS|DPA|PRIVACY|SLA") String kind, @Valid Text fr, @Valid Text en,
                          boolean mustAccept) {}

    private final JdbcClient jdbc;
    private final Tenants tenants;
    private final TransactionTemplate tx;
    private final AuditService audit;

    public LegalDocuments(JdbcClient jdbc, Tenants tenants, TransactionTemplate tx, AuditService audit) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.tx = tx;
        this.audit = audit;
    }

    /** The starting drafts, on a new installation. */
    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        tenants.callAsPlatform(() -> tx.execute(s -> {
            for (String kind : KINDS) {
                if (jdbc.sql("select count(*) from legal_document where kind = ?").param(kind).query(Integer.class).single() > 0) {
                    continue;
                }
                for (String language : List.of("fr", "en")) {
                    String text = draft(kind, language);
                    int newline = text.indexOf('\n');
                    String title = text.substring(0, newline).replaceFirst("^#\\s*", "").trim();
                    jdbc.sql("""
                            insert into legal_document (kind, version, language, title, body, must_accept, published_by)
                            values (?, 1, ?, ?, ?, ?, 'system')""")
                            .params(kind, language, title, text.substring(newline + 1).trim(), MUST_ACCEPT.contains(kind)).update();
                }
                log.info("Draft {} published; have it reviewed by a lawyer.", kind);
            }
            return null;
        }));
    }

    private static String draft(String kind, String language) {
        try {
            return new String(new ClassPathResource("legal/" + kind.toLowerCase() + "." + language + ".md").getInputStream()
                    .readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Missing legal draft " + kind + " " + language, e);
        }
    }

    // ---- anyone ----

    @GetMapping("/api/legal/documents")
    public List<Document> current(@RequestParam(defaultValue = "fr") String language) {
        return tenants.callAsPlatform(() -> KINDS.stream().map(k -> latest(k, language)).filter(java.util.Objects::nonNull).toList());
    }

    @GetMapping("/api/legal/documents/{kind}")
    public Document one(@PathVariable String kind, @RequestParam(defaultValue = "fr") String language) {
        Document d = tenants.callAsPlatform(() -> latest(kind.toUpperCase(), language));
        if (d == null) {
            throw new NotFoundException("Document not found.");
        }
        return d;
    }

    private Document latest(String kind, String language) {
        // The latest version, in the language asked for when it exists in it.
        return jdbc.sql("""
                select kind, version, language, title, body, must_accept, published_at from legal_document
                where kind = ? order by version desc, (language = ?) desc limit 1""")
                .params(kind, "en".equals(language) ? "en" : "fr").query(Document.class).optional().orElse(null);
    }

    // ---- the company's administrator ----

    /** What the company has yet to accept; only asked of its administrators, never of the provider's own company. */
    @GetMapping("/api/legal/pending")
    public List<Pending> pending(@RequestParam(defaultValue = "fr") String language) {
        Long company = TenantContext.companyId();
        if (company == null || TenantContext.isProvider(company) || !CurrentUser.has("role.assign")) {
            return List.of();
        }
        java.util.Set<String> accepted = new java.util.HashSet<>(jdbc.sql("select kind || ':' || version from company_agreement")
                .query(String.class).list());
        return current(language).stream().filter(Document::mustAccept)
                .filter(d -> !accepted.contains(d.kind() + ":" + d.version()))
                .map(d -> new Pending(d.kind(), d.version(), d.title())).toList();
    }

    @PostMapping("/api/legal/accept")
    @PreAuthorize("hasAuthority('role.assign')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(@Valid @RequestBody Accept body, HttpServletRequest request) {
        if (TenantContext.companyId() == null) {
            throw new BusinessRuleException("No company.");
        }
        tx.executeWithoutResult(s -> {
            for (Pending p : body.documents()) {
                Integer current = tenants.callAsPlatform(() -> jdbc.sql("select max(version) from legal_document where kind = ?")
                        .param(p.kind()).query(Integer.class).optional().orElse(null));
                if (current == null || current != p.version()) {
                    throw new BusinessRuleException("A newer version was published. Read it again.");
                }
                jdbc.sql("""
                        insert into company_agreement (kind, version, accepted_by, ip) values (?, ?, ?, ?)
                        on conflict (company_id, kind, version) do nothing""")
                        .params(p.kind(), p.version(), CurrentUser.email(), request.getRemoteAddr()).update();
                audit.record("legal.accepted", "legal_document", p.kind(), Map.of("version", p.version()));
            }
        });
    }

    @GetMapping("/api/legal/agreements")
    @PreAuthorize("hasAuthority('company.manage')")
    public List<Agreement> agreements() {
        return jdbc.sql("select kind, version, accepted_by, accepted_at from company_agreement order by accepted_at desc")
                .query(Agreement.class).list();
    }

    // ---- the provider ----

    @GetMapping("/api/platform/legal")
    @PreAuthorize("hasAuthority('platform.manage')")
    public List<Document> all() {
        requireProvider();
        return tenants.callAsPlatform(() -> jdbc.sql("""
                select kind, version, language, title, body, must_accept, published_at from legal_document
                order by kind, version desc, language""").query(Document.class).list());
    }

    /** A new version in both languages. When it must be accepted, every company's administrator is asked again. */
    @PostMapping("/api/platform/legal")
    @PreAuthorize("hasAuthority('platform.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Document publish(@Valid @RequestBody Publish body) {
        requireProvider();
        if (body.fr() == null || body.en() == null) {
            throw new BusinessRuleException("Write the French and the English text.");
        }
        String by = CurrentUser.email();
        int version = tenants.callAsPlatform(() -> tx.execute(s -> {
            int v = jdbc.sql("select coalesce(max(version), 0) + 1 from legal_document where kind = ?").param(body.kind())
                    .query(Integer.class).single();
            for (Map.Entry<String, Text> e : Map.of("fr", body.fr(), "en", body.en()).entrySet()) {
                jdbc.sql("""
                        insert into legal_document (kind, version, language, title, body, must_accept, published_by)
                        values (?, ?, ?, ?, ?, ?, ?)""")
                        .params(body.kind(), v, e.getKey(), e.getValue().title().trim(), e.getValue().body().trim(),
                                body.mustAccept(), by).update();
            }
            return v;
        }));
        audit.record("legal.published", "legal_document", body.kind(), Map.of("version", version, "mustAccept", body.mustAccept()));
        return one(body.kind(), "fr");
    }

    private static void requireProvider() {
        if (!TenantContext.isProvider(TenantContext.companyId())) {
            throw new AccessDeniedException("Only the provider manages companies.");
        }
    }
}
