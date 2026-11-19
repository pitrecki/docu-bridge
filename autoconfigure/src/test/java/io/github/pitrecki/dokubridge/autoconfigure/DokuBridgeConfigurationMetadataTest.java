package io.github.pitrecki.dokubridge.autoconfigure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DokuBridgeConfigurationMetadataTest {
    @Test
    void generatedMetadataDescribesThePublicSettingsAndTheirDefaults() throws Exception {
        try (var input = DokuBridgeProperties.class.getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
            assertThat(input).as("Generated Spring Boot configuration metadata").isNotNull();
            var properties = new HashMap<String, JsonNode>();
            new ObjectMapper().readTree(input).path("properties")
                    .forEach(property -> properties.put(property.path("name").asText(), property));
            assertThat(properties).containsOnlyKeys(
                    "dokubridge.enabled", "dokubridge.storage.root", "dokubridge.jpa.initialize-schema");
            assertThat(properties.get("dokubridge.enabled").path("defaultValue").asBoolean()).isTrue();
            var schema = properties.get("dokubridge.jpa.initialize-schema");
            assertThat(schema.path("defaultValue").isBoolean()).isTrue();
            assertThat(schema.path("defaultValue").asBoolean()).isFalse();
            assertThat(schema.path("description").asText()).contains("after host migrations", "separate history table");
            var storage = properties.get("dokubridge.storage.root");
            assertThat(storage.path("type").asText()).isEqualTo("java.nio.file.Path");
            assertThat(storage.has("defaultValue")).isFalse();
            assertThat(storage.path("description").asText()).contains("hard links", "DocumentContentStore");
            assertThat(properties.values()).allSatisfy(property -> assertThat(property.path("description").asText()).isNotBlank());
        }
    }
}
