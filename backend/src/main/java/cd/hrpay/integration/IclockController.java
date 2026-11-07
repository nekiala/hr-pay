package cd.hrpay.integration;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The address biometric clocks call ("ADMS" / push protocol): no sign-in, the clock is known by its serial number.
 * Answers are plain text, as the clocks expect.
 */
@RestController
@RequestMapping("/iclock")
public class IclockController {

    private final TimeClocks clocks;

    public IclockController(TimeClocks clocks) {
        this.clocks = clocks;
    }

    @GetMapping({"/cdata", "/cdata.aspx"})
    public ResponseEntity<String> hello(@RequestParam(name = "SN", required = false) String sn, HttpServletRequest request) {
        Optional<TimeClocks.Device> d = clocks.clock(sn, request.getRemoteAddr());
        return d.map(device -> text(clocks.options(device))).orElseGet(IclockController::unknown);
    }

    /**
     * The body is read before any parameter: some clocks send it as a form, and reading a parameter would swallow it.
     * The parameters come from the address only.
     */
    @PostMapping({"/cdata", "/cdata.aspx"})
    public ResponseEntity<String> upload(HttpServletRequest request) throws java.io.IOException {
        byte[] body = request.getInputStream().readNBytes(5_000_000);
        java.util.Map<String, String> q = query(request.getQueryString());
        Optional<TimeClocks.Device> d = clocks.clock(q.get("SN"), request.getRemoteAddr());
        if (d.isEmpty()) {
            return unknown();
        }
        if ("ATTLOG".equalsIgnoreCase(q.get("table"))) {
            int n = clocks.attendance(d.get(), new String(body, StandardCharsets.UTF_8), q.get("Stamp"));
            return text("OK: " + n);
        }
        // Operations, user lists and photos: received and not kept.
        return text("OK");
    }

    static java.util.Map<String, String> query(String qs) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        if (qs == null) {
            return out;
        }
        for (String pair : qs.split("&")) {
            int eq = pair.indexOf('=');
            String k = java.net.URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.putIfAbsent(k, v);
        }
        return out;
    }

    @GetMapping({"/getrequest", "/getrequest.aspx", "/ping"})
    public ResponseEntity<String> commands(@RequestParam(name = "SN", required = false) String sn, HttpServletRequest request) {
        return clocks.clock(sn, request.getRemoteAddr()).isPresent() ? text("OK") : unknown();
    }

    @PostMapping({"/devicecmd", "/devicecmd.aspx"})
    public ResponseEntity<String> commandResult(@RequestParam(name = "SN", required = false) String sn, HttpServletRequest request) {
        return clocks.clock(sn, request.getRemoteAddr()).isPresent() ? text("OK") : unknown();
    }

    private static ResponseEntity<String> text(String body) {
        return ResponseEntity.ok().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8)).body(body);
    }

    private static ResponseEntity<String> unknown() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(MediaType.TEXT_PLAIN).body("Unknown device");
    }
}
