package com.karnataka.fabric.core.service;

import com.karnataka.fabric.core.domain.AuditEventType;

import java.util.Map;

/**
 * Contract for recording audit-trail entries as events traverse the fabric.
 *
 * <p>Defined in {@code fabric-core} so that any module (adapters, propagation, etc.)
 * can depend on the interface without a circular module dependency.
 * The concrete implementation lives in {@code fabric-audit}.</p>
 */
public interface AuditService {

    /**
     * Records an audit entry for the given event.
     *
     * @param eventId      the integration event ID
     * @param ubid         the business entity identifier
     * @param sourceSystem originating system code
     * @param targetSystem destination system code (nullable for RECEIVED)
     * @param eventType    the audit event classification
     * @param beforeState  entity state before mutation (nullable)
     * @param afterState   entity state after mutation (nullable)
     */
    void recordAudit(String eventId,
                     String ubid,
                     String sourceSystem,
                     String targetSystem,
                     AuditEventType eventType,
                     Map<String, Object> beforeState,
                     Map<String, Object> afterState);
}
