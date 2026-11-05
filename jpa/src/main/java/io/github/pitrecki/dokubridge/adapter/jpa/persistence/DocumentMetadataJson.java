package io.github.pitrecki.dokubridge.adapter.jpa.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

final class DocumentMetadataJson {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private DocumentMetadataJson() {
    }

    static String encode(Map<String, Object> metadata) {
        try {
            return JSON.writeValueAsString(metadata);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Cannot encode document metadata", failure);
        }
    }

    static Map<String, Object> decode(String metadata) {
        try {
            return JSON.readValue(metadata, MAP);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot decode persisted document metadata", failure);
        }
    }
}
