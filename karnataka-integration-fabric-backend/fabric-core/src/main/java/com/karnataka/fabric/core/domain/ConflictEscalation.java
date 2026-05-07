package com.karnataka.fabric.core.domain;

import java.time.Instant;
import java.util.UUID;

public class ConflictEscalation {
    private UUID escalationId;
    private UUID conflictId;
    private String ubid;
    private Integer escalationLevel;
    private Instant slaDeadline;
    private String fallbackPolicy;
    private String status;
    private Instant notifiedAt;
    private String notifiedChannel;
    private Instant autoResolvedAt;
    private Instant createdAt;

    public UUID getEscalationId() { return escalationId; }
    public void setEscalationId(UUID escalationId) { this.escalationId = escalationId; }
    public UUID getConflictId() { return conflictId; }
    public void setConflictId(UUID conflictId) { this.conflictId = conflictId; }
    public String getUbid() { return ubid; }
    public void setUbid(String ubid) { this.ubid = ubid; }
    public Integer getEscalationLevel() { return escalationLevel; }
    public void setEscalationLevel(Integer escalationLevel) { this.escalationLevel = escalationLevel; }
    public Instant getSlaDeadline() { return slaDeadline; }
    public void setSlaDeadline(Instant slaDeadline) { this.slaDeadline = slaDeadline; }
    public String getFallbackPolicy() { return fallbackPolicy; }
    public void setFallbackPolicy(String fallbackPolicy) { this.fallbackPolicy = fallbackPolicy; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(Instant notifiedAt) { this.notifiedAt = notifiedAt; }
    public String getNotifiedChannel() { return notifiedChannel; }
    public void setNotifiedChannel(String notifiedChannel) { this.notifiedChannel = notifiedChannel; }
    public Instant getAutoResolvedAt() { return autoResolvedAt; }
    public void setAutoResolvedAt(Instant autoResolvedAt) { this.autoResolvedAt = autoResolvedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
