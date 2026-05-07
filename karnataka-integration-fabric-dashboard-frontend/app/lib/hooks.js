"use client";
import useSWR from "swr";
import { fetcher } from "./utils";
import * as mock from "./mock-data";

/**
 * Custom hooks for all dashboard data fetching.
 * Falls back to mock data when the backend is unreachable.
 */

export function useEvents(limit = 50) {
  const { data, error, isLoading } = useSWR(
    `/api/v1/events?limit=${limit}`,
    fetcher,
    { refreshInterval: 3000, dedupingInterval: 2000, revalidateOnFocus: false }
  );
  // fallback to mock when backend is down
  const events = data ?? (error ? mock.mockEvents(limit) : undefined);
  return { events, error, isLoading: isLoading && !events };
}

export function useAuditByUbid(ubid, from, to) {
  const params = new URLSearchParams();
  if (from) params.set("from", from);
  if (to) params.set("to", to);
  const qs = params.toString();
  const url = ubid ? `/api/v1/audit/ubid/${ubid}${qs ? "?" + qs : ""}` : null;

  const { data, error, isLoading } = useSWR(url, fetcher, {
    revalidateOnFocus: false,
  });

  const result = data ?? (error && ubid ? mock.mockAuditTrail(ubid) : undefined);
  return { data: result, error, isLoading: isLoading && !result };
}

export function useConflicts(resolved, page = 0, size = 20) {
  const params = new URLSearchParams();
  if (resolved !== undefined && resolved !== null) params.set("resolved", resolved);
  params.set("page", page);
  params.set("size", size);

  const { data, error, isLoading, mutate } = useSWR(
    `/api/v1/conflicts?${params}`,
    fetcher,
    { revalidateOnFocus: false }
  );

  const result = data ?? (error ? mock.mockConflicts() : undefined);
  return { data: result, error, isLoading: isLoading && !result, mutate };
}

export function useDLQ() {
  const { data, error, isLoading, mutate } = useSWR(
    `/api/v1/dlq`,
    fetcher,
    { revalidateOnFocus: false }
  );
  const result = data ?? (error ? mock.mockDLQ() : undefined);
  return { data: result, error, isLoading: isLoading && !result, mutate };
}

export function useDepartments() {
  const { data, error, isLoading } = useSWR(
    `/api/v1/departments`,
    fetcher,
    { refreshInterval: 15000, revalidateOnFocus: false }
  );
  const result = data ?? (error ? mock.mockDepartments() : undefined);
  return { data: result, error, isLoading: isLoading && !result };
}

export function useDriftAlerts() {
  const { data, error, isLoading } = useSWR(
    `/api/v1/drift-alerts`,
    fetcher,
    { refreshInterval: 15000, revalidateOnFocus: false }
  );
  return { data: data ?? (error ? [] : undefined), error, isLoading };
}

export function useBannerStats() {
  const { data, error, isLoading } = useSWR(
    `/api/v1/stats/today`,
    fetcher,
    { refreshInterval: 10000, revalidateOnFocus: false }
  );
  const result = data ?? (error ? mock.mockBannerStats() : undefined);
  return { data: result, error, isLoading: isLoading && !result };
}
