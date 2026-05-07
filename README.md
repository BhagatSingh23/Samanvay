<p align="center">
  <h1 align="center">🏛️ Karnataka Integration Fabric</h1>
  <p align="center"><strong>Bidirectional, UBID-Keyed Sync Layer for SWS & Legacy Department Systems</strong></p>
  <p align="center"><em>An event-driven middleware that keeps Karnataka's Single Window System and 40+ legacy department databases in sync — without modifying either side.</em></p>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-blue?logo=openjdk" />
  <img src="https://img.shields.io/badge/Spring_Boot-3.2.5-green?logo=springboot" />
  <img src="https://img.shields.io/badge/Kafka-7.5-black?logo=apachekafka" />
  <img src="https://img.shields.io/badge/PostgreSQL-16-blue?logo=postgresql" />
  <img src="https://img.shields.io/badge/Redis-7-red?logo=redis" />
  <img src="https://img.shields.io/badge/Next.js-16-black?logo=nextdotjs" />
  <img src="https://img.shields.io/badge/Docker-Compose-blue?logo=docker" />
</p>

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Solution Overview](#2-solution-overview)
3. [Architecture](#3-architecture)
4. [Key Components](#4-key-components)
5. [Tech Stack](#5-tech-stack)
6. [Demo Walkthrough](#6-demo-walkthrough-video-script)
7. [Edge Cases Handled](#7-edge-cases-handled)
8. [Conflict Resolution Policy](#8-conflict-resolution-policy)
9. [Audit Trail Schema](#9-audit-trail-schema)
10. [How to Run](#10-how-to-run)
11. [Round 2 Roadmap](#11-round-2-roadmap)
12. [Team & Submission](#12-team--submission)

---

## 1. Problem Statement

### The Split-Brain Problem

Karnataka's **Single Window System (SWS)** was built to be the single source of truth for business registrations. In practice, **40+ legacy department systems** (Factories, Shops & Establishments, Revenue, etc.) continue to operate independently. A business can submit the same service request — say, an address change — on *either* system. But **changes made on one system never propagate to the other**.

The result: **split-brain data inconsistency**. SWS says the business is at Address A; the Factories department says Address B. Neither is wrong — they simply never talked to each other.

### Why Big-Bang Migration Won't Work

- Each department system has **independent uptime SLAs**, tech stacks, and governance.
- Many legacy systems are **decades old** with undocumented schemas.
- A single cutover would require **simultaneous downtime** across 40+ departments — operationally impossible.

### Why UBID Is the Precondition

The **Unique Business Identifier (UBID)** is the *only* field that exists in every system. It is the universal join key that lets us correlate records across SWS and every department database without modifying their schemas.

---

## 2. Solution Overview

We built the **Karnataka Integration Fabric** — an event-driven, bidirectional synchronization middleware that sits *between* SWS and department systems as a transparent interoperability layer.

### How It Works (Two Directions)

| Direction | Flow |
|-----------|------|
| **SWS → Departments** | SWS fires a webhook → Fabric ingests → Kafka → Orchestrator translates schema → Outbox → Dispatch to each department API |
| **Department → SWS** | Fabric polls department API (or receives webhook) → Detects change → Kafka → Orchestrator translates → Outbox → Dispatch to SWS |

### Core Guarantees

- ✅ **Zero modifications** to SWS or department systems
- ✅ **Exactly-once delivery** via SHA-256 fingerprinting + pessimistic locks
- ✅ **Conflict detection** for simultaneous cross-system updates
- ✅ **Full audit trail** for every propagation decision
- ✅ **Resilient delivery** with exponential backoff and dead-letter queue

---

## 3. Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        KARNATAKA INTEGRATION FABRIC                      │
│                                                                          │
│  ┌─────────┐    ┌──────────────┐    ┌────────────────────────────────┐  │
│  │   SWS   │───▶│  REST API    │───▶│  Kafka: sws.inbound.events     │  │
│  │ (Source) │    │  Webhooks    │    └──────────────┬─────────────────┘  │
│  └─────────┘    └──────────────┘                   │                    │
│                                                     ▼                    │
│  ┌─────────┐    ┌──────────────┐    ┌──────────────────────────────┐    │
│  │  DEPT   │───▶│  Polling /   │───▶│  Kafka: dept.inbound.events  │    │
│  │ Systems │    │  Webhook /   │    └──────────────┬───────────────┘    │
│  │         │    │  Snapshot    │                   │                    │
│  └────▲────┘    └──────────────┘                   ▼                    │
│       │                              ┌─────────────────────────┐        │
│       │                              │  PROPAGATION            │        │
│       │                              │  ORCHESTRATOR           │        │
│       │                              │                         │        │
│       │                              │  ┌───────────────────┐  │        │
│       │                              │  │ Schema Translator  │  │        │
│       │                              │  │ (MapStruct + DB)   │  │        │
│       │                              │  └───────────────────┘  │        │
│       │                              │  ┌───────────────────┐  │        │
│       │                              │  │ Idempotency Guard  │  │        │
│       │                              │  │ (SHA-256 + PG Lock)│  │        │
│       │                              │  └───────────────────┘  │        │
│       │                              │  ┌───────────────────┐  │        │
│       │                              │  │ Conflict Detector  │  │        │
│       │                              │  │ (Redis ZSET)       │  │        │
│       │                              │  └───────────────────┘  │        │
│       │                              │  ┌───────────────────┐  │        │
│       │                              │  │ Audit Service      │  │        │
│       │                              │  │ (JDBC → PG)        │  │        │
│       │                              │  └───────────────────┘  │        │
│       │                              └──────────┬──────────────┘        │
│       │                                         ▼                       │
│       │                              ┌─────────────────────┐            │
│       │                              │ TRANSACTIONAL OUTBOX │            │
│       │                              │ (PostgreSQL table)   │            │
│       │                              └──────────┬──────────┘            │
│       │                                         ▼                       │
│       │                              ┌─────────────────────┐            │
│       │                              │   OUTBOX WORKER      │            │
│       │                              │   (5s polling, exp.  │            │
│       │                              │    backoff retries)   │            │
│       │                              └──────────┬──────────┘            │
│       │                                         ▼                       │
│       │                              ┌─────────────────────┐            │
│       │                              │ OUTBOUND DISPATCHER  │            │
│       │◀─────────────────────────────│ (WebClient +         │            │
│       │         HTTP POST            │  Circuit Breakers)    │            │
│       │                              └──────────┬──────────┘            │
│       │                                         ▼                       │
│       │                              ┌─────────────────────┐            │
│       │                              │  DEAD LETTER QUEUE   │            │
│       │                              │  (after 5 retries)   │            │
│       │                              └─────────────────────┘            │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐    │
│  │  NEXT.JS DASHBOARD: Live Feed │ UBID Trace │ Conflicts │ DLQ   │    │
│  └──────────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────────┘
```

### Module Structure

```
karnataka-integration-fabric/
├── fabric-core/          # Domain models, enums, Flyway migrations (V1–V11)
├── fabric-adapters/      # Dept registry, schema translation, polling, webhooks
├── fabric-propagation/   # Orchestrator, idempotency, conflict, outbox, dispatch
├── fabric-audit/         # JdbcAuditService → audit_records table
├── fabric-api/           # REST controllers, Spring Boot main, integration tests
├── stubs/                # WireMock stubs (SWS, Factories, Shop_Estab, Revenue)
├── docker-compose.yml    # Full-stack local environment
└── karnataka-integration-fabric-dashboard-frontend/  # Next.js dashboard
```

---

## 4. Key Components

### a) Change Detection Engine

| Mode | How It Works | When Used |
|------|-------------|-----------|
| **Webhook** | Department POSTs change event to `/api/v1/inbound/{deptId}` | Systems with event capability |
| **Polling** | `PollingAdapter` calls dept API on schedule with circuit breaker | Systems with query APIs |
| **Snapshot Diff** | `SnapshotDiffAdapter` hashes full dataset, detects deltas | Systems with no event/query support |

**Failure handling**: Resilience4j circuit breakers halt polling when a dept is unreachable.
**Idempotency**: Each detected change is fingerprinted before Kafka publish.

### b) Schema Translation Layer

The `SchemaTranslatorService` loads mapping rules from the `schema_mappings` table and applies field-level transforms via the `TransformEngine`:

- `UPPERCASE` / `LOWERCASE` — normalize casing
- `SPLIT_FULLNAME_TO_FIRST_LAST` — split "Rajesh Kumar" → `{first: "Rajesh", last: "Kumar"}`
- `CONCAT_ADDRESS_LINES` — merge address components

**Schema drift detection**: The `SchemaDriftDetector` compares expected fields against actual API responses and raises `SCHEMA_DRIFT_DETECTED` alerts.

### c) Bidirectional Event Router (`PropagationOrchestrator`)

The central orchestrator executes a 5-step pipeline for every event:

1. **Resolve targets** — SWS events → all departments; Dept events → SWS + other depts
2. **Translate** — canonical → department-specific schema
3. **Idempotency check** — SHA-256 fingerprint with `SELECT FOR UPDATE` lock
4. **Conflict detection** — Redis ZSET sliding window check
5. **Enqueue to outbox** — transactional insert for reliable delivery

### d) Conflict Detection & Resolution Engine

**Detection** (Redis ZSET):
```
Key:   ubid_window:{UBID}:{serviceType}
Score: ingestionTimestamp (epochMillis)
Value: eventId
TTL:   conflictWindowSeconds (default: 30s, demo: 60s)
```

If `ZRANGEBYSCORE` returns >1 event → conflict detected. The engine then:
1. Loads both payloads from `event_ledger`
2. Compares fields to identify the dispute (e.g., `registeredAddress.line1`)
3. Loads resolution policy from `conflict_policies` table
4. Applies policy and marks loser as `SUPERSEDED`

### e) Audit Trail Store

Every event lifecycle state is captured in `audit_records`:

`RECEIVED` → `TRANSLATED` → `DISPATCHED` → `CONFIRMED` (or `FAILED` → `RETRY_QUEUED` → `DLQ_PARKED`)

Side branches: `CONFLICT_DETECTED` → `CONFLICT_RESOLVED` | `SCHEMA_DRIFT_DETECTED`

### f) Idempotency & Retry Manager

**Idempotency**: SHA-256 of `(UBID + serviceType + sortedPayloadJSON + targetDeptId)`. Stored in `idempotency_fingerprints` with pessimistic locking. Stale locks (>5 min) are automatically reclaimed.

**Retry (Outbox Worker)**:

| Attempt | Backoff | Action |
|---------|---------|--------|
| 1 | 30 seconds | Retry |
| 2 | 2 minutes | Retry |
| 3 | 10 minutes | Retry |
| 4 | 1 hour | Retry |
| 5 | — | Move to Dead Letter Queue |

---

## 5. Tech Stack

| Technology | Version | Why This Choice |
|-----------|---------|----------------|
| **Java** | 21 | Virtual threads, pattern matching, text blocks for SQL |
| **Spring Boot** | 3.2.5 | Enterprise-grade DI, Kafka/JPA/WebFlux/Actuator integration |
| **PostgreSQL** | 16 | JSONB for flexible payloads, `SELECT FOR UPDATE SKIP LOCKED` for outbox |
| **Apache Kafka** | Confluent 7.5 | Decouples ingestion from processing; handles burst traffic |
| **Redis** | 7 Alpine | Sub-ms ZSET operations for conflict window detection |
| **Resilience4j** | 2.2 | Per-department circuit breakers prevent cascade failures |
| **Flyway** | 10.11 | Versioned, repeatable DB migrations (V1–V11) |
| **WireMock** | 3.5.4 | Mock SWS + 3 department APIs for sandbox demo |
| **Next.js + React** | 16 / 19 | Real-time dashboard with SWR polling |
| **Docker Compose** | 3.9 | One-command full-stack deployment |
| **MapStruct** | 1.5.5 | Compile-time schema mapping code generation |
| **Lombok** | 1.18 | Reduce boilerplate in domain models |

---

## 6. Demo Walkthrough (Video Script)

> This section serves as the **exact script** for the demo video. Each scenario maps to pre-seeded data.

### 🎬 Setup (30 seconds)

```bash
make demo    # Builds, starts all containers, seeds demo data
```

Open the **Dashboard** at `http://localhost:3000`. Show the 5 tabs: Live Event Feed, UBID Trace, Conflict Queue, Dead Letter Queue, System Health.

---

### 🎬 Scenario 1 — SWS → Department Propagation

**UBID**: `KA-2024-001` (Karnataka Steel Works Pvt Ltd)

> *"A business updates its registered address on the SWS portal from '42 MG Road' to '100 Electronic City'. Let's see how the Integration Fabric detects, translates, and propagates this change to the Factories and Shops & Establishments departments."*

**Step 1**: Fire the SWS webhook:
```bash
curl -X POST http://localhost:8080/api/v1/inbound/sws \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "'$(uuidgen)'",
    "ubid": "KA-2024-001",
    "sourceSystemId": "SWS",
    "serviceType": "ADDRESS_CHANGE",
    "payload": {
      "registeredAddress": {
        "line1": "100 Electronic City Phase 1",
        "city": "Bengaluru",
        "pincode": "560100",
        "state": "Karnataka"
      },
      "businessName": "Karnataka Steel Works Pvt Ltd"
    }
  }'
```

**Step 2**: Switch to the **UBID Trace** tab. Search for `KA-2024-001`. Show the audit trail:
- `RECEIVED` — event ingested from SWS
- `DISPATCHED` → FACTORIES — translated payload sent
- `DISPATCHED` → SHOP_ESTAB — translated payload sent
- `CONFIRMED` — both departments acknowledged

**Step 3**: Show the **translated payload** difference:
- SWS uses `registeredAddress.line1`
- Factories expects `addr_line_1` (UPPERCASE transform applied)
- Shop_Estab expects `shop_address_line1`

> *"The schema translation happened automatically using mapping rules stored in PostgreSQL. No code change needed to add a new department — just insert new mapping rules."*

---

### 🎬 Scenario 2 — Department → SWS Propagation

**UBID**: `KA-2024-002` (Mysore Silks International)

> *"This business updated its Authorized Signatory directly inside the Factories legacy portal. The Fabric's polling adapter detected this change and will propagate it back to SWS."*

**Step 1**: Show the pre-seeded event in the **Live Event Feed** tab — a `SIGNATORY_UPDATE` from `DEPT_FACTORIES`.

**Step 2**: Show the **UBID Trace** for `KA-2024-002`:
- `RECEIVED` from `DEPT_FACTORIES`
- Event normalized into canonical format
- Dispatched to SWS and SHOP_ESTAB (fan-out to other systems)

> *"The polling adapter uses a cursor stored in `poll_cursors` to avoid re-processing. If the Factories API goes down, the circuit breaker trips and retries later."*

---

### 🎬 Scenario 3 — Conflict Resolution

**UBID**: `KA-2024-003` (Deccan Enterprises)

> *"Two conflicting address updates arrived within 2 seconds of each other — one from SWS saying '88 Brigade Road', and one from Factories saying '99 Commercial Street'. Watch how the conflict detector catches this."*

**Step 1**: Open the **Conflict Queue** tab. Show the unresolved conflict:
- Event 1: SWS → `88 Brigade Road`
- Event 2: DEPT_FACTORIES → `99 Commercial Street`
- Field in dispute: `registeredAddress.line1`
- Policy: `SOURCE_PRIORITY`

**Step 2**: Resolve the conflict (SWS wins):
```bash
curl -X POST http://localhost:8080/api/v1/conflicts/{conflictId}/resolve \
  -H "Content-Type: application/json" \
  -d '{"winningEventId": "<sws-event-id>"}'
```

**Step 3**: Show the **audit trail** for `KA-2024-003`:
- `CONFLICT_DETECTED` — field: `registeredAddress.line1`, policy: `SOURCE_PRIORITY`
- `CONFLICT_RESOLVED` — winner: SWS event
- Factories event marked `SUPERSEDED`

> *"The conflict policy is configurable per service type and field. SWS is authoritative for address changes, but for factory-specific fields like inspection dates, we could configure LAST_WRITE_WINS or HOLD_FOR_REVIEW."*

---

### 🎬 Scenario 4 — Resilience & Dead Letter Queue

**UBID**: `KA-2024-004` (Mysore Silks International)

> *"The Factories API returned HTTP 503 five times in a row. Watch how the system retried with exponential backoff and eventually parked the event in the Dead Letter Queue."*

**Step 1**: Open the **Dead Letter Queue** tab. Show the parked event:
- UBID: `KA-2024-004`
- Target: `FACTORIES`
- Failure: `HTTP 503 Service Unavailable`
- Attempts: 5/5

**Step 2**: Show the **audit trail**: `RECEIVED` → `FAILED` → `DLQ_PARKED`

> *"An operator can manually retry from the DLQ once the Factories system is back online. The circuit breaker prevents further calls until the system recovers."*

---

## 7. Edge Cases Handled

| Edge Case | How We Handle It |
|-----------|-----------------|
| **Duplicate requests** | SHA-256 fingerprint + `SELECT FOR UPDATE` pessimistic lock in `idempotency_fingerprints` table. Stale locks (>5 min) auto-reclaimed. |
| **Department system down** | Transactional outbox with exponential backoff: 30s → 2m → 10m → 1h → DLQ. Per-department Resilience4j circuit breakers. |
| **Schema mismatch** | `SchemaTranslatorService` returns `TranslationResult` with warnings. Unmappable fields logged, pipeline continues. `SchemaDriftDetector` raises alerts. |
| **Simultaneous conflicting updates** | Redis ZSET sliding window (configurable: 30–60s). Policy lookup from `conflict_policies` table. Loser marked `SUPERSEDED`. |
| **No native event emission** | `PollingAdapter` (scheduled, cursor-based) and `SnapshotDiffAdapter` (hash-based delta detection). |
| **UBID not found in target** | Event parked in `pending_ubid_resolution` table for later reconciliation. |
| **Partial propagation failure** | Outbox tracks entries **per target**. Factories failing does not block Shop_Estab delivery. |
| **Network partition during write** | Outbox pattern: event persisted to DB *before* HTTP dispatch. Crash recovery picks up `PENDING` entries. |
| **Kafka consumer restart** | `auto-offset-reset: earliest` ensures no events are lost on consumer group rebalance. |

---

## 8. Conflict Resolution Policy

### Detection Mechanism

```
Window:  configurable (default 30s, demo 60s)
Trigger: 2+ events for same (UBID, serviceType) within window
Storage: Redis ZSET with TTL-based auto-cleanup
```

### Resolution Strategies

| Strategy | Behavior | Use Case |
|----------|----------|----------|
| `LAST_WRITE_WINS` | Latest ingestion timestamp wins | Low-risk fields (e.g., contact phone) |
| `SOURCE_PRIORITY` | SWS always wins over department systems | Authoritative fields (e.g., registered address) |
| `HOLD_FOR_REVIEW` | Both events paused in `CONFLICT_HELD` state | High-risk fields requiring human judgment |

### Audit Logging

Every conflict produces an immutable record in `conflict_records`:

```sql
conflict_id       UUID PRIMARY KEY
ubid              TEXT           -- the business entity
event1_id         UUID           -- first event in window
event2_id         UUID           -- second event in window
resolution_policy TEXT           -- LAST_WRITE_WINS | SOURCE_PRIORITY | HOLD_FOR_REVIEW
winning_event_id  UUID           -- NULL if HOLD_FOR_REVIEW
field_in_dispute  TEXT           -- e.g., "registeredAddress.line1"
resolved_at       TIMESTAMPTZ
```

---

## 9. Audit Trail Schema

### Database Table: `audit_records`

| Column | Type | Description |
|--------|------|-------------|
| `audit_id` | UUID | Primary key |
| `event_id` | UUID | The event being tracked |
| `ubid` | TEXT | Business identifier |
| `source_system` | TEXT | Origin (SWS, DEPT_FACTORIES, etc.) |
| `target_system` | TEXT | Destination (nullable for ingestion events) |
| `audit_event_type` | TEXT | RECEIVED, DISPATCHED, CONFIRMED, FAILED, CONFLICT_DETECTED, DLQ_PARKED |
| `ts` | TIMESTAMPTZ | When this audit entry was created |
| `before_state` | JSONB | State before the change |
| `after_state` | JSONB | State after the change / resolution details |

### Sample Entry — Conflict Resolution

```json
{
  "auditId": "8f7a4b2c-...",
  "eventId": "e99b11a2-...",
  "ubid": "KA-2024-003",
  "sourceSystem": "SWS",
  "targetSystem": null,
  "auditEventType": "CONFLICT_DETECTED",
  "ts": "2026-05-07T12:05:00Z",
  "beforeState": {
    "conflictingEventId": "a1b233c4-...",
    "fieldInDispute": "registeredAddress.line1"
  },
  "afterState": {
    "policy": "SOURCE_PRIORITY",
    "winningEventId": "e99b11a2-..."
  }
}
```

### Sample Entry — Successful Delivery

```json
{
  "auditId": "cc91a3f1-...",
  "eventId": "d44e7b89-...",
  "ubid": "KA-2024-001",
  "sourceSystem": "FABRIC",
  "targetSystem": "FACTORIES",
  "auditEventType": "CONFIRMED",
  "ts": "2026-05-07T12:03:15Z",
  "beforeState": null,
  "afterState": {
    "status": "DELIVERED",
    "attemptCount": 1
  }
}
```

---

## 10. How to Run

### Prerequisites

- Docker Desktop (with Docker Compose)
- Make (optional, for convenience commands)
- 8 GB+ RAM allocated to Docker

### One-Command Demo

```bash
# Clone the repository
git clone <repo-url>
cd karnataka-integration-fabric

# Start everything: Postgres, Redis, Kafka, WireMock stubs, Backend API, Next.js Dashboard
make demo
```

This single command:
1. Builds the Java multi-module project
2. Starts all infrastructure containers
3. Waits for health checks to pass
4. Seeds 50 synthetic businesses and 4 demo scenarios
5. Opens the dashboard at `http://localhost:3000`

### Access Points

| Service | URL | Purpose |
|---------|-----|---------|
| **Dashboard** | http://localhost:3000 | Real-time observability UI |
| **Fabric API** | http://localhost:8080 | REST endpoints |
| **Mock SWS** | http://localhost:8081 | WireMock stub for SWS |
| **Mock Factories** | http://localhost:8082 | WireMock stub |
| **Mock Shop_Estab** | http://localhost:8083 | WireMock stub |
| **Mock Revenue** | http://localhost:8084 | WireMock stub |

### Key API Endpoints

```bash
# Ingest SWS event
POST /api/v1/inbound/sws

# Ingest department webhook
POST /api/v1/inbound/{deptId}

# Dry-run translation (preview without dispatching)
POST /api/v1/translate/dry-run

# View events by UBID
GET  /api/v1/events?ubid=KA-2024-001

# View/manage schema mappings
GET  /api/v1/mappings
POST /api/v1/mappings

# View drift alerts
GET  /api/v1/drift-alerts

# Health check
GET  /api/v1/health

# Reset demo data
POST /api/v1/demo/reset
```

### Other Commands

```bash
make docker-down    # Tear down all containers and volumes
make demo-reset     # Reset demo data without restarting
make test           # Run full test suite (H2 in-memory, no Docker needed)
make build          # Build all modules (skip tests)
```

---

## 11. Round 2 Roadmap

| Feature | Description |
|---------|-------------|
| **UBID Registry Lookup** | Query a central registry to discover which departments a UBID is registered with (vs. current broadcast-to-all approach) |
| **Interactive Conflict Resolution UI** | Dashboard panel for admins to manually resolve `HOLD_FOR_REVIEW` conflicts |
| **DLQ Management Endpoints** | `GET /api/v1/dlq`, `POST /api/v1/dlq/{id}/retry`, `POST /api/v1/dlq/{id}/resolve` |
| **Batch Event Ingestion** | `POST /api/v1/inbound/sws/batch` for high-throughput initial data loads |
| **Revenue Department Adapter** | Add mapping rules for the Revenue system (config exists, mappings TBD) |
| **Prometheus Metrics** | Micrometer counters for events ingested/propagated/conflicted/DLQ'd via `/actuator/prometheus` |
| **Gatling Load Tests** | Validate throughput limits on Kafka consumers and outbox processing |
| **Outbox Monitoring Dashboard** | Real-time visualization of outbox queue depth and processing latency |

---

## 12. Team & Submission

| | |
|---|---|
| **Hackathon** | Two-Way Interoperability — Karnataka Commerce & Industry |
| **Theme** | SWS ↔ Legacy Department System Interoperability |
| **Team Name** | Samanvay |
| **Project** | Karnataka Integration Fabric |

---

## Database Schema (12 Tables)

| Table | Purpose |
|-------|---------|
| `event_ledger` | Master event store (UBID, payload as JSONB, status) |
| `audit_records` | Immutable audit trail for every state transition |
| `conflict_records` | Detected conflicts with resolution details |
| `conflict_policies` | Configurable per-service-type resolution policies |
| `conflict_hold_queue` | Events held for manual review |
| `propagation_outbox` | Transactional outbox for reliable delivery |
| `dead_letter_queue` | Failed events after retry exhaustion |
| `idempotency_fingerprints` | SHA-256 locks for exactly-once processing |
| `schema_mappings` | Dynamic field mapping rules per department |
| `drift_alerts` | Schema drift detection results |
| `poll_cursors` | Polling watermarks per department |
| `snapshot_hashes` | Hash-based change detection for snapshot diff |

---

<p align="center">
  <strong>Built with ❤️ for Karnataka's digital governance infrastructure</strong>
</p>
