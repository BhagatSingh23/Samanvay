package com.karnataka.fabric.adapters.webhook;

import com.karnataka.fabric.core.domain.CanonicalServiceRequest;

import java.util.Map;

/**
 * Strategy interface for converting a department-specific raw webhook
 * payload into a {@link CanonicalServiceRequest}.
 *
 * <p>Each department may register a custom normaliser.  If none is
 * registered, the {@link DefaultWebhookNormaliser} is used.</p>
 */
@FunctionalInterface
public interface WebhookNormaliser {

    /**
     * Converts a raw JSON payload into a canonical service request.
     *
     * <p>Implementations should populate at minimum:
     * {@code eventId}, {@code ubid}, {@code sourceSystemId},
     * {@code serviceType}, {@code eventTimestamp}, and {@code payload}.
     * The fields {@code ingestionTimestamp}, {@code checksum}, and
     * {@code status} are set downstream by
     * {@link com.karnataka.fabric.adapters.core.AbstractInboundAdapter#publishCanonical}.</p>
     *
     * @param deptId     the department identifier
     * @param rawPayload the raw JSON body as a map
     * @return a partially-filled canonical request
     */
    CanonicalServiceRequest normalise(String deptId, Map<String, Object> rawPayload);
}
