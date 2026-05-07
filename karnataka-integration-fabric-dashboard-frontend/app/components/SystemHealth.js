"use client";
import { useDepartments, useDriftAlerts } from "../lib/hooks";
import { timeAgo } from "../lib/utils";
import { IconServer, IconAlertTriangle, IconCheckCircle, IconClock } from "./icons";

function CbBadge({ status }) {
  const config = {
    CLOSED:    { color: "var(--status-delivered)", label: "Closed", bg: "rgba(52,211,153,0.12)" },
    HALF_OPEN: { color: "var(--status-pending)",   label: "Half-Open", bg: "rgba(251,191,36,0.12)" },
    OPEN:      { color: "var(--status-failed)",    label: "Open", bg: "rgba(248,113,113,0.12)" },
  };
  const c = config[status] || config.CLOSED;
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[0.65rem] font-semibold" style={{ background: c.bg, color: c.color }}>
      <span className="w-1.5 h-1.5 rounded-full" style={{ background: c.color }} />
      {c.label}
    </span>
  );
}

function CoverageBar({ pct }) {
  const color = pct >= 90 ? "var(--status-delivered)"
               : pct >= 70 ? "var(--status-pending)"
               : "var(--status-failed)";
  return (
    <div className="flex items-center gap-2">
      <div className="flex-1 h-2 rounded-full bg-[var(--navy-700)] overflow-hidden">
        <div
          className="h-full rounded-full transition-all duration-700"
          style={{ width: `${pct}%`, background: color }}
        />
      </div>
      <span className="text-xs font-mono font-semibold" style={{ color }}>{pct}%</span>
    </div>
  );
}

function DeptCard({ dept }) {
  const modeColors = {
    WEBHOOK:  { bg: "rgba(96,165,250,0.12)", color: "var(--status-received)" },
    POLLING:  { bg: "rgba(251,191,36,0.12)", color: "var(--status-pending)" },
    SNAPSHOT: { bg: "rgba(167,139,250,0.12)", color: "var(--status-conflict-held)" },
  };
  const m = modeColors[dept.adapterMode] || modeColors.WEBHOOK;

  return (
    <div className="glass-card glass-card-hover p-5 animate-fade-in">
      <div className="flex items-start justify-between mb-4">
        <div className="flex items-center gap-3">
          <div
            className="w-10 h-10 rounded-lg flex items-center justify-center"
            style={{ background: m.bg }}
          >
            <IconServer style={{ color: m.color, width: 20, height: 20 }} />
          </div>
          <div>
            <h3 className="text-sm font-semibold text-[var(--text-primary)]">{dept.deptId}</h3>
            <p className="text-[0.65rem] text-[var(--text-muted)]">{dept.name}</p>
          </div>
        </div>
        <CbBadge status={dept.circuitBreakerStatus} />
      </div>

      <div className="space-y-3">
        {/* Adapter mode */}
        <div className="flex items-center justify-between">
          <span className="text-xs text-[var(--text-muted)]">Adapter Mode</span>
          <span
            className="px-2 py-0.5 rounded text-[0.65rem] font-semibold"
            style={{ background: m.bg, color: m.color }}
          >
            {dept.adapterMode}
          </span>
        </div>

        {/* Last poll */}
        <div className="flex items-center justify-between">
          <span className="text-xs text-[var(--text-muted)]">Last Activity</span>
          <span className="flex items-center gap-1 text-xs text-[var(--text-secondary)]">
            <IconClock style={{ width: 12, height: 12 }} />
            {dept.lastPollTime ? timeAgo(dept.lastPollTime) : "Webhook mode"}
          </span>
        </div>

        {/* UBID coverage */}
        <div>
          <div className="flex items-center justify-between mb-1">
            <span className="text-xs text-[var(--text-muted)]">UBID Coverage</span>
          </div>
          <CoverageBar pct={dept.ubidCoverage ?? 0} />
        </div>

        {/* Drift alerts */}
        <div className="flex items-center justify-between">
          <span className="text-xs text-[var(--text-muted)]">Drift Alerts</span>
          {dept.driftAlerts > 0 ? (
            <span className="flex items-center gap-1 text-xs text-[var(--status-pending)]">
              <IconAlertTriangle style={{ width: 13, height: 13 }} />
              {dept.driftAlerts} alert{dept.driftAlerts > 1 ? "s" : ""}
            </span>
          ) : (
            <span className="flex items-center gap-1 text-xs text-[var(--status-delivered)]">
              <IconCheckCircle style={{ width: 13, height: 13 }} />
              Clean
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

export default function SystemHealth() {
  const { data: departments, isLoading } = useDepartments();

  if (isLoading) {
    return (
      <div className="p-6 grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="skeleton h-52 rounded-xl" />
        ))}
      </div>
    );
  }

  const depts = departments || [];

  return (
    <div className="p-5 animate-fade-in">
      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
        {depts.map((dept) => (
          <DeptCard key={dept.deptId} dept={dept} />
        ))}
        {depts.length === 0 && (
          <p className="text-[var(--text-muted)] text-sm col-span-full text-center py-12">
            No departments registered.
          </p>
        )}
      </div>
    </div>
  );
}
