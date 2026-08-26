package io.labdeck.manifest;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.snakeyaml.engine.v2.api.LoadSettings;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

public final class RestrictedManifestParser {

    public static final int MAX_MANIFEST_BYTES = 256 * 1024;

    private final YAMLMapper mapper;
    private final LabManifestValidator validator;

    public RestrictedManifestParser() {
        LoadSettings loadSettings = LoadSettings.builder()
                .setAllowDuplicateKeys(false)
                .setAllowRecursiveKeys(false)
                .setAllowNonScalarKeys(false)
                .setMaxAliasesForCollections(0)
                .setCodePointLimit(MAX_MANIFEST_BYTES)
                .build();
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(20)
                .maxDocumentLength(MAX_MANIFEST_BYTES)
                .maxTokenCount(10_000)
                .maxNameLength(128)
                .maxStringLength(8_192)
                .maxNumberLength(64)
                .build();
        YAMLFactory factory = YAMLFactory.builder()
                .loadSettings(loadSettings)
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.mapper = YAMLMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        this.validator = new LabManifestValidator();
    }

    public LabManifest parse(byte[] input) {
        if (input == null) {
            throw parseFailure("The manifest is required.");
        }
        if (input.length > MAX_MANIFEST_BYTES) {
            throw parseFailure("The manifest exceeds the 256 KiB limit.");
        }

        String yaml;
        try {
            yaml = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(input))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw parseFailure("The manifest must be valid UTF-8.");
        }
        return parse(yaml);
    }

    public LabManifest parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw parseFailure("The manifest is required.");
        }
        if (yaml.length() > MAX_MANIFEST_BYTES
                || yaml.getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES) {
            throw parseFailure("The manifest exceeds the 256 KiB limit.");
        }
        if (containsDisallowedControlCharacter(yaml)) {
            throw parseFailure("The manifest contains a disallowed control character.");
        }

        try {
            JsonNode root = mapper.readTree(yaml);
            return validator.validate(root);
        } catch (ManifestValidationException exception) {
            throw exception;
        } catch (JacksonException exception) {
            throw parseFailure("The manifest is not well-formed YAML.");
        }
    }

    private static boolean containsDisallowedControlCharacter(String value) {
        return value.codePoints().anyMatch(codePoint ->
                (codePoint < 0x20 && codePoint != '\n' && codePoint != '\r' && codePoint != '\t')
                        || codePoint == 0x7f);
    }

    private static ManifestValidationException parseFailure(String message) {
        return new ManifestValidationException(List.of(
                new ManifestProblem(ManifestProblemCode.MANIFEST_PARSE_ERROR, "/", message)));
    }
}
