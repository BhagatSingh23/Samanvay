"use client";
import { useState, useEffect, useCallback } from "react";
import { IconBarChart } from "./icons";

const GRADE_COLORS = {
  A: "#22c55e",
  B: "#3b82f6",
  C: "#f59e0b",
  D: "#ef4444",
};

function gradeColor(grade) {
  return GRADE_COLORS[grade] || "#6b7280";
}

/* ── Tiny SVG sparkline (no external dependency) ───────────── */
function Sparkline({ data, color, width = 200, height = 60 }) {
  if (!data || data.length < 2) {
    return <div style={{ width, height, opacity: 0.3 }} className="skeleton rounded" />;
  }

  const scores = data.map((d) => d.score ?? d);
  const min = Math.min(...scores) - 5;
  const max = Math.max(...scores) + 5;
  const range = max - min || 1;

  const points = scores.map((s, i) => {
    const x = (i / (scores.length - 1)) * width;
    const y = height - ((s - min) / range) * (height - 8) - 4;
    return `${x},${y}`;
  });

  return (
    <svg width={width} height={height} style={{ display: "block" }}>
      {/* Area fill */}
      <polygon
        points={`0,${height} ${points.join(" ")} ${width},${height}`}
        fill={color}
        opacity={0.08}
      />
      {/* Line */}
      <polyline
        points={points.join(" ")}
        fill="none"
        stroke={color}
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      {/* Dots */}
      {scores.map((s, i) => {
        const x = (i / (scores.length - 1)) * width;
        const y = height - ((s - min) / range) * (height - 8) - 4;
        return <circle key={i} cx={x} cy={y} r="3" fill={color} />;
      })}
    </svg>
  );
}

/* ── Department Health Card ────────────────────────────────── */
function DeptCard({ dept }) {
  const [history, setHistory] = useState(null);

  useEffect(() => {
    let active = true;
    fetch(`/api/v1/health/departments/${dept.deptId}/history?days=7`)
      .then((r) => r.ok ? r.json() : [])
      .then((data) => { if (active) setHistory(data); })
      .catch(() => {});
    return () => { active = false; };
  }, [dept.deptId]);

  const color = gradeColor(dept.grade);
  const pct = Math.min(100, Math.max(0, dept.score));

  return (
    <div className="glass-card p-5 flex flex-col gap-4 animate-fade-in">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h3 style={{ fontSize: "0.95rem", fontWeight: 700, color: "var(--text-primary)" }}>
          {dept.deptName}
        </h3>
        {/* Grade badge */}
        <div
          style={{
            width: 42,
            height: 42,
            borderRadius: "50%",
            background: color,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            color: "#fff",
            fontWeight: 800,
            fontSize: "1.1rem",
            boxShadow: `0 0 20px ${color}40`,
          }}
        >
          {dept.grade}
        </div>
      </div>

      {/* Score */}
      <div>
        <p style={{ fontSize: "1.6rem", fontWeight: 700, color, lineHeight: 1 }}>
          {dept.score.toFixed(1)}
          <span style={{ fontSize: "0.85rem", fontWeight: 400, color: "var(--text-muted)" }}>
            {" "}/ 100
          </span>
        </p>
      </div>

      {/* Progress bar */}
      <div
        style={{
          width: "100%",
          height: 6,
          borderRadius: 3,
          background: "rgba(106,130,176,0.1)",
          overflow: "hidden",
        }}
      >
        <div
          style={{
            width: `${pct}%`,
            height: "100%",
            borderRadius: 3,
            background: `linear-gradient(90deg, ${color}80, ${color})`,
            transition: "width 0.6s ease",
          }}
        />
      </div>

      {/* Metrics */}
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "6px 16px" }}>
        <MetricRow emoji="✅" label="Success Rate" value={`${(dept.successRate * 100).toFixed(1)}%`} />
        <MetricRow emoji="📬" label="DLQ Events" value={dept.dlqCount} warn={dept.dlqCount > 0} />
        <MetricRow emoji="⚔️" label="Conflicts" value={dept.conflictCount} warn={dept.conflictCount > 2} />
        <MetricRow emoji="⚠️" label="Drift Alerts" value={dept.driftAlertCount} warn={dept.driftAlertCount > 0} />
        <MetricRow emoji="⏱" label="Avg Latency" value={`${dept.avgLatencyMs}ms`} warn={dept.avgLatencyMs > 2000} />
        <MetricRow emoji="📊" label="Events (24h)" value={dept.totalEventsLast24h} />
      </div>

      {/* Sparkline */}
      <div style={{ borderTop: "1px solid var(--glass-border)", paddingTop: 12 }}>
        <p style={{ fontSize: "0.65rem", color: "var(--text-muted)", marginBottom: 6, textTransform: "uppercase", letterSpacing: "0.05em" }}>
          7-Day Trend
        </p>
        <Sparkline data={history} color={color} width={260} height={50} />
      </div>
    </div>
  );
}

function MetricRow({ emoji, label, value, warn }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 6, fontSize: "0.78rem" }}>
      <span>{emoji}</span>
      <span style={{ color: "var(--text-muted)" }}>{label}:</span>
      <span style={{ fontWeight: 600, color: warn ? "var(--status-failed)" : "var(--text-secondary)" }}>
        {value}
      </span>
    </div>
  );
}

