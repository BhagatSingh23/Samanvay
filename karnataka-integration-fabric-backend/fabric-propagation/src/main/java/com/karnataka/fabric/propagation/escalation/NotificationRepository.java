package com.karnataka.fabric.propagation.escalation;

import com.karnataka.fabric.core.domain.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JDBC-backed repository for the {@code notifications} table.
 */
@Repository
public class NotificationRepository {

    private static final Logger log = LoggerFactory.getLogger(NotificationRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public NotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<Notification> ROW_MAPPER = (rs, rowNum) -> {
        Notification n = new Notification();
        n.setNotificationId(rs.getObject("notification_id", UUID.class));
        n.setConflictId(rs.getObject("conflict_id", UUID.class));
        n.setMessage(rs.getString("message"));
        n.setRead(rs.getBoolean("is_read"));
        Timestamp ts = rs.getTimestamp("created_at");
        n.setCreatedAt(ts != null ? ts.toInstant() : null);
        return n;
    };

    /**
     * Inserts a new notification.
     */
    public void save(Notification n) {
        if (n.getNotificationId() == null) {
            n.setNotificationId(UUID.randomUUID());
        }
        jdbcTemplate.update(
                """
                INSERT INTO notifications (notification_id, conflict_id, message, is_read, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                n.getNotificationId(),
                n.getConflictId(),
                n.getMessage(),
                n.isRead(),
                Timestamp.from(n.getCreatedAt() != null ? n.getCreatedAt() : Instant.now())
        );
        log.debug("Saved notification: id={}, conflictId={}", n.getNotificationId(), n.getConflictId());
    }

    /**
     * Returns unread notifications, newest first.
     */
    public List<Notification> findUnread(int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM notifications WHERE is_read = FALSE ORDER BY created_at DESC LIMIT ?",
                ROW_MAPPER,
                limit
        );
    }

    /**
     * Marks a single notification as read.
     */
    public void markRead(UUID notificationId) {
        jdbcTemplate.update(
                "UPDATE notifications SET is_read = TRUE WHERE notification_id = ?",
                notificationId
        );
        log.debug("Marked notification {} as read", notificationId);
    }
}
