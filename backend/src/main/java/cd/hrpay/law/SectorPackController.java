package cd.hrpay.law;

import cd.hrpay.security.CurrentUser;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Sector packs: the public list, the company's choice and apply, the provider's writing. */
@RestController
public class SectorPackController {

    public record ApplyInput(LocalDate from) {}

    private final SectorPacks packs;
    private final Clock clock;

    public SectorPackController(SectorPacks packs, Clock clock) {
        this.packs = packs;
        this.clock = clock;
    }

    /** The packs HR Pay has, for the website and the law page. */
    @GetMapping("/api/public/sector-packs")
    public List<SectorPacks.Pack> published(@RequestParam(required = false) String country, HttpServletResponse response) {
        response.setHeader("Cache-Control", "public, max-age=300");
        return country == null || country.isBlank() ? packs.all().stream().filter(p -> !p.status().equals("RETIRED")).toList()
                : packs.forCountry(country.toUpperCase());
    }

    @GetMapping("/api/sector-packs")
    @PreAuthorize("hasAuthority('masterdata.manage') or hasAuthority('payroll.run')")
    public SectorPacks.CompanySector mine() {
        return packs.forCompany();
    }

    @GetMapping("/api/sector-packs/{code}/preview")
    @PreAuthorize("hasAuthority('masterdata.manage') or hasAuthority('payroll.run')")
    public SectorPacks.Preview preview(@PathVariable String code,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from) {
        return packs.preview(code, from == null ? SectorPacks.defaultFrom(LocalDate.now(clock)) : from);
    }

    @PostMapping("/api/sector-packs/{code}/apply")
    @PreAuthorize("hasAuthority('masterdata.manage')")
    public SectorPacks.Preview apply(@PathVariable String code, @RequestBody ApplyInput in) {
        return packs.apply(code, in.from(), CurrentUser.email());
    }

    // ---- the provider ------------------------------------------------------------------------------------------

    @GetMapping("/api/platform/sector-packs")
    @PreAuthorize("hasAuthority('platform.manage')")
    public List<SectorPacks.Pack> all() {
        return packs.all();
    }

    @PutMapping("/api/platform/sector-packs/{code}")
    @PreAuthorize("hasAuthority('platform.manage')")
    public SectorPacks.Pack save(@PathVariable String code, @RequestBody SectorPacks.PackInput in) {
        return packs.save(code, in, CurrentUser.email());
    }

    @DeleteMapping("/api/platform/sector-packs/{code}")
    @PreAuthorize("hasAuthority('platform.manage')")
    public void delete(@PathVariable String code) {
        packs.delete(code);
    }
}