/* ── Alerts Panel ──────────────────────────────────────────── */
function AlertsPanel({ departments, onNavigate }) {
  const atRisk = departments.filter((d) => d.grade === "C" || d.grade === "D");

  if (atRisk.length === 0) return null;

  return (
    <div className="glass-card p-5 animate-fade-in">
      <h3 style={{ fontSize: "0.85rem", fontWeight: 700, color: "var(--status-failed)", marginBottom: 12, textTransform: "uppercase", letterSpacing: "0.05em" }}>
        ⚠ Departments Needing Attention
      </h3>
      <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
        {atRisk.map((dept) => {
          let problem, targetTab;
          if (dept.dlqCount > 0) {
            problem = `🔴 ${dept.dlqCount} events stuck in Dead Letter Queue`;
            targetTab = "dlq";
          } else if (dept.driftAlertCount > 0) {
            problem = `🟡 ${dept.driftAlertCount} open schema drift alerts`;
            targetTab = "health";
          } else {
            problem = `🟠 Low success rate (${(dept.successRate * 100).toFixed(1)}%)`;
            targetTab = "health";
          }

          return (
            <div
              key={dept.deptId}
              onClick={() => onNavigate?.(targetTab)}
              style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
                padding: "10px 14px",
                borderRadius: 8,
                background: "rgba(248,113,113,0.06)",
                border: "1px solid rgba(248,113,113,0.12)",
                cursor: "pointer",
                transition: "background 0.15s",
              }}
              className="glass-card-hover"
            >
              <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                <div
                  style={{
                    width: 28,
                    height: 28,
                    borderRadius: "50%",
                    background: gradeColor(dept.grade),
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                    color: "#fff",
                    fontWeight: 700,
                    fontSize: "0.7rem",
                  }}
                >
                  {dept.grade}
                </div>
                <div>
                  <p style={{ fontSize: "0.82rem", fontWeight: 600, color: "var(--text-primary)" }}>
                    {dept.deptName}
                  </p>
                  <p style={{ fontSize: "0.72rem", color: "var(--text-muted)" }}>
                    {problem}
                  </p>
                </div>
              </div>
              <span style={{ fontSize: "0.65rem", color: "var(--text-muted)" }}>→</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

/* ── Main Tab Component ────────────────────────────────────── */
export default function DeptHealthTab({ onNavigate }) {
  const [departments, setDepartments] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const fetchScores = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetch("/api/v1/health/departments");
      if (!res.ok) throw new Error("Failed to fetch");
      const data = await res.json();
      setDepartments(data.departments || []);
      setError(null);
    } catch (err) {
      setError(err.message);
      // Mock data fallback
      setDepartments([
        { deptId: "FACTORIES", deptName: "Factories", score: 92, grade: "A", successRate: 0.95, dlqCount: 0, conflictCount: 1, driftAlertCount: 0, avgLatencyMs: 550, totalEventsLast24h: 118 },
        { deptId: "SHOP_ESTAB", deptName: "Shop & Establishments", score: 76, grade: "B", successRate: 0.8, dlqCount: 2, conflictCount: 2, driftAlertCount: 0, avgLatencyMs: 1450, totalEventsLast24h: 87 },
        { deptId: "REVENUE", deptName: "Revenue", score: 54, grade: "D", successRate: 0.48, dlqCount: 3, conflictCount: 2, driftAlertCount: 2, avgLatencyMs: 3600, totalEventsLast24h: 40 },
      ]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchScores();
    const interval = setInterval(fetchScores, 60000);
    return () => clearInterval(interval);
  }, [fetchScores]);

  if (loading && !departments) {
    return (
      <div className="p-6 space-y-4">
        <div className="skeleton h-8 w-48 rounded" />
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(320px, 1fr))", gap: 16 }}>
          {[1, 2, 3].map((i) => (
            <div key={i} className="skeleton h-80 w-full rounded-xl" />
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="p-5 space-y-5 animate-fade-in">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <IconBarChart style={{ width: 20, height: 20, color: "var(--gold-400)" }} />
          <div>
            <h2 style={{ fontSize: "1rem", fontWeight: 700, color: "var(--text-primary)" }}>
              Department Sync Health
            </h2>
            <p style={{ fontSize: "0.7rem", color: "var(--text-muted)" }}>
              Rolling 24-hour performance scores across all integrated departments
            </p>
          </div>
        </div>
        <button
          className="btn-ghost flex items-center gap-2"
          onClick={() => fetchScores()}
          disabled={loading}
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <polyline points="23 4 23 10 17 10" /><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10" />
          </svg>
          {loading ? "Refreshing…" : "Refresh"}
        </button>
      </div>

      {/* Card grid */}
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(320px, 1fr))", gap: 16 }}>
        {departments?.map((dept) => (
          <DeptCard key={dept.deptId} dept={dept} />
        ))}
      </div>

      {/* Alerts panel */}
      {departments && <AlertsPanel departments={departments} onNavigate={onNavigate} />}
    </div>
  );
}
