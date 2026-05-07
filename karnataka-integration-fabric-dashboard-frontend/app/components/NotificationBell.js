"use client";
import { useState, useEffect, useRef } from "react";
import { IconBell } from "./icons";
import { timeAgo } from "../lib/utils";

/**
 * Notification bell icon with unread badge and dropdown.
 * Polls GET /api/v1/notifications every 30 seconds.
 */
export default function NotificationBell({ onNavigateToConflicts }) {
  const [notifications, setNotifications] = useState([]);
  const [open, setOpen] = useState(false);
  const dropdownRef = useRef(null);

  // Poll notifications
  useEffect(() => {
    let active = true;

    async function fetchNotifications() {
      try {
        const res = await fetch("/api/v1/notifications");
        if (res.ok) {
          const data = await res.json();
          if (active) setNotifications(data);
        }
      } catch {
        // backend may be down — keep existing state
      }
    }

    fetchNotifications();
    const interval = setInterval(fetchNotifications, 30000);
    return () => { active = false; clearInterval(interval); };
  }, []);

  // Close dropdown when clicking outside
  useEffect(() => {
    function handleClick(e) {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target)) {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", handleClick);
    return () => document.removeEventListener("mousedown", handleClick);
  }, []);

  async function markRead(id) {
    try {
      await fetch(`/api/v1/notifications/${id}/read`, { method: "POST" });
      setNotifications((prev) => prev.filter((n) => n.notificationId !== id));
    } catch {
      // ignore
    }
    onNavigateToConflicts?.();
    setOpen(false);
  }

  const count = notifications.length;

  return (
    <div ref={dropdownRef} style={{ position: "relative" }}>
      <button
        id="notification-bell"
        onClick={() => setOpen(!open)}
        style={{
          position: "relative",
          background: "transparent",
          border: "1px solid var(--glass-border)",
          borderRadius: 8,
          padding: "6px 8px",
          cursor: "pointer",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          transition: "all 0.2s",
        }}
        className="glass-card-hover"
      >
        <IconBell style={{ width: 18, height: 18, color: count > 0 ? "var(--gold-400)" : "var(--text-muted)" }} />

        {count > 0 && (
          <span
            style={{
              position: "absolute",
              top: -4,
              right: -4,
              background: "var(--status-failed)",
              color: "#fff",
              fontSize: "0.6rem",
              fontWeight: 700,
              borderRadius: "50%",
              width: 18,
              height: 18,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              lineHeight: 1,
              border: "2px solid var(--navy-950)",
            }}
          >
            {count > 9 ? "9+" : count}
          </span>
        )}
      </button>

      {/* Dropdown */}
      {open && (
        <div
          className="animate-fade-in"
          style={{
            position: "absolute",
            top: "calc(100% + 8px)",
            right: 0,
            width: 360,
            maxHeight: 400,
            overflowY: "auto",
            background: "var(--navy-800)",
            border: "1px solid var(--glass-border)",
            borderRadius: 12,
            boxShadow: "0 12px 40px rgba(0,0,0,0.5)",
            zIndex: 100,
          }}
        >
          <div
            style={{
              padding: "12px 16px",
              borderBottom: "1px solid var(--glass-border)",
              fontSize: "0.75rem",
              fontWeight: 600,
              textTransform: "uppercase",
              letterSpacing: "0.06em",
              color: "var(--text-muted)",
              display: "flex",
              justifyContent: "space-between",
              alignItems: "center",
            }}
          >
            <span>Notifications</span>
            {count > 0 && (
              <span style={{
                background: "rgba(248,113,113,0.15)",
                color: "var(--status-failed)",
                padding: "2px 8px",
                borderRadius: 9999,
                fontSize: "0.65rem",
                fontWeight: 700,
              }}>
                {count} unread
              </span>
            )}
          </div>

          {notifications.length === 0 ? (
            <div style={{ padding: "24px 16px", textAlign: "center", color: "var(--text-muted)", fontSize: "0.82rem" }}>
              No unread notifications
            </div>
          ) : (
            notifications.map((n) => (
              <div
                key={n.notificationId}
                onClick={() => markRead(n.notificationId)}
                style={{
                  padding: "12px 16px",
                  borderBottom: "1px solid rgba(106,130,176,0.08)",
                  cursor: "pointer",
                  transition: "background 0.15s",
                }}
                className="glass-card-hover"
              >
                <p style={{
                  fontSize: "0.8rem",
                  color: "var(--text-primary)",
                  lineHeight: 1.5,
                  margin: 0,
                }}>
                  {n.message}
                </p>
                <p style={{
                  fontSize: "0.65rem",
                  color: "var(--text-muted)",
                  marginTop: 4,
                }}>
                  {timeAgo(n.createdAt)}
                </p>
              </div>
            ))
          )}
        </div>
      )}
    </div>
  );
}
