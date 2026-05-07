"use client";
import { useState } from "react";

const EXAMPLE_QUESTIONS = [
  "How many events were processed in the last 24 hours?",
  "Which UBIDs are currently stuck in the Dead Letter Queue?",
  "Show all conflicts resolved by SOURCE_PRIORITY this week",
  "Which department had the most delivery failures today?",
];

export default function NlQueryTab() {
  const [question, setQuestion] = useState("");
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);

  const MAX_DISPLAY_ROWS = 50;

  async function handleSubmit() {
    if (!question.trim()) return;
    setLoading(true);
    setError(null);
    setResult(null);

    try {
      const res = await fetch("/api/v1/audit/query", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ question: question.trim() }),
      });

      if (!res.ok) {
        const text = await res.text();
        throw new Error(text || `Request failed with status ${res.status}`);
      }

      const data = await res.json();
      setResult(data);
    } catch (err) {
      setError(err.message || "An unexpected error occurred.");
    } finally {
      setLoading(false);
    }
  }

  function handleKeyDown(e) {
    if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
      handleSubmit();
    }
  }

  const columns =
    result?.results?.length > 0 ? Object.keys(result.results[0]) : [];
  const displayRows = result?.results?.slice(0, MAX_DISPLAY_ROWS) || [];

  return (
    <div style={{ padding: "24px", display: "flex", flexDirection: "column", gap: "20px" }}
         className="animate-fade-in">

      {/* ── Header ─────────────────────────────────────────── */}
      <div>
        <h2 style={{
          fontSize: "1.15rem", fontWeight: 700, color: "var(--text-primary)",
          display: "flex", alignItems: "center", gap: 8, margin: 0,
        }}>
          <span style={{
            background: "linear-gradient(135deg, var(--gold-500), var(--gold-400))",
            WebkitBackgroundClip: "text", WebkitTextFillColor: "transparent",
          }}>✦</span>
          Ask the Fabric
        </h2>
        <p style={{ fontSize: "0.82rem", color: "var(--text-muted)", marginTop: 4 }}>
          Query your audit data using natural language — powered by AI
        </p>
      </div>

      {/* ── Example question chips ─────────────────────────── */}
      <div style={{ display: "flex", flexWrap: "wrap", gap: "8px" }}>
        {EXAMPLE_QUESTIONS.map((q, i) => (
          <button
            key={i}
            id={`example-chip-${i}`}
            className="btn-ghost"
            style={{
              fontSize: "0.75rem", padding: "6px 12px", borderRadius: "20px",
              transition: "all 0.2s",
            }}
            onClick={() => setQuestion(q)}
          >
            {q}
          </button>
        ))}
      </div>

      {/* ── Textarea + Submit ──────────────────────────────── */}
      <div style={{ display: "flex", gap: "12px", alignItems: "flex-end" }}>
        <textarea
          id="nl-query-input"
          className="input-field"
          rows={4}
          placeholder='Ask anything about the sync data... e.g. Which UBIDs are stuck in the DLQ right now?'
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={handleKeyDown}
          style={{
            resize: "vertical", fontFamily: "var(--font-sans)",
            lineHeight: 1.5,
          }}
        />
        <button
          id="nl-query-submit"
          className="btn-primary"
          onClick={handleSubmit}
          disabled={loading || !question.trim()}
          style={{
            minWidth: 100, height: 44, display: "flex", alignItems: "center",
            justifyContent: "center", gap: 8, flexShrink: 0,
          }}
        >
          {loading ? (
            <span style={{
              width: 16, height: 16, border: "2px solid transparent",
              borderTopColor: "var(--navy-950)", borderRadius: "50%",
              display: "inline-block",
              animation: "spin 0.6s linear infinite",
            }} />
          ) : (
            <>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
                   stroke="currentColor" strokeWidth="2.5" strokeLinecap="round"
                   strokeLinejoin="round">
                <line x1="22" y1="2" x2="11" y2="13" />
                <polygon points="22 2 15 22 11 13 2 9 22 2" />
              </svg>
              Query
            </>
          )}
        </button>
      </div>

      {/* ── Error alert ────────────────────────────────────── */}
      {error && (
        <div id="nl-query-error" className="animate-fade-in" style={{
          padding: "14px 18px", borderRadius: 10,
          background: "rgba(248, 113, 113, 0.1)",
          border: "1px solid rgba(248, 113, 113, 0.25)",
          color: "var(--status-failed)", fontSize: "0.85rem",
          lineHeight: 1.5,
        }}>
          <strong style={{ display: "block", marginBottom: 4 }}>⚠ Query Error</strong>
          {error}
        </div>
      )}

      {/* ── Results ────────────────────────────────────────── */}
      {result && (
        <div className="animate-fade-in" style={{ display: "flex", flexDirection: "column", gap: 16 }}>

          {/* Natural-language summary */}
          <div id="nl-query-summary" style={{
            padding: "16px 20px", borderRadius: 10,
            background: "linear-gradient(135deg, rgba(96,165,250,0.12), rgba(129,140,248,0.08))",
            border: "1px solid rgba(96, 165, 250, 0.2)",
            fontSize: "0.9rem", lineHeight: 1.6,
            color: "var(--text-primary)",
          }}>
            <div style={{
              display: "flex", alignItems: "center", gap: 8,
              marginBottom: 8, fontSize: "0.75rem",
              fontWeight: 600, textTransform: "uppercase",
              letterSpacing: "0.06em", color: "var(--status-received)",
            }}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
                   stroke="currentColor" strokeWidth="2" strokeLinecap="round"
                   strokeLinejoin="round">
                <circle cx="12" cy="12" r="10" />
                <path d="M12 16v-4" />
                <path d="M12 8h.01" />
              </svg>
              AI Summary
            </div>
            {result.naturalSummary}
          </div>

          {/* Generated SQL */}
          <div>
            <div style={{
              fontSize: "0.72rem", fontWeight: 600, textTransform: "uppercase",
              letterSpacing: "0.06em", color: "var(--text-muted)", marginBottom: 6,
            }}>
              Generated SQL
            </div>
            <pre id="nl-query-sql" style={{
              padding: "14px 18px", borderRadius: 10,
              background: "var(--navy-800)", border: "1px solid var(--glass-border)",
              fontSize: "0.8rem", lineHeight: 1.6, overflowX: "auto",
              color: "var(--text-secondary)", margin: 0,
              fontFamily: "var(--font-mono)",
            }}>
              <code>{result.generatedSql}</code>
            </pre>
          </div>

          {/* Results table */}
          {columns.length > 0 && (
            <div>
              <div style={{
                display: "flex", justifyContent: "space-between",
                alignItems: "center", marginBottom: 8,
              }}>
                <span style={{
                  fontSize: "0.72rem", fontWeight: 600, textTransform: "uppercase",
                  letterSpacing: "0.06em", color: "var(--text-muted)",
                }}>
                  Query Results
                </span>
                <span style={{
                  fontSize: "0.75rem", color: "var(--text-muted)",
                }}>
                  {result.rowCount > MAX_DISPLAY_ROWS
                    ? `Showing ${MAX_DISPLAY_ROWS} of ${result.rowCount} rows`
                    : `${result.rowCount} row${result.rowCount !== 1 ? "s" : ""}`}
                </span>
              </div>

              <div style={{
                borderRadius: 10, overflow: "hidden",
                border: "1px solid var(--glass-border)",
              }}>
                <div style={{ overflowX: "auto", maxHeight: 420 }}>
                  <table className="data-table" id="nl-query-results-table">
                    <thead>
                      <tr>
                        {columns.map((col) => (
                          <th key={col}>{col}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {displayRows.map((row, ri) => (
                        <tr key={ri}>
                          {columns.map((col) => (
                            <td key={col} style={{
                              maxWidth: 260, overflow: "hidden",
                              textOverflow: "ellipsis", whiteSpace: "nowrap",
                            }}>
                              {row[col] === null
                                ? <span style={{ color: "var(--navy-500)", fontStyle: "italic" }}>null</span>
                                : typeof row[col] === "object"
                                  ? JSON.stringify(row[col])
                                  : String(row[col])}
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )}

          {/* Metadata footer */}
          <div style={{
            fontSize: "0.72rem", color: "var(--navy-500)",
            textAlign: "right", paddingTop: 4,
          }}>
            Computed at {new Date(result.computedAt).toLocaleString()}
          </div>
        </div>
      )}

      {/* ── Spinner keyframes (inline) ─────────────────────── */}
      <style>{`
        @keyframes spin {
          to { transform: rotate(360deg); }
        }
      `}</style>
    </div>
  );
}
