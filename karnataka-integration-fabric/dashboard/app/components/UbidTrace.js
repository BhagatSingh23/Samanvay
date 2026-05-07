"use client";
import { useState } from "react";
import { useAuditByUbid } from "../lib/hooks";
import { fmtDateTime } from "../lib/utils";
import { IconSearch, auditTypeConfig } from "./icons";

function DiffView({ before, after }) {
  if (!before && !after) return null;
  const allKeys = new Set([
    ...Object.keys(before || {}),
    ...Object.keys(after || {}),
  ]);
  if (allKeys.size === 0) return null;

  return (
    <div className="grid grid-cols-2 gap-3 mt-3">
      <div>
        <p className="text-[0.65rem] font-semibold text-[var(--text-muted)] mb-1 uppercase tracking-wider">Before</p>
        <div className="rounded-lg bg-[var(--navy-800)] p-3 font-mono text-xs space-y-1 min-h-[40px]">
          {before ? (
            Object.entries(before).map(([k, v]) => (
              <div key={k} className="diff-removed px-2 py-0.5 rounded">
                <span className="text-[var(--text-muted)]">{k}:</span>{" "}
                {typeof v === "object" ? JSON.stringify(v) : String(v)}
              </div>
            ))
          ) : (
            <span className="text-[var(--text-muted)]">—</span>
          )}
        </div>
      </div>
      <div>
        <p className="text-[0.65rem] font-semibold text-[var(--text-muted)] mb-1 uppercase tracking-wider">After</p>
        <div className="rounded-lg bg-[var(--navy-800)] p-3 font-mono text-xs space-y-1 min-h-[40px]">
          {after ? (
            Object.entries(after).map(([k, v]) => (
              <div key={k} className="diff-added px-2 py-0.5 rounded">
                <span className="text-[var(--text-muted)]">{k}:</span>{" "}
                {typeof v === "object" ? JSON.stringify(v) : String(v)}
              </div>
            ))
          ) : (
            <span className="text-[var(--text-muted)]">—</span>
          )}
        </div>
      </div>
    </div>
  );
}

function TimelineItem({ event, isLast }) {
  const config = auditTypeConfig(event.auditEventType);
  const Icon = config.icon;

  return (
    <div className="relative flex gap-4 pb-8 animate-fade-in">
      {/* Vertical line */}
      {!isLast && (
        <div
          className="absolute left-[17px] top-[36px] bottom-0 w-[2px]"
          style={{ background: `linear-gradient(to bottom, ${config.color}33, transparent)` }}
        />
      )}

      {/* Dot */}
      <div className="flex-shrink-0 mt-1">
        <div
          className="w-[36px] h-[36px] rounded-full flex items-center justify-center"
          style={{ background: `${config.color}18`, border: `2px solid ${config.color}44` }}
        >
          <Icon style={{ color: config.color, width: 16, height: 16 }} />
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 min-w-0">
        <div className="flex items-start justify-between gap-3">
          <div>
            <p className="text-sm font-medium" style={{ color: config.color }}>
              {config.label}
            </p>
            <div className="flex flex-wrap gap-x-4 gap-y-1 mt-1 text-xs text-[var(--text-muted)]">
              <span>Event: <span className="font-mono text-[var(--text-secondary)]">{event.eventId?.substring(0, 8) || "—"}</span></span>
              {event.sourceSystem && <span>Source: <span className="text-[var(--text-secondary)]">{event.sourceSystem}</span></span>}
              {event.targetSystem && <span>Target: <span className="text-[var(--text-secondary)]">{event.targetSystem}</span></span>}
              {event.conflictPolicy && (
                <span className="px-2 py-0.5 rounded bg-[var(--navy-700)] text-[var(--status-conflict-held)]">
                  {event.conflictPolicy}
                </span>
              )}
            </div>
          </div>
          <span className="text-[0.65rem] text-[var(--text-muted)] whitespace-nowrap font-mono">
            {fmtDateTime(event.timestamp)}
          </span>
        </div>

        <DiffView before={event.beforeState} after={event.afterState} />
      </div>
    </div>
  );
}

export default function UbidTrace() {
  const [query, setQuery] = useState("");
  const [ubid, setUbid] = useState(null);

  const { data, isLoading } = useAuditByUbid(ubid);

  function handleSubmit(e) {
    e.preventDefault();
    if (query.trim()) setUbid(query.trim());
  }

  return (
    <div className="animate-fade-in p-5">
      {/* Search */}
      <form onSubmit={handleSubmit} className="flex gap-3 max-w-xl">
        <div className="relative flex-1">
          <IconSearch className="absolute left-3 top-1/2 -translate-y-1/2 text-[var(--text-muted)]" />
          <input
            type="text"
            placeholder="Enter UBID (e.g. KA-2024-0001)"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            className="input-field pl-10"
          />
        </div>
        <button type="submit" className="btn-primary whitespace-nowrap" disabled={!query.trim()}>
          Trace
        </button>
      </form>

      {/* Loading */}
      {isLoading && (
        <div className="mt-8 space-y-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="flex gap-4">
              <div className="skeleton w-9 h-9 rounded-full flex-shrink-0" />
              <div className="flex-1 space-y-2">
                <div className="skeleton h-4 w-40" />
                <div className="skeleton h-3 w-64" />
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Timeline */}
      {data && !isLoading && (
        <div className="mt-8">
          <div className="flex items-center gap-3 mb-6">
            <span className="text-xs text-[var(--text-muted)]">UBID</span>
            <span className="font-mono text-sm text-[var(--gold-400)] font-semibold">{data.ubid}</span>
            <span className="text-xs text-[var(--text-muted)]">
              {data.events?.length || 0} audit record{data.events?.length !== 1 ? "s" : ""}
            </span>
          </div>
          <div className="max-w-3xl">
            {(data.events || []).map((evt, i) => (
              <TimelineItem
                key={evt.auditId || i}
                event={evt}
                isLast={i === data.events.length - 1}
              />
            ))}
          </div>
          {(!data.events || data.events.length === 0) && (
            <p className="text-[var(--text-muted)] text-sm mt-4">
              No audit records found for this UBID.
            </p>
          )}
        </div>
      )}

      {/* Empty state */}
      {!ubid && !isLoading && (
        <div className="flex flex-col items-center justify-center mt-20 text-center">
          <div className="w-16 h-16 rounded-full bg-[var(--navy-800)] flex items-center justify-center mb-4">
            <IconSearch className="text-[var(--text-muted)]" style={{ width: 28, height: 28 }} />
          </div>
          <p className="text-[var(--text-muted)] text-sm">
            Search for a UBID to view its full audit trail
          </p>
          <p className="text-[var(--text-muted)] text-xs mt-1 opacity-60">
            Each step in the event lifecycle will be shown as a timeline
          </p>
        </div>
      )}
    </div>
  );
}
