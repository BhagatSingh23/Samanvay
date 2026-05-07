/**
 * Mock data generator for development without a running backend.
 * Used as fallback data when API calls fail.
 */

const UBIDS = [
  "KA-2024-0001", "KA-2024-0042", "KA-2024-0108",
  "KA-2024-0217", "KA-2024-0339", "KA-2024-0455",
];
const SOURCES = ["SWS", "DEPT_FACTORIES", "DEPT_SHOP_ESTAB", "DEPT_REVENUE"];
const SERVICE_TYPES = [
  "ADDRESS_CHANGE", "SIGNATORY_UPDATE", "OWNERSHIP_CHANGE",
  "LICENSE_RENEWAL", "TAX_FILING",
];
const STATUSES = [
  "DELIVERED", "DELIVERED", "DELIVERED",
  "PENDING", "FAILED", "CONFLICT_HELD", "SUPERSEDED", "RECEIVED",
];
const TARGETS = ["FACTORIES", "SHOP_ESTAB", "REVENUE", "GST", "LABOUR"];

function pick(arr) { return arr[Math.floor(Math.random() * arr.length)]; }
function uuid() { return crypto.randomUUID ? crypto.randomUUID() : Math.random().toString(36).slice(2); }

export function mockEvents(count = 50) {
  const now = Date.now();
  return Array.from({ length: count }, (_, i) => ({
    eventId: uuid(),
    ubid: pick(UBIDS),
    sourceSystemId: pick(SOURCES),
    serviceType: pick(SERVICE_TYPES),
    status: pick(STATUSES),
    ingestionTimestamp: new Date(now - i * 3200).toISOString(),
    targetSystems: [pick(TARGETS), pick(TARGETS)].filter((v, j, a) => a.indexOf(v) === j),
  }));
}

export function mockAuditTrail(ubid) {
  const base = Date.now() - 86400000;
  const eventId1 = uuid();
  const eventId2 = uuid();
  return {
    ubid,
    events: [
      { auditId: uuid(), eventId: eventId1, sourceSystem: "SWS", targetSystem: null, auditEventType: "RECEIVED", timestamp: new Date(base).toISOString(), conflictPolicy: null, supersededBy: null, beforeState: null, afterState: null },
      { auditId: uuid(), eventId: eventId1, sourceSystem: "SWS", targetSystem: null, auditEventType: "TRANSLATED", timestamp: new Date(base + 1200).toISOString(), conflictPolicy: null, supersededBy: null, beforeState: { "registeredAddress.line1": "Old Address" }, afterState: { "addr_line_1": "OLD ADDRESS" } },
      { auditId: uuid(), eventId: eventId2, sourceSystem: "DEPT_FACTORIES", targetSystem: null, auditEventType: "RECEIVED", timestamp: new Date(base + 5000).toISOString(), conflictPolicy: null, supersededBy: null, beforeState: null, afterState: null },
      { auditId: uuid(), eventId: eventId1, sourceSystem: "SWS", targetSystem: null, auditEventType: "CONFLICT_DETECTED", timestamp: new Date(base + 6000).toISOString(), conflictPolicy: "SOURCE_PRIORITY", supersededBy: null, beforeState: { conflictingEventId: eventId2 }, afterState: { policy: "SOURCE_PRIORITY", fieldInDispute: "registeredAddress.line1" } },
      { auditId: uuid(), eventId: eventId1, sourceSystem: "SWS", targetSystem: null, auditEventType: "CONFLICT_RESOLVED", timestamp: new Date(base + 7000).toISOString(), conflictPolicy: "SOURCE_PRIORITY", supersededBy: null, beforeState: null, afterState: { winningEventId: eventId1 } },
      { auditId: uuid(), eventId: eventId1, sourceSystem: "SWS", targetSystem: "FACTORIES", auditEventType: "DISPATCHED", timestamp: new Date(base + 10000).toISOString(), conflictPolicy: null, supersededBy: null, beforeState: null, afterState: { translatedPayload: { addr_line_1: "MG ROAD" } } },
      { auditId: uuid(), eventId: eventId1, sourceSystem: "SWS", targetSystem: "FACTORIES", auditEventType: "CONFIRMED", timestamp: new Date(base + 15000).toISOString(), conflictPolicy: null, supersededBy: null, beforeState: null, afterState: null },
    ],
  };
}

