package cd.hrpay.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** "Forgot your password?" on the sign-in page, and the page the emailed link opens. Both work signed out. */
@RestController
@RequestMapping("/api/auth/password-reset")
public class PasswordResetController {

    public record Ask(@NotBlank @Size(max = 320) String email) {}

    public record NewPassword(@NotBlank @Size(max = 200) String token, @NotBlank String password) {}

    private final PasswordResets resets;

    public PasswordResetController(PasswordResets resets) {
        this.resets = resets;
    }

    /** Always the same answer, so the form does not tell who has an account. */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ask(@Valid @RequestBody Ask body, HttpServletRequest request) {
        resets.request(body.email(), request.getRemoteAddr());
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@Valid @RequestBody NewPassword body) {
        resets.confirm(body.token(), body.password());
    }
}
