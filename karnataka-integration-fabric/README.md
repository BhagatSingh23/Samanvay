# Karnataka Integration Fabric

> Multi-module Spring Boot 3.2 integration platform for Karnataka state services.

## Architecture

```
karnataka-integration-fabric/
├── fabric-core          # Canonical domain model (pure POJOs, no Spring)
├── fabric-adapters      # External system connectors (DB, Kafka, APIs)
├── fabric-propagation   # Event routing & message distribution
├── fabric-audit         # Audit trail & compliance logging
├── fabric-api           # Spring Web MVC REST layer (deployable)
├── dashboard/           # Next.js 14 operational dashboard
└── helm/                # Kubernetes Helm chart
```

## Tech Stack

| Concern           | Technology                      |
|-------------------|---------------------------------|
| Framework         | Spring Boot 3.2                 |
| Messaging         | Apache Kafka 3.6                |
| Database          | PostgreSQL + Flyway migrations  |
| Resilience        | Resilience4j (circuit breaker)  |
| Dashboard         | Next.js 14 + SWR               |
| Serialization     | Jackson                         |
| Build             | Maven (multi-module + wrapper)  |
| Deployment        | Docker Compose / Helm           |

## Prerequisites

- **Java 21+** (Maven Wrapper downloads Maven automatically)
- **Docker & Docker Compose** (for local infra)
- **Node.js 20+** (for dashboard development)

## Quick Start

```bash
# Local demo (Docker Compose)
make docker-up
make demo-seed
open http://localhost:3000

# Kubernetes sandbox
helm install karnataka-fabric ./helm/karnataka-fabric
```

### What happens

1. `make docker-up` builds and starts all 9 containers:
   PostgreSQL, Redis, Zookeeper, Kafka, fabric-api (demo profile),
   Next.js dashboard, and 4 WireMock stubs.
2. `make demo-seed` polls `GET /api/v1/health` until the API is UP,
   then calls `POST /api/v1/demo/reset` to seed 50 businesses
   and 4 demo scenarios.
3. Open `http://localhost:3000` to see the dashboard with live data.

> **Tip:** Run `make demo-reset` anytime to clear data and re-seed
> without restarting containers.

### Other commands

```bash
make build          # Build all modules (skips tests)
make test           # Run full test suite
make docker-down    # Tear down all containers & volumes
make demo-reset     # Clear & re-seed demo data
make helm-install   # Install Helm chart to current k8s context
make demo           # Full sequence: up → wait → seed → print URLs
```

## Demo Scenarios

| Scene | UBID | Description | Dashboard Tab |
|-------|------|-------------|---------------|
| 1 | KA-2024-001 | Address change via SWS → FACTORIES + SHOP_ESTAB | Live Event Feed |
| 2 | KA-2024-002 | Signatory update via FACTORIES polling | UBID Trace |
| 3 | KA-2024-003 | Conflict detection (SOURCE_PRIORITY, SWS wins) | Conflict Queue |
| 4 | KA-2024-004 | DLQ — FACTORIES returns 503 after 5 retries | Dead Letter Queue |

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/health` | Service health check |
| POST | `/api/v1/inbound/sws` | SWS webhook ingestion |
| GET | `/api/v1/events?limit=50` | Latest events |
| GET | `/api/v1/audit/ubid/{ubid}` | Audit trail for UBID |
| GET | `/api/v1/audit/event/{eventId}` | Event lifecycle |
| GET | `/api/v1/conflicts?resolved=false` | Unresolved conflicts |
| POST | `/api/v1/conflicts/{id}/resolve` | Manual conflict resolution |
| POST | `/api/v1/audit/replay` | Replay events for UBID |
| POST | `/api/v1/demo/reset` | Reset & re-seed demo data |

## Module Details

### fabric-core
Pure Java domain model — `CanonicalServiceRequest`, `AuditEventType`, `PropagationStatus`.
**No Spring dependencies** to keep the domain portable and independently testable.

### fabric-adapters
Department registry, webhook normalisers, polling/snapshot adapters.
Uses Resilience4j for circuit-breaking.

### fabric-propagation
Event routing engine with conflict detection, resolution policies
(LAST_WRITE_WINS, SOURCE_PRIORITY, HOLD_FOR_REVIEW), and outbox worker.

### fabric-audit
JDBC-backed audit service, query service, and replay engine.

### fabric-api
Deployable Spring Boot application. REST controllers, demo data seeder,
and component scanning across all modules.

### dashboard
Next.js 14 operational dashboard with 5 tabs: Live Event Feed,
UBID Trace (timeline), Conflict Queue, Dead Letter Queue, System Health.

## Helm Chart

```bash
helm install karnataka-fabric ./helm/karnataka-fabric

# Custom values
helm install karnataka-fabric ./helm/karnataka-fabric \
  --set fabricApi.replicas=3 \
  --set postgres.storage.size=10Gi
```

See `helm/karnataka-fabric/values.yaml` for all configurable options.

## License

TBD
