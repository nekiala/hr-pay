package cd.hrpay.integration;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Export layouts, and the files written with them. */
@RestController
@RequestMapping("/api")
public class ExportLayoutController {

    public record LayoutInput(@NotBlank String target, @NotBlank @Size(max = 100) String name,
                              @NotEmpty @Size(max = 40) List<ExportLayouts.Column> columns, @NotBlank String separator,
                              boolean decimalComma, @NotBlank @Size(max = 20) String dateFormat, boolean headerRow,
                              @NotBlank String encoding, @NotBlank String extension) {
        ExportLayouts.Layout layout() {
            return new ExportLayouts.Layout(0, target, name, columns, separator, decimalComma, dateFormat, headerRow, encoding,
                    extension, null, null);
        }
    }

    private final ExportLayouts layouts;

    public ExportLayoutController(ExportLayouts layouts) {
        this.layouts = layouts;
    }

    private static void requireFor(String target) {
        boolean ok = "ACCOUNTING".equals(target) ? CurrentUser.has("accounting.export") : CurrentUser.has("payroll.pay");
        if (!ok) {
            throw new org.springframework.security.access.AccessDeniedException("Not allowed.");
        }
    }

    @GetMapping("/export-layouts/fields")
    @PreAuthorize("hasAuthority('accounting.export') or hasAuthority('payroll.pay')")
    public Map<String, Map<String, String>> fields() {
        Map<String, Map<String, String>> out = new java.util.LinkedHashMap<>();
        ExportLayouts.FIELDS.forEach((target, fields) -> {
            Map<String, String> f = new java.util.LinkedHashMap<>();
            fields.forEach((k, v) -> f.put(k, cd.hrpay.i18n.Texts.t(v)));
            out.put(target, f);
        });
        return out;
    }

    @GetMapping("/export-layouts")
    @PreAuthorize("hasAuthority('accounting.export') or hasAuthority('payroll.pay')")
    public List<ExportLayouts.Layout> list(@RequestParam(required = false) String target) {
        return layouts.list(target).stream().filter(l -> "ACCOUNTING".equals(l.target())
                ? CurrentUser.has("accounting.export") : CurrentUser.has("payroll.pay")).toList();
    }

    @PostMapping("/export-layouts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('accounting.export') or hasAuthority('payroll.pay')")
    public ExportLayouts.Layout create(@Valid @RequestBody LayoutInput in) {
        requireFor(in.target());
        return layouts.save(null, in.layout());
    }

    @PutMapping("/export-layouts/{id}")
    @PreAuthorize("hasAuthority('accounting.export') or hasAuthority('payroll.pay')")
    public ExportLayouts.Layout update(@PathVariable long id, @Valid @RequestBody LayoutInput in) {
        requireFor(layouts.find(id).target());
        requireFor(in.target());
        return layouts.save(id, in.layout());
    }

    @DeleteMapping("/export-layouts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('accounting.export') or hasAuthority('payroll.pay')")
    public void delete(@PathVariable long id) {
        requireFor(layouts.find(id).target());
        layouts.delete(id);
    }

    @GetMapping("/accounting/{month}/layout/{layoutId}")
    @PreAuthorize("hasAuthority('accounting.export')")
    public ResponseEntity<byte[]> journal(@PathVariable YearMonth month, @PathVariable long layoutId,
                                          @RequestParam(defaultValue = "false") boolean byCostCentre) {
        return file(layouts.journal(layoutId, month, byCostCentre));
    }

    @GetMapping("/payment-batches/{id}/layout/{layoutId}")
    @PreAuthorize("hasAuthority('payroll.pay')")
    public ResponseEntity<byte[]> payment(@PathVariable long id, @PathVariable long layoutId) {
        return file(layouts.payment(layoutId, id));
    }

    private static ResponseEntity<byte[]> file(ExportLayouts.File f) {
        if (f.content().length == 0) {
            throw new BusinessRuleException("The file is empty.");
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType()))
                .header("Content-Disposition", "attachment; filename=\"" + f.name() + "\"").body(f.content());
    }
}
