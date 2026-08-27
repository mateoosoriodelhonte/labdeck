package io.labdeck.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LabApiIntegrationTests {

    private static final String SECRET_VALUE = "secret-value-must-not-leave-the-plan";

    @TempDir
    static Path dataDirectory;

    @TempDir
    Path workspaceRoot;

    @DynamicPropertySource
    static void useTemporaryDatabase(DynamicPropertyRegistry properties) {
        properties.add("labdeck.data-directory", dataDirectory::toString);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Test
    void importsListsAndReadsARealValidatedWorkspaceWithoutEnvironmentValues() throws Exception {
        Path workspace = Files.createDirectories(workspaceRoot.resolve("student-project"));
        Files.writeString(workspace.resolve("labdeck.yml"), manifest());
        CsrfSession csrf = csrf();

        MvcResult imported = mvc.perform(post("/api/v1/labs")
                        .session(csrf.session())
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(java.util.Map.of("workspace", workspace.toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("API integration fixture"))
                .andExpect(jsonPath("$.state").value("IMPORTED"))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.plan.services[0].environmentKeys[0]").value("LAB_SECRET"))
                .andReturn();
        String body = imported.getResponse().getContentAsString();
        assertThat(body).doesNotContain(SECRET_VALUE);
        JsonNode importedJson = json.readTree(body);
        String id = importedJson.get("id").asText();

        mvc.perform(get("/api/v1/labs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labs.length()").value(1))
                .andExpect(jsonPath("$.labs[0].id").value(id));
        mvc.perform(get("/api/v1/labs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace").value(workspace.toRealPath().toString()))
                .andExpect(jsonPath("$.plan.images[0]").value("busybox:1.37"));
        mvc.perform(get("/api/v1/labs/{id}/services", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services.length()").value(0));
        mvc.perform(get("/api/v1/labs/{id}/logs", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capability").value("PLANNED"))
                .andExpect(jsonPath("$.lines.length()").value(0));
        mvc.perform(get("/api/v1/labs/{id}/tests?limit=20", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs.length()").value(0));
    }

    @Test
    void returnsSafeManifestAndMissingLabProblems() throws Exception {
        Path workspace = Files.createDirectories(workspaceRoot.resolve("invalid-project"));
        Files.writeString(workspace.resolve("labdeck.yml"), "version: 1\nname: Invalid\n");
        CsrfSession csrf = csrf();

        MvcResult invalid = mvc.perform(post("/api/v1/labs")
                        .session(csrf.session())
                        .header(csrf.header(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(java.util.Map.of("workspace", workspace.toString()))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MANIFEST_INVALID"))
                .andExpect(jsonPath("$.problems").isArray())
                .andReturn();
        assertThat(invalid.getResponse().getContentAsString()).doesNotContain(workspace.toString());

        mvc.perform(get("/api/v1/labs/lab-does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LAB_NOT_FOUND"));
    }

    private CsrfSession csrf() throws Exception {
        MvcResult bootstrap = mvc.perform(get("/api/v1/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode response = json.readTree(bootstrap.getResponse().getContentAsString());
        return new CsrfSession(
                (MockHttpSession) bootstrap.getRequest().getSession(false),
                response.get("headerName").asText(),
                response.get("token").asText());
    }

    private static String manifest() {
        return """
                version: 1
                name: API integration fixture
                workspace:
                  mount: /workspace
                services:
                  app:
                    image: busybox:1.37
                    command: ["sleep", "infinity"]
                    environment:
                      LAB_SECRET: %s
                    ports:
                      - container: 8080
                resources:
                  memory: 256MiB
                  cpus: 0.5
                tests:
                  service: app
                  command: ["true"]
                  timeout: 30s
                """.formatted(SECRET_VALUE);
    }

    private record CsrfSession(MockHttpSession session, String header, String token) {}
}
