package cd.hrpay.reference;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The reference lists: anyone signed in reads them; HR adds for the company, HR Pay keeps the shared ones. */
@RestController
@RequestMapping("/api/reference")
public class ReferenceController {

    private final References refs;

    public ReferenceController(References refs) {
        this.refs = refs;
    }

    @GetMapping("/countries")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> countries() {
        return Map.of("countries", refs.countries(), "withPlaces", refs.countriesWithPlaces(), "canShare", References.platform());
    }

    @GetMapping("/places")
    @PreAuthorize("isAuthenticated()")
    public List<References.Place> places(@RequestParam String country, @RequestParam(required = false) Long parent,
                                         @RequestParam(defaultValue = "false") boolean all) {
        return refs.places(country, parent, all);
    }

    @GetMapping("/places/{id}/path")
    @PreAuthorize("isAuthenticated()")
    public List<References.Place> path(@PathVariable long id) {
        return refs.path(id);
    }

    @PostMapping("/places")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('masterdata.manage') or hasAuthority('platform.manage')")
    public References.Place addPlace(@RequestBody References.PlaceInput in) {
        return refs.addPlace(in);
    }

    @PutMapping("/places/{id}")
    @PreAuthorize("hasAuthority('masterdata.manage') or hasAuthority('platform.manage')")
    public References.Place updatePlace(@PathVariable long id, @RequestBody References.PlaceInput in) {
        return refs.updatePlace(id, in);
    }

    @GetMapping("/institutions")
    @PreAuthorize("isAuthenticated()")
    public List<References.Institution> institutions(@RequestParam(required = false) String country,
                                                     @RequestParam(required = false) String kind,
                                                     @RequestParam(defaultValue = "false") boolean all) {
        return refs.institutions(country, kind, all);
    }

    @PostMapping("/institutions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('masterdata.manage') or hasAuthority('platform.manage')")
    public References.Institution addInstitution(@RequestBody References.InstitutionInput in) {
        return refs.addInstitution(in);
    }

    @PutMapping("/institutions/{id}")
    @PreAuthorize("hasAuthority('masterdata.manage') or hasAuthority('platform.manage')")
    public References.Institution updateInstitution(@PathVariable long id, @RequestBody References.InstitutionInput in) {
        return refs.updateInstitution(id, in);
    }

    @GetMapping("/cities")
    @PreAuthorize("isAuthenticated()")
    public List<References.City> cities(@RequestParam String country) {
        return refs.cities(country);
    }

    @GetMapping("/branches")
    @PreAuthorize("isAuthenticated()")
    public List<References.ActivityBranch> branches(@RequestParam(defaultValue = "false") boolean all) {
        return refs.branches(all);
    }

    @PostMapping("/branches")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('platform.manage')")
    public References.ActivityBranch addBranch(@RequestBody References.BranchInput in) {
        return refs.addBranch(in);
    }

    @PutMapping("/branches/{id}")
    @PreAuthorize("hasAuthority('platform.manage')")
    public References.ActivityBranch updateBranch(@PathVariable long id, @RequestBody References.BranchInput in) {
        return refs.updateBranch(id, in);
    }

    @GetMapping("/to-pick")
    @PreAuthorize("hasAuthority('salary.read')")
    public List<References.ToPick> toPick() {
        return refs.toPick();
    }
}
