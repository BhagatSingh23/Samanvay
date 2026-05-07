# ============================================================
# Karnataka Integration Fabric — Makefile
# ============================================================

.PHONY: build test docker-up docker-down docker-infra demo-seed \
        demo-reset wait-health clean helm-install helm-uninstall

# ── Build ─────────────────────────────────────────────────────

# Build all modules (skip tests for speed)
build:
	./mvnw clean package -DskipTests

# Run full test suite
test:
	./mvnw clean verify

# Remove all build artifacts
clean:
	./mvnw clean

# ── Docker Compose ────────────────────────────────────────────

# Spin up EVERYTHING (infra + app + mocks + dashboard)
docker-up:
	docker compose -f docker-compose.yml up -d --build

# Spin up infra only (postgres, redis, kafka)
docker-infra:
	docker compose -f docker-compose.yml up -d postgres redis zookeeper kafka

# Tear down all containers & volumes
docker-down:
	docker compose -f docker-compose.yml down -v

# ── Demo ──────────────────────────────────────────────────────

# Wait for fabric-api health, then trigger demo seed via reset endpoint
demo-seed: wait-health
	@echo ""
	@echo "╔══════════════════════════════════════════════════════"
	@echo "║  Seeding demo data via POST /api/v1/demo/reset …"
	@echo "╚══════════════════════════════════════════════════════"
	@curl -s -X POST http://localhost:8080/api/v1/demo/reset \
		-H "Content-Type: application/json" | python3 -m json.tool 2>/dev/null || \
	curl -s -X POST http://localhost:8080/api/v1/demo/reset \
		-H "Content-Type: application/json"
	@echo ""
	@echo "✓ Demo ready — open http://localhost:3000"
	@echo ""

# Reset demo data (clear + re-seed) without restarting
demo-reset: wait-health
	@curl -s -X POST http://localhost:8080/api/v1/demo/reset \
		-H "Content-Type: application/json" | python3 -m json.tool 2>/dev/null || \
	curl -s -X POST http://localhost:8080/api/v1/demo/reset \
		-H "Content-Type: application/json"

# Wait for fabric-api to become healthy
wait-health:
	@bash scripts/wait-for-health.sh http://localhost:8080/api/v1/health 120 || \
		powershell -ExecutionPolicy Bypass -File scripts/wait-for-health.ps1 -Url http://localhost:8080/api/v1/health -Timeout 120

# ── Helm ──────────────────────────────────────────────────────

# Install Helm chart
helm-install:
	helm install karnataka-fabric ./helm/karnataka-fabric

# Uninstall Helm chart
helm-uninstall:
	helm uninstall karnataka-fabric

# ── Quick demo (single command) ──────────────────────────────

# Full demo: build → start → wait → seed → open
demo: docker-up demo-seed
	@echo "╔══════════════════════════════════════════════════════"
	@echo "║  Karnataka Integration Fabric — Demo Running!"
	@echo "║"
	@echo "║  Dashboard:  http://localhost:3000"
	@echo "║  API:        http://localhost:8080"
	@echo "║  H2 Console: (demo uses PostgreSQL)"
	@echo "║"
	@echo "║  Quick reset: make demo-reset"
	@echo "║  Tear down:   make docker-down"
	@echo "╚══════════════════════════════════════════════════════"
