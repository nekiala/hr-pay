package cd.hrpay.lifecycle;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Files sent to and from the browser. */
final class Files {

    private Files() {}

    /** Opened in the browser's own viewer (on a phone, the PDF reader), with its name for saving. */
    static ResponseEntity<byte[]> inline(String name, String contentType, byte[] data) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .body(data);
    }

    /** Downloaded, with its name. */
    static ResponseEntity<byte[]> attachment(String name, String contentType, byte[] data) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(data);
    }

    static byte[] decode(String base64) {
        try {
            String b = base64.contains(",") && base64.startsWith("data:") ? base64.substring(base64.indexOf(',') + 1) : base64;
            return Base64.getDecoder().decode(b.trim());
        } catch (IllegalArgumentException e) {
            throw new cd.hrpay.common.BusinessRuleException("The file could not be read.");
        }
    }
}
