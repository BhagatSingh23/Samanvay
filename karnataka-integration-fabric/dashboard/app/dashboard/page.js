"use client";
import { useState } from "react";
import TopBanner from "../components/TopBanner";
import LiveEventFeed from "../components/LiveEventFeed";
import UbidTrace from "../components/UbidTrace";
import ConflictQueue from "../components/ConflictQueue";
import DeadLetterQueue from "../components/DeadLetterQueue";
import SystemHealth from "../components/SystemHealth";
import {
  IconActivity,
  IconSearch,
  IconShield,
  IconInbox,
  IconHeart,
} from "../components/icons";

const TABS = [
  { id: "feed",      label: "Live Event Feed",  icon: IconActivity },
  { id: "trace",     label: "UBID Trace",       icon: IconSearch },
  { id: "conflicts", label: "Conflict Queue",   icon: IconShield },
  { id: "dlq",       label: "Dead Letter Queue", icon: IconInbox },
  { id: "health",    label: "System Health",     icon: IconHeart },
];

export default function DashboardPage() {
  const [activeTab, setActiveTab] = useState("feed");

  return (
    <div className="min-h-screen flex flex-col" style={{ background: "var(--navy-950)" }}>
      {/* ── Background gradient ─────────────────────────── */}
      <div
        className="fixed inset-0 pointer-events-none"
        style={{
          background: "radial-gradient(ellipse 80% 60% at 50% 0%, rgba(36, 48, 84, 0.35), transparent 70%)",
        }}
      />

      <div className="relative z-10 flex flex-col flex-1 max-w-[1440px] mx-auto w-full px-4 py-4 gap-4">
        {/* ── Top banner ─────────────────────────────────── */}
        <TopBanner />

        {/* ── Tab bar ────────────────────────────────────── */}
        <div className="glass-card overflow-hidden flex flex-col flex-1">
          <div className="flex items-center border-b border-[var(--glass-border)] overflow-x-auto">
            {TABS.map((tab) => {
              const Icon = tab.icon;
              return (
                <button
                  key={tab.id}
                  id={`tab-${tab.id}`}
                  className={`tab-btn flex items-center gap-2 ${activeTab === tab.id ? "active" : ""}`}
                  onClick={() => setActiveTab(tab.id)}
                >
                  <Icon
                    style={{
                      width: 15,
                      height: 15,
                      opacity: activeTab === tab.id ? 1 : 0.5,
                    }}
                  />
                  {tab.label}
                </button>
              );
            })}
          </div>

          {/* ── Tab content ────────────────────────────────── */}
          <div className="flex-1 overflow-y-auto">
            {activeTab === "feed" && <LiveEventFeed />}
            {activeTab === "trace" && <UbidTrace />}
            {activeTab === "conflicts" && <ConflictQueue />}
            {activeTab === "dlq" && <DeadLetterQueue />}
            {activeTab === "health" && <SystemHealth />}
          </div>
        </div>
      </div>
    </div>
  );
}
