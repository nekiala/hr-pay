package cd.hrpay.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the React app's index.html for client-side routes when the built app is bundled in static/. */
@Controller
public class SpaForwardController {

    @GetMapping({"/", "/login", "/users/**", "/roles/**", "/org/**", "/sites/**", "/audit/**", "/account/**", "/schedules/**", "/holidays/**",
            "/overtime/**", "/check-in/**", "/kiosk/**", "/outbox/**", "/my/**", "/team/**", "/leave/**", "/reports/**", "/missions/**",
            "/payroll/**", "/payslips/**",
            "/evaluations/**", "/corrections/**", "/presence/**", "/privacy/**", "/company/**", "/system/**"})
    public String index() {
        return "forward:/index.html";
    }
}
