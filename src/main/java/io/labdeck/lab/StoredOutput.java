package io.labdeck.lab;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class StoredOutput {

    public static final int MAX_UTF8_BYTES = 64 * 1024;

    private final String text;
    private final boolean truncated;
    private final boolean safeToPersist;

    private StoredOutput(String text, boolean truncated, boolean safeToPersist) {
        this.text = Objects.requireNonNull(text, "text");
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("Stored output exceeds the UTF-8 byte limit.");
        }
        this.truncated = truncated;
        this.safeToPersist = safeToPersist;
    }

    static StoredOutput bounded(String input, int maxUtf8Bytes, TestOutputSanitizer sanitizer) {
        Objects.requireNonNull(sanitizer, "sanitizer");
        if (maxUtf8Bytes < 0 || maxUtf8Bytes > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("The output byte limit is not valid.");
        }

        String sanitized = sanitizer.sanitize(input);
        if (sanitized.isEmpty()) {
            return new StoredOutput("", false, true);
        }

        StringBuilder result = new StringBuilder(Math.min(sanitized.length(), maxUtf8Bytes));
        int bytes = 0;
        boolean truncated = false;
        for (int offset = 0; offset < sanitized.length(); ) {
            int codePoint = sanitized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                codePoint = 0xfffd;
            }
            int codePointBytes = utf8Length(codePoint);
            if (bytes + codePointBytes > maxUtf8Bytes) {
                truncated = true;
                break;
            }
            result.appendCodePoint(codePoint);
            bytes += codePointBytes;
        }
        return new StoredOutput(
                result.toString(), truncated || result.length() < sanitized.length(), true);
    }

    public static StoredOutput fromPersistence(String text, boolean truncated) {
        return new StoredOutput(text, truncated, false);
    }

    public String text() {
        return text;
    }

    public boolean truncated() {
        return truncated;
    }

    public boolean safeToPersist() {
        return safeToPersist;
    }

    public int utf8Bytes() {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StoredOutput output
                && text.equals(output.text)
                && truncated == output.truncated;
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, truncated);
    }

    @Override
    public String toString() {
        return "StoredOutput[text=<redacted>, utf8Bytes=" + utf8Bytes()
                + ", truncated=" + truncated + "]";
    }

    private static int utf8Length(int codePoint) {
        if (codePoint <= 0x7f) {
            return 1;
        }
        if (codePoint <= 0x7ff) {
            return 2;
        }
        if (codePoint <= 0xffff) {
            return 3;
        }
        return 4;
    }
}
