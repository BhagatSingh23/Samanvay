"use client";
import { useState } from "react";
import { useDLQ } from "../lib/hooks";
import { fmtDateTime, shortId } from "../lib/utils";
import { IconRefresh, IconXCircle } from "./icons";

function DLQRow({ item, onRedispatch }) {
  const [loading, setLoading] = useState(false);
  const [success, setSuccess] = useState(false);

  async function handleRedispatch() {
    setLoading(true);
    try {
      const res = await fetch(`/api/v1/dlq/${item.dlqId}/redispatch`, {
        method: "POST",
      });
      if (res.ok) {
        setSuccess(true);
        onRedispatch?.();
      }
    } catch (err) {
      console.error("Redispatch failed:", err);
    } finally {
      setLoading(false);
    }
  }

  return (
    <tr className="animate-fade-in">
      <td className="font-mono text-xs">{fmtDateTime(item.parkedAt)}</td>
      <td>
        <span className="font-mono text-xs text-[var(--gold-400)]">
          {item.ubid || "—"}
        </span>
      </td>
      <td className="text-xs">{item.targetSystemId || "—"}</td>
      <td>
        <span className="font-mono text-xs text-[var(--text-secondary)]">
          {shortId(item.eventId)}
        </span>
      </td>
      <td>
        <div className="max-w-xs">
          <p className="text-xs text-[var(--status-failed)] leading-relaxed line-clamp-2">
            {item.failureReason || "Unknown error"}
          </p>
        </div>
      </td>
      <td>
        {success ? (
          <span className="status-badge status-DELIVERED">Re-dispatched</span>
        ) : (
          <button
            className="btn-ghost flex items-center gap-1.5 text-xs"
            onClick={handleRedispatch}
            disabled={loading}
          >
            <IconRefresh className={loading ? "animate-spin" : ""} style={{ width: 13, height: 13 }} />
            {loading ? "Sending…" : "Re-dispatch"}
          </button>
        )}
      </td>
    </tr>
  );
}

export default function DeadLetterQueue() {
  const { data, isLoading, mutate } = useDLQ();

  if (isLoading) {
    return (
      <div className="p-6 space-y-3">
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="skeleton h-12 w-full" />
        ))}
      </div>
    );
  }

  const items = data || [];

  return (
    <div className="animate-fade-in">
      <div className="flex items-center justify-between px-5 py-3 border-b border-[var(--glass-border)]">
        <p className="text-xs text-[var(--text-muted)]">
          <span className="text-[var(--status-failed)] font-semibold">{items.length}</span> dead-lettered event{items.length !== 1 ? "s" : ""}
        </p>
        <div className="flex items-center gap-1 text-[var(--status-failed)]">
          <IconXCircle style={{ width: 14, height: 14 }} />
          <span className="text-[0.65rem]">Failed after max retries</span>
        </div>
      </div>

      <div className="overflow-x-auto">
        <table className="data-table">
          <thead>
            <tr>
              <th>Parked At</th>
              <th>UBID</th>
              <th>Target</th>
              <th>Event ID</th>
              <th>Failure Reason</th>
              <th>Action</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <DLQRow
                key={item.dlqId}
                item={item}
                onRedispatch={() => mutate()}
              />
            ))}
            {items.length === 0 && (
              <tr>
                <td colSpan={6} className="text-center py-12">
                  <p className="text-[var(--text-muted)] text-sm">
                    Dead letter queue is empty 🎉
                  </p>
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
