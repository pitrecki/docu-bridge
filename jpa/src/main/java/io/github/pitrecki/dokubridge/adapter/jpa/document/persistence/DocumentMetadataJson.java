package io.github.pitrecki.dokubridge.adapter.jpa.document.persistence;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

final class DocumentMetadataJson {
    // JSONB numeric permits 131072 integer and 16383 fractional digits, plus sign and decimal point.
    // Jackson's default limit would make valid committed metadata unreadable.
    private static final int MAX_JSONB_NUMBER_LENGTH = 131_072 + 16_383 + 2;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNumberLength(MAX_JSONB_NUMBER_LENGTH).build())
            .build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private DocumentMetadataJson() {
    }

    static String encode(Map<String, Object> metadata) {
        try {
            String encoded = JSON.writeValueAsString(metadata);
            // Jackson's write constraints do not cover every read limit, including field-name length.
            JSON.readValue(encoded, MAP);
            return encoded;
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
