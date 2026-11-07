package cd.hrpay.lifecycle;

import cd.hrpay.common.BusinessRuleException;
import cd.hrpay.common.NotFoundException;
import cd.hrpay.security.CurrentUser;
import cd.hrpay.user.AppUser;
import cd.hrpay.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The employee's own documents: reading them, adding the ones HR asks for, signing letters, asking for certificates. */
@RestController
@RequestMapping("/api/my")
public class MyDocumentsController {

    public record Mine(List<Documents.Doc> documents, List<Letters.Letter> letters, List<Certificates.Request> certificates,
                       List<Documents.Type> uploadTypes) {}

    public record DocumentInput(@NotNull Long typeId, @Size(max = 200) String fileName, @NotBlank @Size(max = 14_000_000) String content,
                                LocalDate issuedOn, LocalDate expiresOn) {}

    public record SignInput(@NotNull UUID challengeId, @NotBlank @Size(max = 10) String code, @NotBlank @Size(max = 200) String name) {}

    public record DeclineInput(@Size(max = 300) String reason) {}

    public record CertificateInput(@NotBlank String kind, @Size(max = 200) String purpose) {}

    private final Documents documents;
    private final Letters letters;
    private final Certificates certificates;
    private final UserService users;

    public MyDocumentsController(Documents documents, Letters letters, Certificates certificates, UserService users) {
        this.documents = documents;
        this.letters = letters;
        this.certificates = certificates;
        this.users = users;
    }

    private AppUser me() {
        return users.find(CurrentUser.id());
    }

    @GetMapping("/documents")
    public Mine mine() {
        long me = CurrentUser.id();
        return new Mine(documents.of(me, true), letters.mine(me).stream().map(MyDocumentsController::withoutBody).toList(),
                certificates.of(me), documents.types().stream().filter(t -> t.active() && t.employeeCanUpload()).toList());
    }

    private static Letters.Letter withoutBody(Letters.Letter l) {
        return new Letters.Letter(l.id(), l.userId(), l.employeeName(), l.templateId(), l.kind(), l.number(), l.title(), null,
                l.requiresSignature(), l.status(), l.sha256(), null, l.sentByName(), l.sentAt(), l.signedSha256(), l.signedAt(),
                l.signedName(), null, l.declinedReason(), l.documentId(), null, l.createdAt());
    }

    @GetMapping("/documents/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable long id) {
        Documents.Doc d = documents.find(id);
        if (d.userId() != CurrentUser.id() || !d.employeeCanSee()) {
            throw new NotFoundException("Document not found.");
        }
        Documents.File f = documents.file(id);
        return Files.inline(f.fileName(), f.contentType(), f.data());
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public Documents.Doc add(@Valid @RequestBody DocumentInput in) {
        Documents.Type t = documents.type(in.typeId());
        if (!t.employeeCanUpload() || !t.active()) {
            throw new BusinessRuleException("Hand this document to HR.");
        }
        return documents.add(CurrentUser.id(), t.id(), in.fileName(), Files.decode(in.content()), in.issuedOn(), in.expiresOn(), null,
                "EMPLOYEE");
    }

    @GetMapping("/letters/{id}/file")
    public ResponseEntity<byte[]> letter(@PathVariable long id) {
        Letters.Letter l = letters.find(id);
        if (l.userId() != CurrentUser.id() || l.status().equals("DRAFT") || l.status().equals("CANCELLED")) {
            throw new NotFoundException("Document not found.");
        }
        return Files.inline(l.number() + (l.status().equals("SIGNED") ? "-signed" : "") + ".pdf", "application/pdf", letters.file(id));
    }

    @PostMapping("/letters/{id}/code")
    public Letters.CodeSent code(@PathVariable long id) {
        return letters.sendCode(id, me());
    }

    @PostMapping("/letters/{id}/sign")
    public Letters.Letter sign(@PathVariable long id, @Valid @RequestBody SignInput in, HttpServletRequest request) {
        return withoutBody(letters.sign(id, me(), in.challengeId(), in.code().trim(), in.name(), request.getRemoteAddr(),
                request.getHeader("User-Agent")));
    }

    @PostMapping("/letters/{id}/decline")
    public Letters.Letter decline(@PathVariable long id, @Valid @RequestBody DeclineInput in) {
        return withoutBody(letters.decline(id, me(), in.reason()));
    }

    @PostMapping("/certificates")
    @ResponseStatus(HttpStatus.CREATED)
    public Certificates.Request request(@Valid @RequestBody CertificateInput in) {
        return certificates.request(me(), in.kind(), in.purpose());
    }

    @PostMapping("/certificates/{id}/cancel")
    public Certificates.Request cancel(@PathVariable long id) {
        return certificates.cancel(id, me());
    }
}
