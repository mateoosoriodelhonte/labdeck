package io.labdeck.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SystemControllerTests {

    @TempDir
    static Path dataDirectory;

    @DynamicPropertySource
    static void useTemporaryDatabase(DynamicPropertyRegistry properties) {
        properties.add("labdeck.data-directory", dataDirectory::toString);
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void reportsLocalOnlyVersionedStatus() throws Exception {
        mvc.perform(get("/api/v1/system"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.access").value("LOCAL_ONLY"))
                .andExpect(jsonPath("$.apiVersion").value("v1"));
    }

    @Test
    void servesSupportedDeepLinkFromBundledFrontend() throws Exception {
        mvc.perform(get("/labs/cs-341-databases"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }
}
