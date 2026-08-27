package io.labdeck.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.labdeck.api.LabApiModels.LabDetailResponse;
import io.labdeck.api.LabApiModels.LabListResponse;
import io.labdeck.api.LabApiModels.LabSummaryResponse;
import io.labdeck.api.LabApiModels.ManifestPlanResponse;
import io.labdeck.api.LabApiModels.ResourcePlanResponse;
import io.labdeck.api.LabApiModels.SettingsResponse;
import io.labdeck.api.LabApiModels.TemplateListResponse;
import io.labdeck.docker.DockerEngineCapabilityException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LabControllerContractTests {

    private static final Instant NOW = Instant.parse("2026-08-26T20:00:00Z");

    @TempDir
    static Path dataDirectory;

    @DynamicPropertySource
    static void useTemporaryDatabase(DynamicPropertyRegistry properties) {
        properties.add("labdeck.data-directory", dataDirectory::toString);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @MockitoBean
    private LabApiService labs;

    @Test
    void exposesTypedLabTemplateAndSettingsContracts() throws Exception {
        when(labs.listLabs()).thenReturn(new LabListResponse(
                "v1", List.of(new LabSummaryResponse("lab-1", "API fixture", "IMPORTED", 0, NOW))));
        when(labs.templates()).thenReturn(new TemplateListResponse("v1", "PLANNED", List.of()));
        when(labs.settings()).thenReturn(new SettingsResponse(
                "v1", "127.0.0.1", false, false, false, "PLANNED", "PLANNED", "PLANNED"));

        mvc.perform(get("/api/v1/labs"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.apiVersion").value("v1"))
                .andExpect(jsonPath("$.labs[0].id").value("lab-1"));
        mvc.perform(get("/api/v1/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capability").value("PLANNED"));
        mvc.perform(get("/api/v1/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bindAddress").value("127.0.0.1"))
                .andExpect(jsonPath("$.remoteAccess").value(false))
                .andExpect(jsonPath("$.telemetryEnabled").value(false));
    }

    @Test
    void mutationRequiresCsrfAndAcceptsTheExactJsonContract() throws Exception {
        LabDetailResponse response = detail();
        when(labs.importLab("/tmp/api-fixture")).thenReturn(response);

        mvc.perform(post("/api/v1/labs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"/tmp/api-fixture\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        verifyNoInteractions(labs);

        CsrfSession csrf = csrf();
        mvc.perform(post("/api/v1/labs")
                        .session(csrf.session())
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace\":\"/tmp/api-fixture\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("lab-1"))
                .andExpect(jsonPath("$.plan.manifestSha256").value(
                        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        verify(labs).importLab("/tmp/api-fixture");
    }

    @Test
    void rejectsUnknownDuplicateTrailingAndWrongMediaBodies() throws Exception {
        CsrfSession csrf = csrf();

        assertMalformed(csrf, "{\"workspace\":\"/tmp/a\",\"unexpected\":true}");
        assertMalformed(csrf, "{\"workspace\":\"/tmp/a\",\"workspace\":\"/tmp/b\"}");
        assertMalformed(csrf, "{\"workspace\":\"/tmp/a\"}{\"workspace\":\"/tmp/b\"}");

        mvc.perform(post("/api/v1/labs")
                        .session(csrf.session())
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("/tmp/a"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("JSON_REQUIRED"));
        verifyNoInteractions(labs);
    }

    @Test
    void rejectsOversizedAndInvalidLifecycleBodiesBeforeTheService() throws Exception {
        CsrfSession csrf = csrf();
        String oversized = "{\"workspace\":\"/tmp/" + "a".repeat(70_000) + "\"}";

        mvc.perform(post("/api/v1/labs")
                        .session(csrf.session())
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
        mvc.perform(post("/api/v1/labs/lab-1/start")
                        .session(csrf.session())
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevision": 0,
                                  "expectedManifestSha256": "not-a-hash",
                                  "confirmedImageDownloads": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/labs/lab-1/start")
                        .session(csrf.session())
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevision": null,
                                  "expectedManifestSha256":
                                    "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                  "confirmedImageDownloads": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/labs/lab-1/stop")
                        .session(csrf.session())
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/labs/lab-1/stop")
                        .session(csrf.session())
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/labs/lab-1/start")
                        .session(csrf.session())
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedManifestSha256":
                                    "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                  "confirmedImageDownloads": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/labs/lab-1/stop")
                        .session(csrf.session())
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
        verifyNoInteractions(labs);
    }

    @Test
    void returnsStableProblemDetailsWithoutRejectedValues() throws Exception {
        String hostile = "private-value-that-must-not-return";
        when(labs.getLab(anyString())).thenThrow(new ApiException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "LAB_NOT_FOUND",
                "Lab not found",
                "No LabDeck lab has that ID."));

        MvcResult missing = mvc.perform(get("/api/v1/labs/" + hostile))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("LAB_NOT_FOUND"))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(missing.getResponse().getContentAsString())
                .doesNotContain(hostile);

        mvc.perform(get("/api/v1/labs/bad!/tests?limit=999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
    }

    @Test
    void returnsActionableTypedDockerCapabilityProblems() throws Exception {
        when(labs.getLab("lab-1")).thenThrow(new DockerEngineCapabilityException(
                DockerEngineCapabilityException.Reason.UNAVAILABLE));

        mvc.perform(get("/api/v1/labs/lab-1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("DOCKER_UNAVAILABLE"))
                .andExpect(jsonPath("$.instance").value("/api/v1"))
                .andExpect(jsonPath("$.detail").value(
                        "Docker is not available. Install or start Docker, then retry."));
    }

    private void assertMalformed(CsrfSession csrf, String body) throws Exception {
        mvc.perform(post("/api/v1/labs")
                        .session(csrf.session())
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    private CsrfSession csrf() throws Exception {
        MvcResult bootstrap = mvc.perform(get("/api/v1/csrf")).andExpect(status().isOk()).andReturn();
        String token = json.readTree(bootstrap.getResponse().getContentAsString()).get("token").stringValue();
        String header = json.readTree(bootstrap.getResponse().getContentAsString())
                .get("headerName").stringValue();
        return new CsrfSession(
                (MockHttpSession) bootstrap.getRequest().getSession(false), header, token);
    }

    private static LabDetailResponse detail() {
        ManifestPlanResponse plan = new ManifestPlanResponse(
                1,
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "API fixture",
                "/workspace",
                new ResourcePlanResponse(268_435_456, "0.5"),
                List.of(),
                List.of("busybox:1.37"),
                List.of(),
                null);
        return new LabDetailResponse(
                "v1", "lab-1", "API fixture", "/tmp/api-fixture", "IMPORTED", 0,
                NOW, NOW, plan, null);
    }

    private record CsrfSession(MockHttpSession session, String header, String token) {}
}
