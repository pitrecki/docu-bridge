package io.github.pitrecki.dokubridge.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class JsonMetadata {
    private JsonMetadata() {
    }

    static Map<String, Object> snapshot(Map<String, Object> source) {
        return copyMap(source, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static Map<String, Object> copyMap(Map<?, ?> source, Set<Object> ancestors) {
        enter(source, ancestors);
        try {
            var copy = new LinkedHashMap<String, Object>();
            for (var entry : source.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Metadata map keys must be strings");
                }
                copy.put(key, copyValue(entry.getValue(), ancestors));
            }
            return Collections.unmodifiableMap(copy);
        } finally {
            ancestors.remove(source);
        }
    }

    private static List<Object> copyList(List<?> source, Set<Object> ancestors) {
        enter(source, ancestors);
        try {
            var copy = new ArrayList<>();
            for (var value : source) {
                copy.add(copyValue(value, ancestors));
            }
            return Collections.unmodifiableList(copy);
        } finally {
            ancestors.remove(source);
        }
    }

    private static Object copyValue(Object value, Set<Object> ancestors) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return copyMap(map, ancestors);
        }
        if (value instanceof List<?> list) {
            return copyList(list, ancestors);
        }
        Class<?> type = value.getClass();
        // Exact classes exclude mutable Number implementations and subclasses.
        if (type == Byte.class || type == Short.class || type == Integer.class || type == Long.class
                || type == BigInteger.class || type == BigDecimal.class) {
            return value;
        }
        if ((type == Float.class && Float.isFinite((Float) value))
                || (type == Double.class && Double.isFinite((Double) value))) {
            return value;
        }
        throw new IllegalArgumentException("Metadata values must be JSON-compatible");
    }

    private static void enter(Object container, Set<Object> ancestors) {
        if (!ancestors.add(container)) {
            throw new IllegalArgumentException("Metadata must not contain cycles");
        }
    }
}
