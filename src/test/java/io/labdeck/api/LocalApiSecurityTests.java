package io.labdeck.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LocalApiSecurityTests {

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

    @Test
    void bootstrapsAndRotatesAHeaderOnlySessionToken() throws Exception {
        MvcResult first = mvc.perform(get("/api/v1/csrf")
                        .header(HttpHeaders.HOST, "127.0.0.1:8787"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.apiVersion").value("v1"))
                .andExpect(jsonPath("$.headerName").value("X-LabDeck-CSRF"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) first.getRequest().getSession(false);
        String firstToken = token(first);
        assertThat(session).isNotNull();
        assertThat(firstToken).isNotBlank();

        MvcResult rotated = mvc.perform(post("/api/v1/csrf/rotate")
                        .session(session)
                        .header(HttpHeaders.HOST, "127.0.0.1:8787")
                        .header("X-LabDeck-CSRF", firstToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();
        String secondToken = token(rotated);
        assertThat(secondToken).isNotBlank().isNotEqualTo(firstToken);

        mvc.perform(post("/api/v1/csrf/rotate")
                        .session(session)
                        .header(HttpHeaders.HOST, "127.0.0.1:8787")
                        .header("X-LabDeck-CSRF", firstToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));

        mvc.perform(post("/api/v1/csrf/rotate")
                        .session(session)
                        .header(HttpHeaders.HOST, "127.0.0.1:8787")
                        .header("X-LabDeck-CSRF", secondToken))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsMissingWrongDuplicateAndParameterOnlyTokens() throws Exception {
        MvcResult bootstrap = mvc.perform(get("/api/v1/csrf")).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) bootstrap.getRequest().getSession(false);
        String token = token(bootstrap);

        mvc.perform(post("/api/v1/csrf/rotate").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        mvc.perform(post("/api/v1/csrf/rotate")
                        .session(session)
                        .header("X-LabDeck-CSRF", "wrong"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/csrf/rotate")
                        .session(session)
                        .header("X-LabDeck-CSRF", token, token))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/csrf/rotate")
                        .session(session)
                        .param("_csrf", token)
                        .param("_labdeck_csrf_disabled", token))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsHostileAuthorityOriginAndProxyIdentity() throws Exception {
        mvc.perform(get("/api/v1/csrf").header(HttpHeaders.HOST, "evil.example:8787"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOCAL_REQUEST_REQUIRED"))
                .andExpect(jsonPath("$.instance").value("/api/v1"));
        mvc.perform(get("/api/v1/csrf")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.10");
                            return request;
                        })
                        .header(HttpHeaders.HOST, "127.0.0.1:8787"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOCAL_REQUEST_REQUIRED"))
                .andExpect(jsonPath("$.instance").value("/api/v1"));
        mvc.perform(get("/api/v1/csrf").header(HttpHeaders.HOST, "localhost", "127.0.0.1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/csrf")
                        .header(HttpHeaders.HOST, "127.0.0.1:8787")
                        .header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_ORIGIN_REJECTED"));
        mvc.perform(get("/api/v1/csrf")
                        .header(HttpHeaders.HOST, "127.0.0.1:8787")
                        .header(HttpHeaders.ORIGIN, "null"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/system")
                        .header(HttpHeaders.HOST, "127.0.0.1:8787")
                        .header("Forwarded", "host=127.0.0.1:8787"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/csrf")
                        .header(HttpHeaders.HOST, "127.0.0.1:8787")
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:8787"))
                .andExpect(status().isOk());
    }

    @Test
    void hostilePreflightNeverReceivesCorsPermission() throws Exception {
        mvc.perform(options("/api/v1/csrf")
                        .header(HttpHeaders.HOST, "127.0.0.1:8787")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-LabDeck-CSRF"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void appliesLocalSecurityHeadersAndLimitsActuatorExposure() throws Exception {
        mvc.perform(get("/api/v1/system"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString(
                        "frame-ancestors 'none'")));
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/info")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }

    private String token(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("token").stringValue();
    }
}