export function mockConflicts() {
  return {
    content: [
      {
        conflictId: uuid(), ubid: "KA-2024-0042", event1Id: uuid(), event2Id: uuid(),
        resolutionPolicy: "HOLD_FOR_REVIEW", winningEventId: null, resolvedAt: null,
        fieldInDispute: "ownerName",
        event1Summary: { eventId: uuid(), sourceSystemId: "SWS", serviceType: "OWNERSHIP_CHANGE", status: "CONFLICT_HELD", ingestionTimestamp: new Date(Date.now() - 3600000).toISOString() },
        event2Summary: { eventId: uuid(), sourceSystemId: "DEPT_REVENUE", serviceType: "OWNERSHIP_CHANGE", status: "CONFLICT_HELD", ingestionTimestamp: new Date(Date.now() - 3500000).toISOString() },
      },
      {
        conflictId: uuid(), ubid: "KA-2024-0108", event1Id: uuid(), event2Id: uuid(),
        resolutionPolicy: "HOLD_FOR_REVIEW", winningEventId: null, resolvedAt: null,
        fieldInDispute: "registeredAddress.pincode",
        event1Summary: { eventId: uuid(), sourceSystemId: "DEPT_FACTORIES", serviceType: "ADDRESS_CHANGE", status: "CONFLICT_HELD", ingestionTimestamp: new Date(Date.now() - 7200000).toISOString() },
        event2Summary: { eventId: uuid(), sourceSystemId: "DEPT_SHOP_ESTAB", serviceType: "ADDRESS_CHANGE", status: "CONFLICT_HELD", ingestionTimestamp: new Date(Date.now() - 7100000).toISOString() },
      },
    ],
    page: 0, size: 20, totalElements: 2,
  };
}

export function mockDLQ() {
  return [
    { dlqId: uuid(), eventId: uuid(), ubid: "KA-2024-0339", targetSystemId: "SHOP_ESTAB", failureReason: "Connection timeout after 3 retries — target API returned HTTP 503", parkedAt: new Date(Date.now() - 1800000).toISOString(), resolved: false },
    { dlqId: uuid(), eventId: uuid(), ubid: "KA-2024-0455", targetSystemId: "REVENUE", failureReason: "Schema validation failed: missing required field 'tax_id'", parkedAt: new Date(Date.now() - 7200000).toISOString(), resolved: false },
    { dlqId: uuid(), eventId: uuid(), ubid: "KA-2024-0042", targetSystemId: "FACTORIES", failureReason: "Authentication token expired — 401 Unauthorized", parkedAt: new Date(Date.now() - 14400000).toISOString(), resolved: false },
  ];
}

export function mockDepartments() {
  return [
    { deptId: "FACTORIES", name: "Department of Factories & Boilers", adapterMode: "WEBHOOK", lastPollTime: null, ubidCoverage: 94, circuitBreakerStatus: "CLOSED", driftAlerts: 0 },
    { deptId: "SHOP_ESTAB", name: "Shops & Establishments", adapterMode: "POLLING", lastPollTime: new Date(Date.now() - 45000).toISOString(), ubidCoverage: 87, circuitBreakerStatus: "CLOSED", driftAlerts: 1 },
    { deptId: "REVENUE", name: "Department of Revenue", adapterMode: "POLLING", lastPollTime: new Date(Date.now() - 120000).toISOString(), ubidCoverage: 72, circuitBreakerStatus: "HALF_OPEN", driftAlerts: 3 },
    { deptId: "GST", name: "GST Network", adapterMode: "SNAPSHOT", lastPollTime: new Date(Date.now() - 86400000).toISOString(), ubidCoverage: 91, circuitBreakerStatus: "CLOSED", driftAlerts: 0 },
    { deptId: "LABOUR", name: "Labour Department", adapterMode: "WEBHOOK", lastPollTime: null, ubidCoverage: 65, circuitBreakerStatus: "OPEN", driftAlerts: 5 },
  ];
}

export function mockBannerStats() {
  return {
    eventsToday: 1247,
    conflictsToday: 3,
    dlqCount: 3,
  };
}
