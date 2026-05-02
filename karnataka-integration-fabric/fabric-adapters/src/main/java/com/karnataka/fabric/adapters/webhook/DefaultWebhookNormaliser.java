package com.karnataka.fabric.adapters.webhook;

import com.karnataka.fabric.core.domain.CanonicalServiceRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Fallback normaliser used when no department-specific normaliser is registered.
 *
 * <p>Extracts well-known fields from the raw payload by convention:</p>
 * <ul>
 *   <li>{@code ubid} — required, must be present in payload</li>
 *   <li>{@code serviceType} — defaults to {@code "UNKNOWN"}</li>
 *   <li>{@code eventTimestamp} — parsed from payload or defaults to now</li>
 * </ul>
 */
@Component
public class DefaultWebhookNormaliser implements WebhookNormaliser {

    @Override
    public CanonicalServiceRequest normalise(String deptId, Map<String, Object> rawPayload) {

        String ubid = extractString(rawPayload, "ubid");
        if (ubid == null || ubid.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required field 'ubid' in payload for dept=" + deptId);
        }

        String serviceType = extractString(rawPayload, "serviceType");
        if (serviceType == null || serviceType.isBlank()) {
            serviceType = "UNKNOWN";
        }

        Instant eventTimestamp = parseTimestamp(rawPayload.get("eventTimestamp"));

        return new CanonicalServiceRequest(
                UUID.randomUUID().toString(),
                ubid,
                "DEPT_" + deptId,
                serviceType,
                eventTimestamp,
                null,   // set by publishCanonical
                rawPayload,
                null,   // set by publishCanonical
                null    // set by publishCanonical
        );
    }

    private String extractString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private Instant parseTimestamp(Object raw) {
        if (raw == null) return Instant.now();
        if (raw instanceof Instant inst) return inst;
        try {
            return Instant.parse(raw.toString());
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
