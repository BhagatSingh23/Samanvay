# Karnataka Integration Fabric

> Multi-module Spring Boot 3.2 integration platform for Karnataka state services.

## Architecture

```
karnataka-integration-fabric/
├── fabric-core          # Canonical domain model (pure POJOs, no Spring)
├── fabric-adapters      # External system connectors (DB, Kafka, APIs)
├── fabric-propagation   # Event routing & message distribution
├── fabric-audit         # Audit trail & compliance logging
└── fabric-api           # Spring Web MVC REST layer (deployable)
```

## Tech Stack

| Concern           | Technology                      |
|-------------------|---------------------------------|
| Framework         | Spring Boot 3.2                 |
| Messaging         | Apache Kafka 3.6                |
| Database          | PostgreSQL + Flyway migrations  |
| Resilience        | Resilience4j (circuit breaker)  |
| Serialization     | Jackson                         |
| Code Generation   | Lombok + MapStruct              |
| Build             | Maven (multi-module + wrapper)  |

## Prerequisites

- **Java 17+** (Maven Wrapper downloads Maven automatically)
- **Docker & Docker Compose** (for local infra)

## Quick Start

```bash
# Start local infrastructure (PostgreSQL, Kafka, Zookeeper)
make docker-up

# Build all modules (uses Maven Wrapper — no mvn install needed)
make build
# or directly:
./mvnw clean package -DskipTests

# Run tests
make test

# Stop infrastructure
make docker-down
```

## API Endpoints

| Method | Path                      | Description            |
|--------|---------------------------|------------------------|
| GET    | `/api/v1/health`          | Service health check   |
| POST   | `/api/v1/events`          | Submit integration event |
| GET    | `/api/v1/events/{eventId}`| Query event status     |

## Module Details

### fabric-core
Pure Java domain model — `IntegrationEvent`, `ServiceIdentifier`, `RoutingRule`, `EventStatus`.
**No Spring dependencies** to keep the domain portable and independently testable.

### fabric-adapters
Database repositories, Kafka producers/consumers, and external API clients.
Uses Resilience4j for circuit-breaking and MapStruct for entity ↔ domain mapping.

### fabric-propagation
Event routing engine that fans out `IntegrationEvent`s to destination services
based on `RoutingRule` configuration.

### fabric-audit
AOP-driven audit logging that captures event lifecycle transitions
into the `audit_log` table for compliance reporting.

### fabric-api
Deployable Spring Boot application exposing the REST API.
Aggregates all modules via dependency and component scanning.

## License

TBD
