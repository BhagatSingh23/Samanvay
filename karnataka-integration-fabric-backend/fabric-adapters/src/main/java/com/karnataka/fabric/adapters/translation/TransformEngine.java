package com.karnataka.fabric.adapters.translation;

import com.karnataka.fabric.core.domain.FieldTransform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stateless engine that applies a single {@link FieldTransform} to a value.
 *
 * <p>Each transform is deterministic and side-effect-free. Composite
 * transforms (like {@link FieldTransform#SPLIT_FULLNAME_TO_FIRST_LAST})
 * may produce multiple output fields — in that case, the result is a
 * {@code Map<String, Object>} rather than a scalar.</p>
 */
@Component
public class TransformEngine {

    private static final Logger log = LoggerFactory.getLogger(TransformEngine.class);

    /**
     * Applies the given transform to a value.
     *
     * @param transform the transformation to apply
     * @param value     the raw value (may be String, Number, Map, etc.)
     * @return the transformed value, or the original value if the transform
     *         is {@link FieldTransform#NONE} or fails gracefully
     */
    public Object applyTransform(FieldTransform transform, Object value) {
        if (value == null || transform == null || transform == FieldTransform.NONE) {
            return value;
        }

        return switch (transform) {
            case NONE -> value;
            case UPPERCASE -> applyUppercase(value);
            case LOWERCASE -> applyLowercase(value);
            case DATE_ISO_TO_EPOCH -> applyIsoToEpoch(value);
            case DATE_EPOCH_TO_ISO -> applyEpochToIso(value);
            case SPLIT_FULLNAME_TO_FIRST_LAST -> applySplitFullname(value);
            case CONCAT_ADDRESS_LINES -> applyConcatAddressLines(value);
        };
    }

    // ── Individual transform implementations ────────────────────

    private Object applyUppercase(Object value) {
        return value.toString().toUpperCase();
    }

    private Object applyLowercase(Object value) {
        return value.toString().toLowerCase();
    }

    /**
     * Converts an ISO-8601 date/time string to Unix epoch milliseconds.
     * Supports both {@code Instant.parse}-compatible and ISO offset formats.
     */
    private Object applyIsoToEpoch(Object value) {
        try {
            String s = value.toString();
            Instant instant = Instant.parse(s);
            return instant.toEpochMilli();
        } catch (DateTimeParseException e) {
            log.warn("DATE_ISO_TO_EPOCH: failed to parse '{}': {}", value, e.getMessage());
            return value;
        }
    }

    /**
     * Converts Unix epoch milliseconds to an ISO-8601 UTC string.
     */
    private Object applyEpochToIso(Object value) {
        try {
            long epochMillis;
            if (value instanceof Number n) {
                epochMillis = n.longValue();
            } else {
                epochMillis = Long.parseLong(value.toString());
            }
            Instant instant = Instant.ofEpochMilli(epochMillis);
            return DateTimeFormatter.ISO_INSTANT.format(instant);
        } catch (NumberFormatException e) {
            log.warn("DATE_EPOCH_TO_ISO: failed to parse '{}': {}", value, e.getMessage());
            return value;
        }
    }

    /**
     * Splits a full name into {@code firstName} and {@code lastName}.
     *
     * <p>Splitting logic:</p>
     * <ul>
     *   <li>"First Last" → {firstName: "First", lastName: "Last"}</li>
     *   <li>"First Middle Last" → {firstName: "First", lastName: "Middle Last"}</li>
     *   <li>"SingleName" → {firstName: "SingleName", lastName: ""}</li>
     * </ul>
     */
    private Object applySplitFullname(Object value) {
        String fullName = value.toString().trim();
        Map<String, Object> result = new LinkedHashMap<>();

        int spaceIdx = fullName.indexOf(' ');
        if (spaceIdx > 0) {
            result.put("firstName", fullName.substring(0, spaceIdx));
            result.put("lastName", fullName.substring(spaceIdx + 1).trim());
        } else {
            result.put("firstName", fullName);
            result.put("lastName", "");
        }
        return result;
    }

    /**
     * Concatenates address-related fields into a single string.
     *
     * <p>If the value is a {@code Map}, joins all non-null values with
     * {@code ", "}. If it's already a string, returns it as-is.</p>
     */
    @SuppressWarnings("unchecked")
    private Object applyConcatAddressLines(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            StringBuilder sb = new StringBuilder();
            for (Object v : mapValue.values()) {
                if (v != null) {
                    if (!sb.isEmpty()) {
                        sb.append(", ");
                    }
                    sb.append(v.toString());
                }
            }
            return sb.toString();
        }
        // If it's already a string or non-map, return as-is
        return value.toString();
    }
}
