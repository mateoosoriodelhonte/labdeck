package io.labdeck.lab;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record StoredOutput(String text, boolean truncated) {

    public static final int MAX_UTF8_BYTES = 64 * 1024;

    public StoredOutput {
        Objects.requireNonNull(text, "text");
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("Stored output exceeds the UTF-8 byte limit.");
        }
    }

    public static StoredOutput bounded(String input) {
        return bounded(input, MAX_UTF8_BYTES);
    }

    static StoredOutput bounded(String input, int maxUtf8Bytes) {
        if (maxUtf8Bytes < 0 || maxUtf8Bytes > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("The output byte limit is not valid.");
        }
        if (input == null || input.isEmpty()) {
            return new StoredOutput("", false);
        }

        StringBuilder result = new StringBuilder(Math.min(input.length(), maxUtf8Bytes));
        int bytes = 0;
        boolean truncated = false;
        for (int offset = 0; offset < input.length(); ) {
            int codePoint = input.codePointAt(offset);
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
        return new StoredOutput(result.toString(), truncated || result.length() < input.length());
    }

    public int utf8Bytes() {
        return text.getBytes(StandardCharsets.UTF_8).length;
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
