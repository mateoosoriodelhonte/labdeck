package io.labdeck.api;

import io.labdeck.api.LabApiModels.SettingsResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/settings", produces = MediaType.APPLICATION_JSON_VALUE)
public class SettingsController {

    private final LabApiService labs;

    public SettingsController(LabApiService labs) {
        this.labs = labs;
    }

    @GetMapping
    public SettingsResponse settings() {
        return labs.settings();
    }
}
