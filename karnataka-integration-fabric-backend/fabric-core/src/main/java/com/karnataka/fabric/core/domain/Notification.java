package com.karnataka.fabric.core.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain entity mapping to the {@code notifications} table.
 * Used for in-dashboard notification bell and escalation alerts.
 */
public class Notification {

    private UUID notificationId;
    private UUID conflictId;
    private String message;
    private boolean isRead;
    private Instant createdAt;

    public Notification() {}

    // ── Getters & Setters ──────────────────────────────────────

    public UUID getNotificationId() { return notificationId; }
    public void setNotificationId(UUID notificationId) { this.notificationId = notificationId; }

    public UUID getConflictId() { return conflictId; }
    public void setConflictId(UUID conflictId) { this.conflictId = conflictId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public boolean isRead() { return isRead; }
    public void setRead(boolean read) { isRead = read; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
