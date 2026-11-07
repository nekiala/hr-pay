package cd.hrpay.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the React app's index.html for every client-side route when the built app is bundled in static/, so a
 * page opened from a bookmark or reloaded works. A path whose first part is the API, the time clocks, the health
 * probes or the sign-in callbacks is never a page, and neither is a file (a path with a dot, such as /sw.js).
 */
@Controller
public class SpaForwardController {

    private static final String FIRST = "/{first:(?!api$|iclock$|actuator$|oauth2$|login$|error$|assets$)[^.]+}";

    @GetMapping({"/", "/login", FIRST, FIRST + "/{b:[^.]+}", FIRST + "/{b:[^.]+}/{c:[^.]+}",
            FIRST + "/{b:[^.]+}/{c:[^.]+}/{d:[^.]+}", FIRST + "/{b:[^.]+}/{c:[^.]+}/{d:[^.]+}/{e:[^.]+}"})
    public String index() {
        return "forward:/index.html";
    }
}
