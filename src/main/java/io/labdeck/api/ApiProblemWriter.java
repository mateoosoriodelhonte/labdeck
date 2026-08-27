package io.labdeck.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class ApiProblemWriter {

    private final JsonMapper json;

    public ApiProblemWriter(JsonMapper json) {
        this.json = json;
    }

    public void write(
            HttpServletResponse response,
            int status,
            String code,
            String title,
            String detail) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "urn:labdeck:problem:" + code.toLowerCase(java.util.Locale.ROOT));
        problem.put("title", title);
        problem.put("status", status);
        problem.put("detail", detail);
        problem.put("instance", "/api/v1");
        problem.put("code", code);
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getOutputStream(), problem);
    }
}
