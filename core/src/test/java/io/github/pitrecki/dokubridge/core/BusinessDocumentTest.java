package io.github.pitrecki.dokubridge.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class BusinessDocumentTest {
    @Test
    @SuppressWarnings("unchecked")
    void takesADeeplyImmutableSnapshotWithoutLosingNullValues() {
        var nested = new LinkedHashMap<String, Object>();
        nested.put("nullable", null);
        nested.put("label", "original");
        var items = new ArrayList<Object>();
        items.add(nested);
        items.add(null);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("items", items);
        metadata.put("nullable", null);
        var document = document(metadata);

        nested.put("label", "changed");
        items.clear();
        metadata.clear();

        var frozenItems = (List<Object>) document.metadata().get("items");
        var frozenNested = (Map<String, Object>) frozenItems.get(0);
        assertThat(document.metadata()).containsKey("nullable");
        assertThat(document.metadata().get("nullable")).isNull();
        assertThat(frozenItems).hasSize(2);
        assertThat(frozenItems.get(1)).isNull();
        assertThat(frozenNested).containsEntry("label", "original").containsEntry("nullable", null);
        assertThatThrownBy(() -> document.metadata().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frozenItems.add("value")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> frozenNested.put("label", "changed"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void acceptsJsonScalarsAndAllSupportedImmutableNumberTypes() {
        var values = Arrays.asList("text", true, null, (byte) 1, (short) 2, 3, 4L, 5.5f, 6.5d,
                new BigInteger("12345678901234567890"), new BigDecimal("123.450"));
        assertThat(document(Map.of("values", values)).metadata()).containsEntry("values", values);
    }

    @ParameterizedTest
    @MethodSource("unsupportedValues")
    void rejectsNonJsonOrMutableValues(Object value) {
        assertThatIllegalArgumentException().isThrownBy(() -> document(Map.of("value", value)));
    }

    static Stream<Object> unsupportedValues() {
        return Stream.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                new AtomicInteger(1), new Object(), new int[]{1}, java.time.Instant.EPOCH,
                java.util.Set.of("value"), 'x', BusinessDocumentState.ACTIVE);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejectsNonStringAndNullMapKeysAtEveryLevel() {
        Map invalid = new LinkedHashMap();
        invalid.put(1, "value");
        assertThatIllegalArgumentException().isThrownBy(() -> document(invalid));
        assertThatIllegalArgumentException().isThrownBy(() -> document(Map.of("nested", invalid)));
        invalid.clear();
        invalid.put(null, "value");
        assertThatIllegalArgumentException().isThrownBy(() -> document(invalid));
    }

    @Test
    void rejectsCyclesInMapsListsAndMixedContainers() {
        var map = new LinkedHashMap<String, Object>();
        map.put("self", map);
        assertThatIllegalArgumentException().isThrownBy(() -> document(map));
        var list = new ArrayList<Object>();
        list.add(list);
        assertThatIllegalArgumentException().isThrownBy(() -> document(Map.of("list", list)));
        map.clear();
        list.clear();
        map.put("list", list);
        list.add(map);
        assertThatIllegalArgumentException().isThrownBy(() -> document(map));
    }

    @Test
    void allowsSharedContainersThatAreNotCycles() {
        var shared = new ArrayList<>(List.of("shared"));
        var document = document(Map.of("first", shared, "second", shared));
        shared.clear();
        assertThat(document.metadata()).containsEntry("first", List.of("shared"))
                .containsEntry("second", List.of("shared"));
    }

    @Test
    void requiresIdentityOwnerMetadataAndState() {
        var id = UUID.randomUUID();
        var owner = new DocumentOwnerRef("Invoice", "42");
        assertThatNullPointerException().isThrownBy(() -> new BusinessDocument(null, owner, Map.of(), BusinessDocumentState.ACTIVE));
        assertThatNullPointerException().isThrownBy(() -> new BusinessDocument(id, null, Map.of(), BusinessDocumentState.ACTIVE));
        assertThatNullPointerException().isThrownBy(() -> new BusinessDocument(id, owner, null, BusinessDocumentState.ACTIVE));
        assertThatNullPointerException().isThrownBy(() -> new BusinessDocument(id, owner, Map.of(), null));
    }

    private BusinessDocument document(Map<String, Object> metadata) {
        return new BusinessDocument(UUID.randomUUID(), new DocumentOwnerRef("Invoice", "42"),
                metadata, BusinessDocumentState.ACTIVE);
    }
}
