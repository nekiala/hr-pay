package cd.hrpay.lifecycle;

import cd.hrpay.i18n.Texts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
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

/** HR's side of the employee's life in the company: checklists, documents, equipment, letters, certificates. */
@RestController
@RequestMapping("/api/lifecycle")
@PreAuthorize("hasAuthority('lifecycle.manage')")
public class LifecycleController {

    public record TypeInput(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 100) String name, boolean hasExpiry,
                            @Min(1) @Max(365) int remindDays, boolean employeeCanUpload, boolean employeeCanSee, int position,
                            boolean active) {}

    public record StepInput(@NotBlank String kind, int position, @NotBlank @Size(max = 200) String label, Long documentTypeId,
                            String action, boolean active) {}

    public record TemplateInput(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 100) String name, @NotBlank String kind,
                                @NotBlank @Size(max = 50_000) String body, boolean requiresSignature, Long documentTypeId,
                                boolean active, @Size(max = 20) String docCode, @Size(max = 20) String docVersion,
                                @Size(max = 60) String docClassification, @Size(max = 100) String docOwner,
                                @Size(max = 100) String docApprover, LocalDate docAdoptedOn, LocalDate docEffectiveOn) {

        Letters.Control control() {
            return new Letters.Control(docCode, docVersion, docClassification, docOwner, docApprover, docAdoptedOn, docEffectiveOn);
        }
    }

    public record TermsInput(@jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(6) Integer trialMonths,
                             Boolean unskilledLabourer, @Size(max = 16) String fixedTermReason,
                             @Size(max = 200) String fixedTermDetail, Boolean permanentJob, @Size(max = 200) String workPlace,
                             @Size(max = 150) String supervisor, @Size(max = 100) String signedPlace, LocalDate signedOn,
                             LocalDate draftGivenOn, LocalDate medicalFitOn, @Size(max = 150) String medicalDoctor,
                             @Size(max = 40) String onemVisaNo, LocalDate onemVisaOn) {}

    public record StartInput(@NotBlank String kind, LocalDate dueOn) {}

    public record TickInput(boolean done, @Size(max = 300) String note) {}

    public record CloseInput(boolean cancel) {}

    public record DocumentInput(@NotNull Long typeId, @Size(max = 200) String fileName, @NotBlank @Size(max = 14_000_000) String content,
                                LocalDate issuedOn, LocalDate expiresOn, @Size(max = 300) String note) {}

    public record EquipmentInput(@NotBlank @Size(max = 60) String kind, @Size(max = 200) String description,
                                 @Size(max = 80) String serialNo, @NotNull LocalDate issuedOn, @Size(max = 300) String note) {}

    public record ReturnInput(@NotNull LocalDate on, @Size(max = 300) String note) {}

    public record PreviewInput(@NotNull Long templateId, Long contractId) {}

    public record LetterInput(Long templateId, @NotBlank @Size(max = 150) String title, @NotBlank @Size(max = 50_000) String body,
                              String kind, boolean requiresSignature, Long contractId) {}

    public record CertificateInput(@NotBlank String kind, @Size(max = 200) String purpose) {}

    public record RejectInput(@NotBlank @Size(max = 300) String reason) {}

    public record Overview(List<Checklists.Open> checklists, List<Documents.Expiring> expiring, List<Certificates.Request> certificates,
                           List<Letters.Letter> letters, List<Checklists.Out> equipment) {}

    public record Person(List<Checklists.Checklist> checklists, List<Documents.Doc> documents, List<Checklists.Equipment> equipment,
                         List<Letters.Letter> letters, List<Certificates.Request> certificates) {}

    private final Checklists checklists;
    private final Documents documents;
    private final Letters letters;
    private ContractTerms contractTerms;

    @org.springframework.beans.factory.annotation.Autowired
    void setContractTerms(ContractTerms contractTerms) {
        this.contractTerms = contractTerms;
    }
    private final Certificates certificates;

    public LifecycleController(Checklists checklists, Documents documents, Letters letters, Certificates certificates) {
        this.checklists = checklists;
        this.documents = documents;
        this.letters = letters;
        this.certificates = certificates;
    }

    @GetMapping("/overview")
    public Overview overview(@RequestParam(defaultValue = "60") int days) {
        return new Overview(checklists.open(), documents.expiring(Math.min(Math.max(days, 1), 365)), certificates.pending(),
                letters.waiting(), checklists.outstandingForLeavers());
    }

    // ---- settings -----------------------------------------------------------------------------------------------

    @GetMapping("/document-types")
    public List<Documents.Type> types() {
        return documents.types();
    }

    @PostMapping("/document-types")
    @ResponseStatus(HttpStatus.CREATED)
    public Documents.Type addType(@Valid @RequestBody TypeInput in) {
        return documents.saveType(null, in.code(), in.name(), in.hasExpiry(), in.remindDays(), in.employeeCanUpload(),
                in.employeeCanSee(), in.position(), in.active());
    }

    @PutMapping("/document-types/{id}")
    public Documents.Type updateType(@PathVariable long id, @Valid @RequestBody TypeInput in) {
        return documents.saveType(id, in.code(), in.name(), in.hasExpiry(), in.remindDays(), in.employeeCanUpload(),
                in.employeeCanSee(), in.position(), in.active());
    }

    @GetMapping("/checklist-steps")
    public List<Checklists.TemplateItem> steps(@RequestParam(required = false) String kind) {
        return checklists.template(kind);
    }

    @PostMapping("/checklist-steps")
    @ResponseStatus(HttpStatus.CREATED)
    public Checklists.TemplateItem addStep(@Valid @RequestBody StepInput in) {
        return checklists.saveTemplateItem(null, in.kind(), in.position(), in.label(), in.documentTypeId(), blank(in.action()), in.active());
    }

    @PutMapping("/checklist-steps/{id}")
    public Checklists.TemplateItem updateStep(@PathVariable long id, @Valid @RequestBody StepInput in) {
        return checklists.saveTemplateItem(id, in.kind(), in.position(), in.label(), in.documentTypeId(), blank(in.action()), in.active());
    }

    @DeleteMapping("/checklist-steps/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStep(@PathVariable long id) {
        checklists.deleteTemplateItem(id);
    }

    @GetMapping("/templates")
    public List<Letters.Template> templates() {
        return letters.templates();
    }

    @GetMapping("/template-fields")
    public Map<String, String> fields() {
        Map<String, String> out = new LinkedHashMap<>();
        Letters.FIELDS.forEach((k, v) -> out.put(k, Texts.t(v)));
        return out;
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public Letters.Template addTemplate(@Valid @RequestBody TemplateInput in) {
        return letters.saveTemplate(null, in.code(), in.name(), in.kind(), in.body(), in.requiresSignature(), in.documentTypeId(), in.active(),
                in.control());
    }

    @PutMapping("/templates/{id}")
    public Letters.Template updateTemplate(@PathVariable long id, @Valid @RequestBody TemplateInput in) {
        return letters.saveTemplate(id, in.code(), in.name(), in.kind(), in.body(), in.requiresSignature(), in.documentTypeId(), in.active(),
                in.control());
    }

    // ---- one person -----------------------------------------------------------------------------------------------

    @GetMapping("/people/{userId}")
    public Person person(@PathVariable long userId) {
        return new Person(checklists.of(userId), documents.of(userId, false), checklists.equipment(userId), letters.of(userId),
                certificates.of(userId));
    }

    @PostMapping("/people/{userId}/checklists")
    @ResponseStatus(HttpStatus.CREATED)
    public Checklists.Checklist start(@PathVariable long userId, @Valid @RequestBody StartInput in) {
        return checklists.start(userId, in.kind(), in.dueOn());
    }

    @PostMapping("/checklist-items/{id}")
    public Checklists.Checklist tick(@PathVariable long id, @Valid @RequestBody TickInput in) {
        return checklists.tick(id, in.done(), in.note());
    }

    @PostMapping("/checklists/{id}/close")
    public Checklists.Checklist close(@PathVariable long id, @RequestBody CloseInput in) {
        return checklists.close(id, in.cancel());
    }

    @PostMapping("/people/{userId}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public Documents.Doc addDocument(@PathVariable long userId, @Valid @RequestBody DocumentInput in) {
        return documents.add(userId, in.typeId(), in.fileName(), Files.decode(in.content()), in.issuedOn(), in.expiresOn(), in.note(), "HR");
    }

    @GetMapping("/documents/{id}/file")
    public ResponseEntity<byte[]> document(@PathVariable long id) {
        Documents.File f = documents.file(id);
        return Files.inline(f.fileName(), f.contentType(), f.data());
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable long id) {
        documents.delete(id);
    }

    @PostMapping("/people/{userId}/equipment")
    @ResponseStatus(HttpStatus.CREATED)
    public Checklists.Equipment issue(@PathVariable long userId, @Valid @RequestBody EquipmentInput in) {
        return checklists.issue(userId, in.kind(), in.description(), in.serialNo(), in.issuedOn(), in.note());
    }

    @PostMapping("/equipment/{id}/return")
    public Checklists.Equipment returned(@PathVariable long id, @Valid @RequestBody ReturnInput in) {
        return checklists.returned(id, in.on(), in.note());
    }

    @DeleteMapping("/equipment/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEquipment(@PathVariable long id) {
        checklists.deleteEquipment(id);
    }

    @PostMapping("/people/{userId}/letters/preview")
    public Letters.Draft preview(@PathVariable long userId, @Valid @RequestBody PreviewInput in) {
        return letters.preview(userId, in.templateId(), in.contractId());
    }

    @PostMapping("/people/{userId}/letters")
    @ResponseStatus(HttpStatus.CREATED)
    public Letters.Letter createLetter(@PathVariable long userId, @Valid @RequestBody LetterInput in) {
        return letters.create(userId, in.templateId(), in.title(), in.body(), in.kind(), in.requiresSignature(), in.contractId());
    }

    @PutMapping("/letters/{id}")
    public Letters.Letter updateLetter(@PathVariable long id, @Valid @RequestBody LetterInput in) {
        return letters.update(id, in.title(), in.body(), in.requiresSignature());
    }

    @PostMapping("/letters/{id}/send")
    public Letters.Letter send(@PathVariable long id) {
        return letters.send(id);
    }

    @PostMapping("/letters/{id}/cancel")
    public Letters.Letter cancel(@PathVariable long id) {
        return letters.cancel(id);
    }

    @GetMapping("/letters/{id}/word")
    public ResponseEntity<byte[]> wordCopy(@PathVariable long id) {
        Letters.Letter l = letters.find(id);
        String name = (l.number() == null ? "draft-" + id : l.number()) + ".docx";
        return Files.attachment(name, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", letters.wordCopy(id));
    }

    // ---- what a contract states beyond its kind, dates and grade ------------------------------------------------

    @GetMapping("/people/{userId}/contracts/{contractId}/terms")
    public ContractTerms.View terms(@PathVariable long userId, @PathVariable long contractId) {
        return contractTerms.view(userId, contractId);
    }

    @PutMapping("/people/{userId}/contracts/{contractId}/terms")
    public ContractTerms.View saveTerms(@PathVariable long userId, @PathVariable long contractId, @Valid @RequestBody TermsInput in) {
        return contractTerms.save(userId, contractId, new ContractTerms.Terms(contractId,
                in.trialMonths() == null ? 0 : in.trialMonths(), Boolean.TRUE.equals(in.unskilledLabourer()),
                in.fixedTermReason(), in.fixedTermDetail(), Boolean.TRUE.equals(in.permanentJob()), in.workPlace(), in.supervisor(), in.signedPlace(),
                in.signedOn(), in.draftGivenOn(), in.medicalFitOn(), in.medicalDoctor(), in.onemVisaNo(), in.onemVisaOn(), null, null));
    }

    @GetMapping("/letters/{id}/file")
    public ResponseEntity<byte[]> letter(@PathVariable long id) {
        Letters.Letter l = letters.find(id);
        byte[] data = l.status().equals("DRAFT") || l.status().equals("CANCELLED") && l.sha256() == null
                ? letters.draftPdf(id) : letters.file(id);
        String name = (l.number() == null ? "draft-" + id : l.number()) + (l.status().equals("SIGNED") ? "-signed" : "") + ".pdf";
        return Files.inline(name, "application/pdf", data);
    }

    @PostMapping("/people/{userId}/certificates")
    @ResponseStatus(HttpStatus.CREATED)
    public Certificates.Request issueDirect(@PathVariable long userId, @Valid @RequestBody CertificateInput in) {
        return certificates.issueDirect(userId, in.kind(), in.purpose());
    }

    @PostMapping("/certificates/{id}/issue")
    public Certificates.Request issueRequested(@PathVariable long id) {
        return certificates.issue(id);
    }

    @PostMapping("/certificates/{id}/reject")
    public Certificates.Request reject(@PathVariable long id, @Valid @RequestBody RejectInput in) {
        return certificates.reject(id, in.reason());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
