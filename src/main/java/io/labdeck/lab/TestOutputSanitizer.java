package io.labdeck.lab;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class TestOutputSanitizer {

    public static final String REDACTION = "[REDACTED]";
    private static final Pattern KEY_VALUE_CREDENTIAL = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|token|secret|api[_-]?key|access[_-]?key|authorization)"
                    + "(\\s*[:=]\\s*)([^\\s,;]+)");
    private static final Pattern BEARER_CREDENTIAL = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+");

    private final List<String> sensitiveValues;

    private TestOutputSanitizer(List<String> sensitiveValues) {
        this.sensitiveValues = sensitiveValues;
    }

    public static TestOutputSanitizer forLab(Path workspace, Collection<String> sensitiveValues) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(sensitiveValues, "sensitiveValues");
        List<String> values = new ArrayList<>();
        addIfPresent(values, workspace.toAbsolutePath().normalize().toString());
        for (String sensitiveValue : sensitiveValues) {
            addIfPresent(values, sensitiveValue);
        }
        values.sort(Comparator.comparingInt(String::length).reversed());
        return new TestOutputSanitizer(List.copyOf(values));
    }

    String sanitize(String input) {
        String sanitized = input == null ? "" : input;
        for (String sensitiveValue : sensitiveValues) {
            sanitized = sanitized.replace(sensitiveValue, REDACTION);
        }
        sanitized = KEY_VALUE_CREDENTIAL.matcher(sanitized).replaceAll("$1$2" + REDACTION);
        sanitized = BEARER_CREDENTIAL.matcher(sanitized).replaceAll("Bearer " + REDACTION);
        StringBuilder safe = new StringBuilder(sanitized.length());
        sanitized.codePoints().forEach(codePoint -> safe.appendCodePoint(
                ((Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\t')
                                || Character.getType(codePoint) == Character.FORMAT)
                        ? 0xfffd : codePoint));
        return safe.toString();
    }

    @Override
    public String toString() {
        return "TestOutputSanitizer[sensitiveValueCount=" + sensitiveValues.size() + "]";
    }

    private static void addIfPresent(List<String> values, String value) {
        if (value != null && !value.isEmpty() && !values.contains(value)) {
            values.add(value);
        }
    }
}
