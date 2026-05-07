# ============================================================
# Multi-stage Dockerfile for fabric-api
# ============================================================

# ── Stage 1: Build ────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /build


COPY pom.xml pom.xml
COPY fabric-core/pom.xml        fabric-core/pom.xml
COPY fabric-adapters/pom.xml    fabric-adapters/pom.xml
COPY fabric-propagation/pom.xml fabric-propagation/pom.xml
COPY fabric-audit/pom.xml       fabric-audit/pom.xml
COPY fabric-api/pom.xml         fabric-api/pom.xml

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B -q

COPY . .
RUN mvn clean package -DskipTests -B -q

# ── Stage 2: Runtime ──────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN addgroup --system fabric && adduser --system --ingroup fabric fabric

COPY --from=builder /build/fabric-api/target/fabric-api-*.jar app.jar
RUN chown fabric:fabric app.jar

USER fabric

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseSerialGC", \
  "-Xss512k", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
