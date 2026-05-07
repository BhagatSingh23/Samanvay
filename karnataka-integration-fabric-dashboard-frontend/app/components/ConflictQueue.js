"use client";
import { useState, useEffect } from "react";
import { useConflicts } from "../lib/hooks";
import { fmtDateTime, shortId } from "../lib/utils";
import { IconChevronDown, IconShield } from "./icons";

function slaColor(minutesRemaining) {
  if (minutesRemaining > 120) return "var(--status-delivered)";
  if (minutesRemaining > 0) return "var(--status-pending)";
  return "var(--status-failed)";
}

function ConflictRow({ conflict, onResolve, escalation }) {
  const [expanded, setExpanded] = useState(false);
  const [resolving, setResolving] = useState(false);
  const [selectedWinner, setSelectedWinner] = useState(null);

  async function handleResolve() {
    if (!selectedWinner) return;
    setResolving(true);
    try {
      // If we have an escalation, resolve via escalation endpoint
      if (escalation) {
        const res = await fetch(`/api/v1/escalations/${escalation.escalationId}/resolve`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ winningEventId: selectedWinner }),
        });
        if (res.ok) {
          onResolve?.();
          return;
        }
      }
      // Fallback to original conflict resolution
      const res = await fetch(`/api/v1/conflicts/${conflict.conflictId}/resolve`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ winnerEventId: selectedWinner }),
      });
      if (res.ok) {
        onResolve?.();
      }
    } catch (err) {
      console.error("Resolve failed:", err);
    } finally {
      setResolving(false);
    }
  }

  const e1 = conflict.event1Summary || {};
  const e2 = conflict.event2Summary || {};
  const isHoldForReview = conflict.resolutionPolicy === "HOLD_FOR_REVIEW";

  return (
    <>
      <tr
        className="cursor-pointer"
        onClick={() => setExpanded(!expanded)}
      >
        <td>
          <span className="font-mono text-xs text-[var(--text-secondary)]">
            {shortId(conflict.conflictId)}
          </span>
        </td>
        <td>
          <span className="font-mono text-xs text-[var(--gold-400)]">
            {conflict.ubid || "—"}
          </span>
        </td>
        <td className="text-xs">{conflict.fieldInDispute || "—"}</td>
        <td>
          <span className="status-badge status-CONFLICT_HELD">
            {conflict.resolutionPolicy || "—"}
          </span>
        </td>
        <td>
          {conflict.winningEventId ? (
            <span className="status-badge status-DELIVERED">Resolved</span>
          ) : (
            <span className="status-badge status-PENDING">Pending</span>
          )}
        </td>
        {/* SLA Deadline column */}
        <td>
          {isHoldForReview && escalation ? (
            <div style={{ lineHeight: 1.3 }}>
              <span
                style={{
                  color: slaColor(escalation.minutesRemaining),
                  fontWeight: escalation.minutesRemaining <= 0 ? 700 : 400,
                  fontSize: "0.75rem",
                }}
              >
                {fmtDateTime(escalation.slaDeadline)}
              </span>
              <br />
              <span
                style={{
                  fontSize: "0.65rem",
                  color: slaColor(escalation.minutesRemaining),
                  fontWeight: escalation.minutesRemaining <= 0 ? 700 : 500,
                }}
              >
                {escalation.minutesRemaining <= 0
                  ? `⚠ Breached (${Math.abs(escalation.minutesRemaining)} min ago)`
                  : `(${escalation.minutesRemaining} min remaining)`}
              </span>
            </div>
          ) : (
            <span className="text-xs text-[var(--text-muted)]">—</span>
          )}
        </td>
        <td>
          <IconChevronDown
            className={`transition-transform duration-200 text-[var(--text-muted)] ${expanded ? "rotate-180" : ""}`}
          />
        </td>
      </tr>

      {expanded && (
        <tr className="animate-fade-in">
          <td colSpan={7} className="!p-0">
            <div className="bg-[var(--navy-800)] border-y border-[var(--glass-border)] p-5">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {/* Event 1 */}
                <div
                  className={`glass-card p-4 ${selectedWinner === conflict.event1Id ? "!border-[var(--gold-400)]" : ""}`}
                  onClick={(e) => { e.stopPropagation(); setSelectedWinner(conflict.event1Id); }}
                  style={{ cursor: "pointer" }}
                >
                  <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-2">
                      <span className="text-xs font-semibold text-[var(--text-muted)]">EVENT 1</span>
                      {selectedWinner === conflict.event1Id && (
                        <span className="text-[0.6rem] px-2 py-0.5 rounded bg-[var(--gold-500)] text-[var(--navy-950)] font-bold">SELECTED</span>
                      )}
                    </div>
                    <span className="text-[0.65rem] text-[var(--text-muted)]">{e1.sourceSystemId}</span>
                  </div>
                  <div className="space-y-1 text-xs text-[var(--text-secondary)]">
                    <p>ID: <span className="font-mono">{shortId(conflict.event1Id)}</span></p>
                    <p>Service: {e1.serviceType || "—"}</p>
                    <p>Ingested: {fmtDateTime(e1.ingestionTimestamp)}</p>
                    <p>Status: <span className={`status-badge status-${e1.status}`}>{e1.status}</span></p>
                  </div>
                </div>

                {/* Event 2 */}
                <div
                  className={`glass-card p-4 ${selectedWinner === conflict.event2Id ? "!border-[var(--gold-400)]" : ""}`}
                  onClick={(e) => { e.stopPropagation(); setSelectedWinner(conflict.event2Id); }}
                  style={{ cursor: "pointer" }}
                >
                  <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-2">
                      <span className="text-xs font-semibold text-[var(--text-muted)]">EVENT 2</span>
                      {selectedWinner === conflict.event2Id && (
                        <span className="text-[0.6rem] px-2 py-0.5 rounded bg-[var(--gold-500)] text-[var(--navy-950)] font-bold">SELECTED</span>
                      )}
                    </div>
                    <span className="text-[0.65rem] text-[var(--text-muted)]">{e2.sourceSystemId}</span>
                  </div>
                  <div className="space-y-1 text-xs text-[var(--text-secondary)]">
                    <p>ID: <span className="font-mono">{shortId(conflict.event2Id)}</span></p>
                    <p>Service: {e2.serviceType || "—"}</p>
                    <p>Ingested: {fmtDateTime(e2.ingestionTimestamp)}</p>
                    <p>Status: <span className={`status-badge status-${e2.status}`}>{e2.status}</span></p>
                  </div>
                </div>
              </div>

              {/* SLA escalation info bar */}
              {escalation && (
                <div
                  className="mt-4 p-3 rounded-lg"
                  style={{
                    background: escalation.minutesRemaining <= 0
                      ? "rgba(248,113,113,0.08)"
                      : escalation.minutesRemaining <= 120
                        ? "rgba(251,191,36,0.08)"
                        : "rgba(52,211,153,0.08)",
                    border: `1px solid ${slaColor(escalation.minutesRemaining)}25`,
                  }}
                >
                  <div className="flex items-center justify-between text-xs">
                    <div>
                      <span style={{ color: slaColor(escalation.minutesRemaining), fontWeight: 600 }}>
                        SLA Escalation Level {escalation.escalationLevel}
                      </span>
                      <span className="text-[var(--text-muted)] ml-2">
                        Fallback: {escalation.fallbackPolicy}
                      </span>
                    </div>
                    <span
                      className="status-badge"
                      style={{
                        background: `${slaColor(escalation.minutesRemaining)}15`,
                        color: slaColor(escalation.minutesRemaining),
                      }}
                    >
                      {escalation.status}
                    </span>
                  </div>
                </div>
              )}

              {/* Resolve button */}
              {!conflict.winningEventId && (
                <div className="flex items-center justify-between mt-4 pt-4 border-t border-[var(--glass-border)]">
                  <p className="text-xs text-[var(--text-muted)]">
                    {selectedWinner
                      ? `Selected winner: ${shortId(selectedWinner)}`
                      : "Click an event card above to select the winner"}
                  </p>
                  <button
                    className="btn-primary flex items-center gap-2"
                    disabled={!selectedWinner || resolving}
                    onClick={(e) => { e.stopPropagation(); handleResolve(); }}
                  >
                    <IconShield style={{ width: 14, height: 14 }} />
                    {resolving
                      ? "Resolving…"
                      : escalation
                        ? "Resolve & Reset Escalation"
                        : "Override Resolution"}
                  </button>
                </div>
              )}
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

export default function ConflictQueue() {
  const [page, setPage] = useState(0);
  const { data, isLoading, mutate } = useConflicts(false, page, 20);
  const [escalations, setEscalations] = useState([]);

  // Fetch active escalations on mount and after mutations
  useEffect(() => {
    async function fetchEscalations() {
      try {
        const res = await fetch("/api/v1/escalations");
        if (res.ok) {
          setEscalations(await res.json());
        }
      } catch {
        // backend may be down
      }
    }
    fetchEscalations();
  }, [data]); // re-fetch when conflict data changes

  if (isLoading) {
    return (
      <div className="p-6 space-y-3">
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="skeleton h-12 w-full" />
        ))}
      </div>
    );
  }

  const conflicts = data?.content || [];
  const total = data?.totalElements || 0;
  const totalPages = Math.ceil(total / 20);

  // Build a map of conflictId → escalation for quick lookup
  const escMap = {};
  for (const esc of escalations) {
    escMap[esc.conflictId] = esc;
  }

  return (
    <div className="animate-fade-in">
      <div className="flex items-center justify-between px-5 py-3 border-b border-[var(--glass-border)]">
        <p className="text-xs text-[var(--text-muted)]">
          <span className="text-[var(--status-conflict-held)] font-semibold">{total}</span> unresolved conflict{total !== 1 ? "s" : ""}
        </p>
        <p className="text-[0.65rem] text-[var(--text-muted)]">
          Click a row to expand details
        </p>
      </div>

      <div className="overflow-x-auto">
        <table className="data-table">
          <thead>
            <tr>
              <th>Conflict ID</th>
              <th>UBID</th>
              <th>Field in Dispute</th>
              <th>Policy</th>
              <th>Status</th>
              <th>SLA Deadline</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {conflicts.map((c) => (
              <ConflictRow
                key={c.conflictId}
                conflict={c}
                escalation={escMap[c.conflictId] || null}
                onResolve={() => mutate()}
              />
            ))}
            {conflicts.length === 0 && (
              <tr>
                <td colSpan={7} className="text-center py-12">
                  <p className="text-[var(--text-muted)] text-sm">
                    No unresolved conflicts 🎉
                  </p>
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-2 p-4">
          <button
            className="btn-ghost"
            disabled={page === 0}
            onClick={() => setPage(p => p - 1)}
          >
            Previous
          </button>
          <span className="text-xs text-[var(--text-muted)]">
            Page {page + 1} of {totalPages}
          </span>
          <button
            className="btn-ghost"
            disabled={page >= totalPages - 1}
            onClick={() => setPage(p => p + 1)}
          >
            Next
          </button>
        </div>
      )}
    </div>
  );
}
