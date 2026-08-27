package io.labdeck.api;

import io.labdeck.api.LabApiModels.TemplateListResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/templates", produces = MediaType.APPLICATION_JSON_VALUE)
public class TemplateController {

    private final LabApiService labs;

    public TemplateController(LabApiService labs) {
        this.labs = labs;
    }

    @GetMapping
    public TemplateListResponse list() {
        return labs.templates();
    }
}
