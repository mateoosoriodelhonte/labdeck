package io.labdeck.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ManifestSchemaTests {

    @Test
    void packagedV1SchemaIsValidJsonAndClosedByDefault() throws IOException {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("schema/labdeck-v1.schema.json")) {
            assertThat(input).isNotNull();
            JsonNode schema = JsonMapper.shared().readTree(input);

            assertThat(schema.get("$schema").stringValue())
                    .isEqualTo("https://json-schema.org/draft/2020-12/schema");
            assertThat(schema.get("additionalProperties").booleanValue()).isFalse();
            assertThat(schema.at("/$defs/service/additionalProperties").booleanValue()).isFalse();
            assertThat(schema.at("/properties/version/const").intValue()).isEqualTo(1);
        }
    }
}
