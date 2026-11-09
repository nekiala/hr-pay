package cd.hrpay.law;

import cd.hrpay.security.CurrentUser;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The law and rate changes: public reading and feed, the company's recent ones, the provider's writing. */
@RestController
public class LawChangeController {

    private final LawChanges changes;

    public LawChangeController(LawChanges changes) {
        this.changes = changes;
    }

    private static String country(String c) {
        return c == null || c.isBlank() ? null : c.toUpperCase();
    }

    /** The published changes, for anyone, from the app or the website. */
    @GetMapping("/api/public/changes")
    public List<LawChanges.Change> published(@RequestParam(required = false) String country, HttpServletResponse response) {
        response.setHeader("Cache-Control", "public, max-age=300");
        return changes.published(country(country));
    }

    /** The same as an RSS feed, in French or English. */
    @GetMapping("/api/public/changes.rss")
    public void feed(@RequestParam(required = false) String country, @RequestParam(defaultValue = "fr") String lang,
                     HttpServletResponse response) throws IOException {
        boolean fr = !"en".equals(lang);
        response.setContentType("application/rss+xml");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "public, max-age=300");
        PrintWriter out = response.getWriter();
        out.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        out.println("<rss version=\"2.0\"><channel>");
        out.println("<title>" + (fr ? "HR Pay : changements de lois et de taux" : "HR Pay: law and rate changes") + "</title>");
        out.println("<link>/changes</link>");
        out.println("<description>" + (fr ? "Paie et déclarations en RDC et au Congo" : "Payroll and declarations in the DRC and the Congo")
                + "</description>");
        for (LawChanges.Change c : changes.published(country(country))) {
            out.println("<item>");
            out.println("<title>" + xml(c.countryCode() + " · " + (fr ? c.titleFr() : c.titleEn())) + "</title>");
            out.println("<link>/changes#" + c.id() + "</link>");
            out.println("<guid isPermaLink=\"false\">hrpay-law-change-" + c.id() + "</guid>");
            if (c.publishedAt() != null) {
                out.println("<pubDate>" + DateTimeFormatter.RFC_1123_DATE_TIME.format(c.publishedAt().withOffsetSameInstant(ZoneOffset.UTC)) + "</pubDate>");
            }
            out.println("<description>" + xml((fr ? "En vigueur le " : "In force from ") + c.effectiveFrom() + ". "
                    + (fr ? c.summaryFr() : c.summaryEn()) + " (" + c.reference() + ")") + "</description>");
            out.println("</item>");
        }
        out.println("</channel></rss>");
        out.flush();
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** The changes published for the company's country lately, for its home page. */
    @GetMapping("/api/changes/recent")
    @PreAuthorize("hasAuthority('payroll.run') or hasAuthority('masterdata.manage') or hasAuthority('declaration.manage')")
    public List<LawChanges.Change> recent() {
        return changes.recentForCompany(30);
    }

    // ---- the provider ------------------------------------------------------------------------------------------

    @GetMapping("/api/platform/changes")
    @PreAuthorize("hasAuthority('platform.manage')")
    public List<LawChanges.Change> all() {
        return changes.all();
    }

    @PostMapping("/api/platform/changes")
    @PreAuthorize("hasAuthority('platform.manage')")
    public LawChanges.Change create(@RequestBody LawChanges.Draft d) {
        return changes.create(d, CurrentUser.email());
    }

    @PutMapping("/api/platform/changes/{id}")
    @PreAuthorize("hasAuthority('platform.manage')")
    public LawChanges.Change update(@PathVariable long id, @RequestBody LawChanges.Draft d) {
        return changes.update(id, d, CurrentUser.email());
    }

    @DeleteMapping("/api/platform/changes/{id}")
    @PreAuthorize("hasAuthority('platform.manage')")
    public void delete(@PathVariable long id) {
        changes.delete(id);
    }

    @PostMapping("/api/platform/changes/{id}/publish")
    @PreAuthorize("hasAuthority('platform.manage')")
    public LawChanges.Change publish(@PathVariable long id) {
        return changes.publish(id, CurrentUser.email());
    }

    @PostMapping("/api/platform/changes/{id}/withdraw")
    @PreAuthorize("hasAuthority('platform.manage')")
    public LawChanges.Change withdraw(@PathVariable long id) {
        return changes.withdraw(id, CurrentUser.email());
    }
}
