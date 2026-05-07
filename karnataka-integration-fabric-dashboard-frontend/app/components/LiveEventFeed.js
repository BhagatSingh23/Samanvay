"use client";
import { useEvents } from "../lib/hooks";
import { fmtTime, shortId } from "../lib/utils";

function StatusBadge({ status }) {
  return (
    <span className={`status-badge status-${status || "PENDING"}`}>
      <span
        className="inline-block w-1.5 h-1.5 rounded-full"
        style={{ background: "currentColor" }}
      />
      {status || "—"}
    </span>
  );
}

export default function LiveEventFeed() {
  const { events, isLoading } = useEvents(50);

  if (isLoading) {
    return (
      <div className="p-6 space-y-3">
        {Array.from({ length: 8 }).map((_, i) => (
          <div key={i} className="skeleton h-10 w-full" />
        ))}
      </div>
    );
  }

  const rows = events || [];

  return (
    <div className="animate-fade-in">
      <div className="flex items-center justify-between px-5 py-3 border-b border-[var(--glass-border)]">
        <p className="text-xs text-[var(--text-muted)]">
          Showing latest <span className="text-[var(--text-primary)] font-semibold">{rows.length}</span> events
          <span className="ml-2 inline-flex items-center gap-1">
            <span className="w-1.5 h-1.5 rounded-full bg-green-400 animate-pulse" />
            <span className="text-green-400">Live</span>
          </span>
        </p>
        <p className="text-[0.65rem] text-[var(--text-muted)]">Auto-refresh 3s</p>
      </div>

      <div className="overflow-x-auto max-h-[calc(100vh-300px)]">
        <table className="data-table">
          <thead>
            <tr>
              <th>Time</th>
              <th>UBID</th>
              <th>Source</th>
              <th>Service Type</th>
              <th>Status</th>
              <th>Target Systems</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((evt, i) => (
              <tr key={evt.eventId || i} style={{ animationDelay: `${i * 20}ms` }} className="animate-fade-in">
                <td className="font-mono text-xs whitespace-nowrap">
                  {fmtTime(evt.ingestionTimestamp || evt.eventTimestamp)}
                </td>
                <td>
                  <span className="font-mono text-xs text-[var(--gold-400)]">
                    {evt.ubid || "—"}
                  </span>
                </td>
                <td>
                  <span className="inline-flex items-center gap-1.5">
                    <span
                      className="w-2 h-2 rounded-full"
                      style={{
                        background: evt.sourceSystemId === "SWS" ? "var(--gold-400)" : "var(--navy-400)",
                      }}
                    />
                    <span className="text-xs">{evt.sourceSystemId || "—"}</span>
                  </span>
                </td>
                <td className="text-xs">{evt.serviceType || "—"}</td>
                <td>
                  <StatusBadge status={evt.status} />
                </td>
                <td>
                  <div className="flex flex-wrap gap-1">
                    {(evt.targetSystems || []).map((t) => (
                      <span
                        key={t}
                        className="px-2 py-0.5 rounded text-[0.65rem] font-medium bg-[var(--navy-700)] text-[var(--text-secondary)]"
                      >
                        {t}
                      </span>
                    ))}
                    {(!evt.targetSystems || evt.targetSystems.length === 0) && (
                      <span className="text-xs text-[var(--text-muted)]">—</span>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
