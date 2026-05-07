<p align="center">
  <img src="https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" />
  <img src="https://img.shields.io/badge/Spring_Boot-3.2.5-6DB33F?style=for-the-badge&logo=springboot&logoColor=white" />
  <img src="https://img.shields.io/badge/PostgreSQL-16-4169E1?style=for-the-badge&logo=postgresql&logoColor=white" />
  <img src="https://img.shields.io/badge/Apache_Kafka-7.5-231F20?style=for-the-badge&logo=apachekafka&logoColor=white" />
  <img src="https://img.shields.io/badge/Redis-7-DC382D?style=for-the-badge&logo=redis&logoColor=white" />
  <img src="https://img.shields.io/badge/Next.js-16-000000?style=for-the-badge&logo=next.js&logoColor=white" />
</p>

# Karnataka Integration Fabric

### Bidirectional, UBID-Keyed Sync Layer for SWS and Legacy Department Systems

> **A zero-modification interoperability layer that eliminates split-brain data inconsistency between Karnataka's Single Window System (SWS) and 40+ legacy department systems — using the Unique Business Identifier (UBID) as the universal join key.**

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Solution Overview](#2-solution-overview)
3. [Architecture](#3-architecture)
4. [Key Components & How They Work](#4-key-components--how-they-work)
5. [Tech Stack](#5-tech-stack)
6. [Demo Walkthrough](#6-demo-walkthrough-video-script)
7. [Edge Cases Handled](#7-edge-cases-handled)
8. [Conflict Resolution Policy](#8-conflict-resolution-policy)
9. [Audit Trail Schema](#9-audit-trail-schema)
10. [How to Run](#10-how-to-run-the-demo)
11. [What We Would Build Next](#11-what-we-would-build-next)
12. [Team & Submission](#12-team--submission-info)

---

## 1. Problem Statement

### The Split-Brain Problem

Karnataka's Single Window System (SWS) and 40+ legacy department systems (Factories, Shop & Establishments, Revenue, etc.) **run in parallel**. A business registered with UBID `KA-2024-001` can submit an address change in SWS, but the Factories department still shows the old address. Conversely, a signatory update made directly in the Factories portal never reaches SWS.

The result: **split-brain data inconsistency** — the same business has contradictory records across systems, leading to compliance failures, delayed approvals, and manual reconciliation by thousands of clerical staff.

### Why Big-Bang Migration Is Not Viable

- **40+ department systems** are independently developed, maintained, and funded — no single authority can mandate a schema change.
- Many systems are **10–15 year old monoliths** with no REST APIs; some only expose flat-file exports.
- A phased migration is underway but will take **3–5 years**. Businesses cannot wait.

### Why UBID Is the Precondition

The **Unique Business Identifier (UBID)** is the only field guaranteed to exist in both SWS and every legacy department system. It is the **sole join key** that allows us to correlate a record in SWS (`KA-2024-001`) with the same business entity in each department's database. Without UBID, cross-system correlation is impossible.

---

## 2. Solution Overview

We built the **Karnataka Integration Fabric** — an event-driven middleware that sits between SWS and every department system **without modifying either side**. It acts as a transparent synchronization layer:

```
SWS  ──webhook──▶  FABRIC  ──API──▶  Dept Systems
SWS  ◀──API──────  FABRIC  ◀──poll──  Dept Systems
```

### The Two Propagation Directions

| Direction | Trigger | Example |
|---|---|---|
| **SWS → Department** | SWS fires a webhook when a business updates its profile | Address change for UBID `KA-2024-001` in SWS → automatically propagated to FACTORIES and SHOP_ESTAB |
| **Department → SWS** | Fabric polls department APIs for changes (or receives webhooks if available) | Signatory update made directly in the Factories portal → detected by polling → propagated back to SWS |

### How It Works (The 6-Step Pipeline)

1. **Detect** — Change is detected via webhook, polling, or snapshot-diff
2. **Normalise** — Raw department payload is normalised into a canonical domain model keyed by UBID
3. **Translate** — Canonical payload is translated to each target system's schema using versioned, database-driven field mappings with value transforms
4. **Deduplicate** — SHA-256 fingerprint-based idempotency guard ensures exactly-once delivery
5. **Conflict Check** — Redis ZSET sliding window detects concurrent mutations of the same UBID; resolution policy is applied (LAST_WRITE_WINS, SOURCE_PRIORITY, or HOLD_FOR_REVIEW)
6. **Dispatch** — Translated payload is written to a transactional outbox table, then reliably dispatched with exponential backoff (5 attempts, 2s → 32s)

---

## 3. Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                     KARNATAKA INTEGRATION FABRIC                     │
│                                                                      │
│  ┌─────────────┐    ┌──────────────┐    ┌──────────────────────┐    │
│  │  Inbound     │    │  Propagation │    │  Outbound Dispatch   │    │
│  │  Adapters    │───▶│  Orchestrator│───▶│  (Outbox + Worker)   │    │
│  │             │    │              │    │                      │    │
│  │ • Webhook   │    │ • Translate  │    │ • SELECT FOR UPDATE  │    │
│  │ • Polling   │    │ • Idempotency│    │   SKIP LOCKED        │    │
│  │ • Snapshot  │    │ • Conflict   │    │ • Exponential Backoff│    │
│  │   Diff      │    │ • Outbox     │    │ • DLQ after 5 fails  │    │
│  └─────────────┘    └──────────────┘    └──────────────────────┘    │
│         │                  │                       │                 │
│         ▼                  ▼                       ▼                 │
│  ┌─────────────┐    ┌──────────────┐    ┌──────────────────────┐    │
│  │  Schema      │    │  Conflict    │    │  Audit Trail         │    │
│  │  Translation │    │  Resolution  │    │  (Immutable Ledger)  │    │
│  │  Engine      │    │  Engine      │    │                      │    │
│  │             │    │              │    │ • RECEIVED            │    │
│  │ • Versioned │    │ • Redis ZSET │    │ • DISPATCHED          │    │
│  │   Mappings  │    │ • Policies   │    │ • CONFIRMED           │    │
│  │ • Transforms│    │ • SLA Engine │    │ • SUPERSEDED          │    │
│  │ • Drift     │    │ • Escalation │    │ • FAILED              │    │
│  │   Detection │    │ • Auto-Resolve│   │ • CONFLICT_RESOLVED   │    │
│  └─────────────┘    └──────────────┘    └──────────────────────┘    │
│         │                  │                       │                 │
│  ┌──────┴──────────────────┴───────────────────────┴────────────┐   │
│  │                    PostgreSQL 16 + Redis 7                    │   │
│  │  event_ledger │ audit_records │ propagation_outbox │ DLQ      │   │
│  │  conflict_records │ schema_mappings │ idempotency_fingerprints│  │
│  └──────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
        ▲                                              │
        │                                              ▼
   ┌────┴────┐    ┌──────────┐    ┌──────────┐    ┌────┴────┐
   │   SWS   │    │ Factories│    │ Shop &   │    │ Revenue │
   │ (Single │    │ Dept     │    │ Estab    │    │ Dept    │
   │ Window) │    │ System   │    │ System   │    │ System  │
   └─────────┘    └──────────┘    └──────────┘    └─────────┘
```

### Interactive Dashboard

A **Next.js 16 / React 19** real-time dashboard provides full operational visibility:

| Tab | Purpose |
|---|---|
| **Live Event Feed** | Real-time scrolling feed of all events across the fabric |
| **UBID Trace** | Deep-dive timeline of every touchpoint for a specific business |
| **Conflict Queue** | HOLD_FOR_REVIEW conflicts with SLA countdown + one-click resolution |
| **Dead Letter Queue** | Failed events with payload inspection + one-click redispatch |
| **System Health** | Kafka lag, DB latency, Redis connectivity |
| **Ask the Fabric** | Natural language queries → instant SQL → audit data (AI-powered) |
| **Dept Health** | Per-department health grades (A–D) with 7-day sparkline trends |

---

## 4. Key Components & How They Work

### a) Change Detection Engine

The fabric supports three ingestion modes per department, configured in JSON:

| Mode | How It Works | Used When |
|---|---|---|
| **WEBHOOK** | Department fires HTTP POST to `/api/v1/inbound/{dept}` with payload | System supports event emission |
| **POLLING** | Fabric polls `GET /changes?since={cursor}` every N seconds | System has a changes endpoint but no push |
| **SNAPSHOT_DIFF** | Fabric fetches a full snapshot, hashes each record, and detects delta | System has no event capability at all |

**Failure Handling:** Polling cursors are persisted in `poll_cursors` table. If a poll fails, the cursor is not advanced — the next poll retries from the same position. Snapshot hashes are stored in `snapshot_hashes` for diff computation.

**Idempotency:** Every ingested event is fingerprinted with SHA-256 over `(ubid, serviceType, payload, targetDept)`. Duplicate payloads are silently skipped.

### b) Schema Translation Layer

Each department has its own field names and value formats. The fabric uses **database-driven, versioned field mappings** stored in the `schema_mappings` table:

```json
{
  "fields": [
    { "canonicalField": "registeredAddress.line1", "targetField": "addr_line_1",  "transform": "UPPERCASE" },
    { "canonicalField": "registeredAddress.pincode", "targetField": "postal_code", "transform": "NONE" },
    { "canonicalField": "contactPerson", "targetField": "contact_person", "transform": "SPLIT_FULLNAME_TO_FIRST_LAST" }
  ]
}
```

**Supported Transforms:** `NONE`, `UPPERCASE`, `LOWERCASE`, `DATE_ISO_TO_EPOCH`, `DATE_EPOCH_TO_ISO`, `SPLIT_FULLNAME_TO_FIRST_LAST`, `CONCAT_ADDRESS_LINES`

**Bidirectional:** The same mapping powers both forward (canonical → department) and reverse (department → canonical) translation. Mappings are cached for 60s via Caffeine. A **Drift Detection Engine** monitors for schema changes and raises alerts when a department's payload no longer matches the mapping.

### c) Bidirectional Event Router (PropagationOrchestrator)

The central nervous system of the fabric. For each incoming event:

1. Resolves target departments by excluding the source system
2. Translates the payload for each target using versioned mappings
3. Acquires an idempotency lock
4. Runs conflict detection against the Redis sliding window
5. If no conflict (or conflict resolved in favour of this event): inserts into `propagation_outbox`
6. The `OutboxWorker` picks up pending rows using `SELECT FOR UPDATE SKIP LOCKED` and dispatches via `WebClient` with Resilience4j circuit breakers

### d) Conflict Detection & Resolution Engine

**Detection:** A Redis Sorted Set (ZSET) keyed by UBID stores event timestamps. When a new event arrives, the engine queries the window (default: 60 seconds) for any concurrent mutations from a *different* source system.

**Resolution Policies** (configurable per `service_type × dept_id × field_name`):

| Policy | Behaviour |
|---|---|
| `LAST_WRITE_WINS` | Latest `ingestion_timestamp` wins; loser is marked `SUPERSEDED` |
| `SOURCE_PRIORITY` | Configured priority source always wins (e.g., SWS for address changes) |
| `HOLD_FOR_REVIEW` | Both events are parked; operator resolves manually via dashboard |

**SLA Escalation Engine:** HOLD_FOR_REVIEW conflicts are tracked with a configurable SLA (default: 4 hours). Level 1 breach sends a dashboard notification + optional webhook. Level 2 breach auto-resolves using the fallback policy. Everything is logged in the immutable audit trail.

### e) Audit Trail Store

Every state transition produces an immutable `audit_records` row:

| Field | Description |
|---|---|
| `event_id` | Which event triggered this audit entry |
| `ubid` | The business affected |
| `source_system` | Where the change originated |
| `target_system` | Where it was propagated to |
| `audit_event_type` | `RECEIVED`, `DISPATCHED`, `CONFIRMED`, `SUPERSEDED`, `CONFLICT_RESOLVED`, `FAILED` |
| `conflict_policy` | If a conflict was resolved, which policy was applied |
| `superseded_by` | If this event lost a conflict, which event won |
| `before_state` / `after_state` | Full JSONB snapshots for forensic analysis |

**Natural Language Querying:** Operators can ask questions in plain English (e.g., *"Show me all conflicts for FACTORIES last week"*) and the AI-powered `NaturalLanguageQueryService` translates the query to SQL, executes it safely, and returns structured results.

### f) Idempotency & Retry Manager

| Mechanism | Implementation |
|---|---|
| **Deduplication** | SHA-256 fingerprint over `(ubid, serviceType, payload, targetDept)` stored in `idempotency_fingerprints` table. INSERT ON CONFLICT + SELECT FOR UPDATE provides race-safe locking. |
| **Outbox Pattern** | Events are written to `propagation_outbox` inside the same DB transaction as the ledger update. `OutboxWorker` polls every 2s with `SELECT FOR UPDATE SKIP LOCKED`. |
| **Exponential Backoff** | Failed dispatches retry 5 times: 2s → 4s → 8s → 16s → 32s. |
| **Dead Letter Queue** | After 5 failures, the event is parked in `dead_letter_queue` with the failure reason. Operators can inspect and manually redispatch. |
| **Circuit Breaker** | Resilience4j circuit breaker wraps all outbound WebClient calls to prevent cascade failures when a department API is down. |

---

## 5. Tech Stack

| Technology | Version | Why We Chose It |
|---|---|---|
| **Java 21** | LTS | Virtual threads, pattern matching, text blocks — ideal for high-throughput event processing |
| **Spring Boot** | 3.2.5 | Production-grade framework with first-class support for Kafka, JDBC, WebFlux, and scheduling |
| **PostgreSQL** | 16 | JSONB for schema-flexible payloads, `gen_random_uuid()` for primary keys, `SELECT FOR UPDATE SKIP LOCKED` for concurrent outbox workers |
| **Apache Kafka** | Confluent 7.5 | Durable, ordered event backbone for inter-module communication; 6-partition topics for parallelism |
| **Redis** | 7 | ZSET-based conflict detection windows (O(log N) range queries); health score caching with TTL |
| **Flyway** | 10.11 | 14 versioned migrations ensure reproducible schema across dev/staging/prod |
| **Resilience4j** | 2.2 | Circuit breakers + retry decorators for all outbound HTTP calls |
| **MapStruct** | 1.5.5 | Compile-time mapping between DTOs — zero reflection overhead |
| **Next.js** | 16 | Server-side rendering for SEO + React 19 for interactive dashboard components |
| **Docker Compose** | — | One-command local deployment of 9 containers (Postgres, Redis, ZooKeeper, Kafka, API, Dashboard, 3× WireMock stubs) |
| **WireMock** | 3.5 | Simulates SWS, Factories, and Shop & Establishments APIs for reproducible demos |

---

## 6. Demo Walkthrough (Video Script)

### Scene 1 — SWS → Department Propagation (Happy Path)

> *"A business with UBID `KA-2024-001` updates its registered address in SWS."*

1. **Trigger:** Send `POST /api/v1/inbound/sws` with an ADDRESS_CHANGE payload containing the new address for UBID `KA-2024-001`.
2. **Live Feed:** Watch the event appear as `RECEIVED` in the Live Event Feed tab (green badge).
3. **Orchestration:** The PropagationOrchestrator resolves two targets: FACTORIES and SHOP_ESTAB. It translates the canonical payload using versioned mappings — `registeredAddress.line1` becomes `addr_line_1` (UPPERCASE) for Factories, and `shop_addr_1` for Shop & Estab.
4. **Idempotency:** SHA-256 fingerprint is computed; first time → lock acquired.
5. **Dispatch:** Two outbox rows are created. The OutboxWorker dispatches both. Events transition to `DISPATCHED` then `CONFIRMED` in the feed.
6. **Audit:** Open UBID Trace → paste `KA-2024-001` → see the complete timeline: RECEIVED → DISPATCHED (FACTORIES) → CONFIRMED → DISPATCHED (SHOP_ESTAB) → CONFIRMED.

### Scene 2 — Department → SWS Propagation (Polling)

> *"The same business has its authorised signatory updated directly inside the Factories system."*

1. **Detection:** The Factories adapter is configured in POLLING mode. It detects a SIGNATORY_UPDATE for UBID `KA-2024-002`.
2. **Reverse Translation:** The department-specific payload is translated back to canonical format using the same mapping rules in reverse.
3. **Propagation:** The orchestrator identifies SWS as the target and dispatches the translated payload.
4. **Verification:** Open UBID Trace → `KA-2024-002` → see the reverse flow from FACTORIES → SWS with full audit trail.

### Scene 3 — Conflict Resolution

> *"Two updates for the same UBID arrive nearly simultaneously — one from SWS, one from Factories."*

1. **Setup:** Two events for UBID `KA-2024-003` are seeded — one from SWS, one from FACTORIES, both within the 60-second conflict window.
2. **Detection:** The ConflictDetector's Redis ZSET query finds both events. A `conflict_records` row is created.
3. **Resolution:** The conflict policy for ADDRESS_CHANGE is `SOURCE_PRIORITY` with `priority_source = SWS`. The SWS event wins; the Factories event is marked `SUPERSEDED`.
4. **Dashboard:** Open Conflict Queue tab → see the resolved conflict with full explanation: *"SOURCE_PRIORITY: SWS wins over FACTORIES for ADDRESS_CHANGE"*.
5. **Audit:** The audit trail shows `CONFLICT_RESOLVED` with `conflict_policy = SOURCE_PRIORITY` and `superseded_by = {SWS event ID}`.

### Scene 4 — Dead Letter Queue & Recovery

> *"The Factories API returns 503 Service Unavailable."*

1. **Failure:** An event for UBID `KA-2024-004` is dispatched to FACTORIES, but the mock returns 503.
2. **Retry:** The OutboxWorker retries with exponential backoff: 2s → 4s → 8s → 16s → 32s. All 5 attempts fail.
3. **DLQ:** The event is parked in the Dead Letter Queue with failure reason `503 Service Unavailable`.
4. **Dashboard:** Open DLQ tab → inspect the full JSON payload → click **Redispatch** → the event is re-queued and (once the mock is back) successfully delivered.

### Scene 5 — Natural Language Audit Query

> *"An operator asks: Show me all conflicts for the FACTORIES department."*

1. **Query:** Open the "Ask the Fabric" tab → type the question in plain English.
2. **AI Translation:** The NaturalLanguageQueryService sends the question to the LLM, which generates safe, read-only SQL.
3. **Results:** The audit data is returned in a formatted table with conflict IDs, policies applied, and timestamps.

### Scene 6 — Department Health Scoreboard

> *"Which departments are degrading?"*

1. **Dashboard:** Open the "Dept Health" tab.
2. **Grades:** See real-time health grades: FACTORIES = A (92/100), SHOP_ESTAB = B (76/100), REVENUE = D (54/100).
3. **Sparklines:** 7-day trend charts show REVENUE has been declining from C → D.
4. **Alerts:** The "Departments Needing Attention" panel flags REVENUE with *"3 events stuck in Dead Letter Queue"* — click to jump directly to the DLQ tab.

---

## 7. Edge Cases Handled

| Edge Case | How We Handle It |
|---|---|
| **Duplicate requests** | SHA-256 fingerprint deduplication with `INSERT ON CONFLICT` — duplicate payloads return `DUPLICATE_SKIP` |
| **Department temporarily down** | Exponential backoff retry (5 attempts, 2s → 32s), then park in DLQ for manual redispatch |
| **Schema mismatch** | Translation engine emits warnings for missing fields; partial payloads are still dispatched. Drift Detection alerts operators to schema changes. |
| **Conflicting simultaneous updates** | Redis ZSET sliding window detects concurrent mutations; configurable policy resolves automatically or holds for manual review |
| **No native event emission** | POLLING mode polls `GET /changes?since={cursor}`; SNAPSHOT_DIFF mode hashes full snapshot and computes delta |
| **UBID not found** | `pending_ubid_resolution` table holds events until the UBID is resolvable |
| **Partial propagation failure** | Each target is independent. If FACTORIES succeeds and SHOP_ESTAB fails, the SHOP_ESTAB entry retries independently while FACTORIES is confirmed |
| **Network partition during write** | Transactional outbox pattern: the event is committed to `propagation_outbox` in the same DB transaction as the ledger update. If the process crashes, OutboxWorker picks it up on restart. |
| **Idempotency race condition** | `SELECT FOR UPDATE` on the fingerprint row prevents two workers from processing the same event concurrently |
| **SLA breach on held conflicts** | Two-level escalation: L1 = notification at SLA deadline; L2 = automatic resolution using fallback policy after extension period |

---

## 8. Conflict Resolution Policy

### How Conflicts Are Detected

The `ConflictDetector` uses a **Redis Sorted Set (ZSET)** keyed by UBID. When a new event arrives:

1. Add the event to `conflict:window:{ubid}` with the ingestion timestamp as score
2. Query the ZSET for all events within the last 60 seconds
3. If any event in the window is from a **different source system**, flag as conflict

### Resolution Strategies

| Policy | Behaviour | Use Case |
|---|---|---|
| `LAST_WRITE_WINS` | Latest `ingestion_timestamp` wins | Default fallback when no specific policy is configured |
| `SOURCE_PRIORITY` | Configured `priority_source` always wins | ADDRESS_CHANGE → SWS is authoritative; SIGNATORY_UPDATE → FACTORIES is authoritative |
| `HOLD_FOR_REVIEW` | Both events parked in `conflict_hold_queue`; neither propagated until an operator resolves | OWNERSHIP_CHANGE — too sensitive for automatic resolution |

### How the Decision Is Logged

Every conflict resolution produces an `audit_records` entry with:
- `audit_event_type = 'CONFLICT_RESOLVED'`
- `conflict_policy = 'SOURCE_PRIORITY'` (or whichever policy was applied)
- `superseded_by = {winning event UUID}`
- `before_state` / `after_state` = full JSONB snapshots

---

## 9. Audit Trail Schema

### Sample Audit Log Entry

```json
{
  "audit_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "event_id": "11111111-2222-3333-4444-555555555555",
  "ubid": "KA-2024-003",
  "source_system": "SWS",
  "target_system": "FACTORIES",
  "audit_event_type": "CONFLICT_RESOLVED",
  "ts": "2026-05-07T14:30:00Z",
  "conflict_policy": "SOURCE_PRIORITY",
  "superseded_by": "66666666-7777-8888-9999-000000000000",
  "before_state": {
    "registeredAddress": {
      "line1": "100 MG Road",
      "city": "Bengaluru"
    }
  },
  "after_state": {
    "registeredAddress": {
      "line1": "200 Brigade Road",
      "city": "Bengaluru"
    }
  }
}
```

This entry tells you: *The SWS event won the conflict because the SOURCE_PRIORITY policy designates SWS as authoritative for ADDRESS_CHANGE. The Factories event (superseded_by) was marked SUPERSEDED. The business address changed from MG Road to Brigade Road.*

---

## 10. How to Run the Demo

### Prerequisites

- **Docker Desktop** (with Docker Compose v2)
- **Make** (pre-installed on macOS/Linux)
- ~4 GB free RAM for the 9-container stack

### One-Command Launch

```bash
# Clone the repository
git clone <repo-url>
cd karnataka-integration-fabric-backend

# Build + start everything + seed demo data
make demo
```

This single command will:
1. Build the Spring Boot JAR (multi-stage Docker build with JDK 21)
2. Build the Next.js dashboard (standalone mode)
3. Start 9 containers: PostgreSQL, Redis, ZooKeeper, Kafka, Fabric API, Dashboard, and 3 WireMock stubs (SWS, Factories, Shop & Estab)
4. Wait for the API health check to pass
5. Seed 50 synthetic businesses + 4 demo scenarios via `POST /api/v1/demo/reset`

### Access Points

| Service | URL |
|---|---|
| **Dashboard** | http://localhost:3000 |
| **Fabric API** | http://localhost:8080 |
| **Mock SWS** | http://localhost:8081 |
| **Mock Factories** | http://localhost:8082 |
| **Mock Shop & Estab** | http://localhost:8083 |

### Quick Reset (Between Demos)

```bash
make demo-reset    # Wipes all data + re-seeds in ~2 seconds
```

### Tear Down

```bash
make docker-down   # Stops all containers, removes volumes
```

---

## 11. What We Would Build Next

### Round 2 Enhancements

| Feature | Description |
|---|---|
| **Sandbox Environment** | Full sandbox with mock SWS and department endpoints that accept and return realistic payloads |
| **Additional Adapters** | Revenue, BBMP, Transport department adapters with department-specific schema mappings |
| **UBID Resolution Service** | Integration with the UBID registry for real-time UBID validation and cross-referencing |
| **Performance Benchmarks** | Load testing with 10,000 concurrent events; publish throughput and P99 latency numbers |
| **Multi-Tenant** | Support for multiple states using the same fabric with isolated configurations |
| **Compliance Reports** | Automated weekly reports showing sync completeness per department |
| **Mobile Notifications** | Push notifications to department officers when HOLD_FOR_REVIEW conflicts need attention |

---

## 12. Team & Submission Info

| | |
|---|---|
| **Hackathon** | Karnataka Commerce & Industry — Two-Way Interoperability Challenge |
| **Team Name** | Samanvay |
| **Project** | Karnataka Integration Fabric — Bidirectional UBID-Keyed Sync Layer |

---

<p align="center">
  <em>Built with ☕ and conviction that interoperability shouldn't require rewriting legacy systems.</em>
</p>
