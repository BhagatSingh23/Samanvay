# ============================================================
# Karnataka Integration Fabric — Makefile
# ============================================================

.PHONY: build test docker-up docker-down docker-infra clean

# ---- Build all modules (skip tests for speed) ----
build:
	./mvnw clean package -DskipTests

# ---- Run full test suite ----
test:
	./mvnw clean verify

# ---- Spin up EVERYTHING (infra + app + mocks) ----
docker-up:
	docker compose -f docker-compose.yml up -d --build

# ---- Spin up infra only (postgres, redis, kafka) ----
docker-infra:
	docker compose -f docker-compose.yml up -d postgres redis zookeeper kafka

# ---- Tear down all containers & volumes ----
docker-down:
	docker compose -f docker-compose.yml down -v

# ---- Remove all build artifacts ----
clean:
	./mvnw clean
