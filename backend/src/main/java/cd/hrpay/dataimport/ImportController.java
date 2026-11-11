package cd.hrpay.dataimport;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.Sheet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/imports")
@PreAuthorize("hasAuthority('data.import')")
public class ImportController {

    /** The file as base64, at most about 5 MB. */
    public record FileInput(@Size(max = 200) String fileName, @NotBlank @Size(max = 7_000_000) String content) {
        byte[] bytes() {
            try {
                String b64 = content.contains(",") && content.startsWith("data:") ? content.substring(content.indexOf(',') + 1) : content;
                return Base64.getDecoder().decode(b64.trim());
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleException("The file could not be read.");
            }
        }
    }

    public record KindView(Imports.Kind kind, List<Imports.Column> columns, boolean canFill) {}

    private final Imports imports;
    private final cd.hrpay.job.BackgroundJobs jobs;

    public ImportController(Imports imports, cd.hrpay.job.BackgroundJobs jobs) {
        this.jobs = jobs;
        this.imports = imports;
    }

    @GetMapping("/kinds")
    public List<KindView> kinds() {
        return Arrays.stream(Imports.Kind.values()).map(k -> new KindView(k, imports.columns(k), Imports.FILLED.contains(k))).toList();
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history() {
        return imports.history();
    }

    @GetMapping({"/{kind}/template.xlsx", "/{kind}/template.csv"})
    public void template(@PathVariable String kind, @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean filled,
                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        Imports.Kind k = kind(kind);
        // Filled first: a refusal is still an ordinary error answer, before the file starts.
        List<List<Object>> rows = filled ? imports.filled(k) : List.of();
        Sheet sheet = Sheet.open(request, response, "hr-pay-" + k.name().toLowerCase(Locale.ROOT).replace('_', '-')
                + (filled ? "-current" : ""));
        sheet.row(imports.columns(k).stream().map(c -> (Object) (c.header() + (c.required() ? " *" : ""))).toArray());
        for (List<Object> r : rows) {
            sheet.row(r.toArray());
        }
        sheet.finish();
    }

    @PostMapping("/{kind}/check")
    public Imports.Result check(@PathVariable String kind, @Valid @RequestBody FileInput in) {
        return imports.run(kind(kind), in.fileName(), in.bytes(), false);
    }

    @PostMapping("/{kind}")
    public org.springframework.http.ResponseEntity<Object> apply(@PathVariable String kind, @Valid @RequestBody FileInput in) {
        Imports.Kind k = kind(kind);
        String name = in.fileName() == null || in.fileName().isBlank() ? "import" : in.fileName();
        return jobs.answer(jobs.submit("import", "import:" + k.name(),
                Map.of("kind", k.name(), "apply", true, "fileName", name),
                new cd.hrpay.job.JobFile(name, "application/octet-stream", in.bytes())));
    }

    private static Imports.Kind kind(String v) {
        try {
            return Imports.Kind.valueOf(v.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("Unknown import %s.".formatted(v));
        }
    }
}
