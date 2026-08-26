package io.labdeck.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    @GetMapping
    public SystemStatus status() {
        return new SystemStatus("LabDeck", "READY", "LOCAL_ONLY", "v1");
    }

    public record SystemStatus(String name, String status, String access, String apiVersion) {}
}
