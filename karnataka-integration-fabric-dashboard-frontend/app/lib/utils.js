/** SWR fetcher — throws on non-OK responses so SWR can handle errors. */
export async function fetcher(url) {
  const res = await fetch(url);
  if (!res.ok) {
    const err = new Error("Fetch failed");
    err.status = res.status;
    try { err.info = await res.json(); } catch { err.info = null; }
    throw err;
  }
  return res.json();
}

/** Formats an ISO timestamp to a short local string. */
export function fmtTime(iso) {
  if (!iso) return "—";
  try {
    const d = new Date(iso);
    return d.toLocaleTimeString("en-IN", {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hour12: false,
    });
  } catch {
    return iso;
  }
}

/** Formats an ISO timestamp to a date+time string. */
export function fmtDateTime(iso) {
  if (!iso) return "—";
  try {
    const d = new Date(iso);
    return d.toLocaleString("en-IN", {
      day: "2-digit",
      month: "short",
      year: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hour12: false,
    });
  } catch {
    return iso;
  }
}

/** Relative time (e.g. "3m ago") */
export function timeAgo(iso) {
  if (!iso) return "—";
  const diff = Date.now() - new Date(iso).getTime();
  const secs = Math.floor(diff / 1000);
  if (secs < 60) return `${secs}s ago`;
  const mins = Math.floor(secs / 60);
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return `${Math.floor(hrs / 24)}d ago`;
}

/** Truncates a UUID for display */
export function shortId(id) {
  if (!id) return "—";
  return id.substring(0, 8);
}
