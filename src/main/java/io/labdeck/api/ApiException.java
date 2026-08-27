package io.labdeck.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;

public final class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final Map<String, Object> properties;

    public ApiException(
            HttpStatus status,
            String code,
            String title,
            String detail) {
        this(status, code, title, detail, Map.of());
    }

    public ApiException(
            HttpStatus status,
            String code,
            String title,
            String detail,
            Map<String, Object> properties) {
        super(detail);
        this.status = Objects.requireNonNull(status, "status");
        this.code = requireText(code, "code");
        this.title = requireText(title, "title");
        this.properties = Map.copyOf(new LinkedHashMap<>(properties));
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("The API exception " + label + " is required.");
        }
        return value;
    }
}
