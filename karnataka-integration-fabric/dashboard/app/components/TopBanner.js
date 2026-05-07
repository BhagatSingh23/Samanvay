"use client";
import { useBannerStats } from "../lib/hooks";
import { IconActivity, IconShield, IconInbox } from "./icons";

function StatCard({ icon: Icon, label, value, color, pulse }) {
  return (
    <div className={`flex items-center gap-3 ${pulse ? "animate-pulse-glow" : ""}`}>
      <div
        className="w-9 h-9 rounded-lg flex items-center justify-center"
        style={{ background: `${color}15` }}
      >
        <Icon style={{ color, width: 18, height: 18 }} />
      </div>
      <div>
        <p className="text-lg font-bold leading-none" style={{ color }}>
          {value !== undefined && value !== null ? value.toLocaleString() : "—"}
        </p>
        <p className="text-[0.6rem] text-[var(--text-muted)] uppercase tracking-wider mt-0.5">
          {label}
        </p>
      </div>
    </div>
  );
}

export default function TopBanner() {
  const { data, isLoading } = useBannerStats();
  const stats = data || {};

  return (
    <div className="glass-card px-6 py-4 flex flex-wrap items-center justify-between gap-4">
      <div className="flex items-center gap-3">
        <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-[var(--gold-500)] to-[var(--gold-300)] flex items-center justify-center">
          <span className="text-[var(--navy-950)] font-bold text-sm">KA</span>
        </div>
        <div>
          <h1 className="text-sm font-bold tracking-tight text-[var(--text-primary)]">
            Karnataka Integration Fabric
          </h1>
          <p className="text-[0.6rem] text-[var(--text-muted)]">
            Operational Dashboard
          </p>
        </div>
      </div>

      <div className="flex items-center gap-8">
        {isLoading ? (
          <>
            <div className="skeleton h-9 w-28 rounded-lg" />
            <div className="skeleton h-9 w-28 rounded-lg" />
            <div className="skeleton h-9 w-28 rounded-lg" />
          </>
        ) : (
          <>
            <StatCard
              icon={IconActivity}
              label="Events Today"
              value={stats.eventsToday}
              color="var(--status-received)"
            />
            <StatCard
              icon={IconShield}
              label="Conflicts Today"
              value={stats.conflictsToday}
              color="var(--status-conflict-held)"
              pulse={stats.conflictsToday > 0}
            />
            <StatCard
              icon={IconInbox}
              label="DLQ Items"
              value={stats.dlqCount}
              color={stats.dlqCount > 0 ? "var(--status-failed)" : "var(--status-delivered)"}
              pulse={stats.dlqCount > 5}
            />
          </>
        )}
      </div>
    </div>
  );
}
