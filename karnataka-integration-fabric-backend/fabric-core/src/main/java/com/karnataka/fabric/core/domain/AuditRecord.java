package com.karnataka.fabric.core.domain;

import java.time.Instant;
import java.util.Map;

public record AuditRecord(
        String auditId,
        String eventId,
        String ubid,
        String sourceSystem,
        String targetSystem,
        AuditEventType auditEventType,
        Instant timestamp,
        String conflictResolutionPolicy,
        String supersededByEventId,
        Map<String, Object> beforeState,
        Map<String, Object> afterState
) {}
