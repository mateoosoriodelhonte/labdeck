package io.labdeck.api;

import io.labdeck.api.LabApiModels.ImportLabRequest;
import io.labdeck.api.LabApiModels.LabDetailResponse;
import io.labdeck.api.LabApiModels.LabListResponse;
import io.labdeck.api.LabApiModels.LabStartResponse;
import io.labdeck.api.LabApiModels.LogListResponse;
import io.labdeck.api.LabApiModels.ServiceListResponse;
import io.labdeck.api.LabApiModels.StartLabRequest;
import io.labdeck.api.LabApiModels.StopLabRequest;
import io.labdeck.api.LabApiModels.TestHistoryResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping(path = "/api/v1/labs", produces = MediaType.APPLICATION_JSON_VALUE)
public class LabController {

    private static final String LAB_ID = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}";

    private final LabApiService labs;

    public LabController(LabApiService labs) {
        this.labs = labs;
    }

    @GetMapping
    public LabListResponse list() {
        return labs.listLabs();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public LabDetailResponse importLab(@Valid @RequestBody ImportLabRequest request) {
        return labs.importLab(request.workspace());
    }

    @GetMapping("/{id}")
    public LabDetailResponse detail(@PathVariable @Pattern(regexp = LAB_ID) String id) {
        return labs.getLab(id);
    }

    @PostMapping(path = "/{id}/start", consumes = MediaType.APPLICATION_JSON_VALUE)
    public LabStartResponse start(
            @PathVariable @Pattern(regexp = LAB_ID) String id,
            @Valid @RequestBody StartLabRequest request) {
        return labs.startLab(id, request);
    }

    @PostMapping(path = "/{id}/stop", consumes = MediaType.APPLICATION_JSON_VALUE)
    public LabDetailResponse stop(
            @PathVariable @Pattern(regexp = LAB_ID) String id,
            @Valid @RequestBody StopLabRequest request) {
        return labs.stopLab(id, request.expectedRevision());
    }

    @GetMapping("/{id}/services")
    public ServiceListResponse services(@PathVariable @Pattern(regexp = LAB_ID) String id) {
        return labs.listServices(id);
    }

    @GetMapping("/{id}/logs")
    public LogListResponse logs(@PathVariable @Pattern(regexp = LAB_ID) String id) {
        return labs.logs(id);
    }

    @GetMapping("/{id}/tests")
    public TestHistoryResponse tests(
            @PathVariable @Pattern(regexp = LAB_ID) String id,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return labs.testHistory(id, limit);
    }
}
