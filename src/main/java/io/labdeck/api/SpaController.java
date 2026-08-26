package io.labdeck.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {

    @GetMapping({
        "/labs",
        "/labs/{labId}",
        "/templates",
        "/test-results",
        "/concepts",
        "/settings"
    })
    public String forwardToApplication() {
        return "forward:/index.html";
    }
}
