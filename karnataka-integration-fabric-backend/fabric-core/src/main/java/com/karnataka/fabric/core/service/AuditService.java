package com.karnataka.fabric.core.service;

import com.karnataka.fabric.core.domain.AuditEventType;
import java.util.Map;

public interface AuditService {
    void recordAudit(String eventId, String ubid, String source, String target, AuditEventType eventType, Map<String, Object> beforeState, Map<String, Object> afterState);
}
