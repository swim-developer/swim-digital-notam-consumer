# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

ANSP-role DNOTAM consumer built on Quarkus and the `swim-developer-framework`. Subscribes to Digital NOTAM events via AMQP 1.0 / mTLS, validates AIXM 5.1.1 XML (XSD), persists to MongoDB, and routes to 6 Kafka topics by business intent.

## Build & Run

```bash
# Build (compiles tests but skips execution)
./mvnw clean package -DskipTests

# Dev mode (connects to compose infra on localhost)
./mvnw quarkus:dev

# Unit tests only
./mvnw test

# Unit + integration tests (Testcontainers: MongoDB, Artemis, Redpanda, WireMock)
./mvnw verify -DskipITs=false

# Run a single test class
./mvnw test -Dtest=DnotamEventFilterServiceTest

# Run a single integration test class
./mvnw verify -DskipITs=false -Dit.test=DnotamConsumerIT

# Native integration tests (5-15 min, validates CDI/reflection/cache in native image)
./mvnw verify -Pnative

# Local infra (first time: --build for Artemis image)
podman compose up --build -d

# Container image (JVM multi-arch)
make jvm
```

## Build Rules

- **NEVER** use `-Dmaven.test.skip=true` — use `-DskipTests` (compiles tests, catches compilation errors).
- **NEVER** run `mvn verify` without `-DskipITs=false` — integration tests are skipped by default (`<skipITs>true</skipITs>` in pom.xml).
- **NEVER** run integration tests of multiple projects in parallel — Testcontainers bind to host ports.
- Container runtime is **Podman**, not Docker.

## Sibling Dependencies

Must be installed in local Maven repo before building. Use `make sync` to clone + install all, or manually:

1. `swim-developer-root` — `./mvnw install -N -DskipTests`
2. `swim-aixm-model` — `./mvnw clean install -DskipTests`
3. `swim-developer-framework` — `./mvnw clean install -DskipTests`
4. `swim-developer-extensions` — `./mvnw clean install -DskipTests`

## Architecture

Hexagonal architecture (ports & adapters). Base package: `com.github.swim_developer.dnotam.consumer`.

```
domain/model/           Domain entities (Subscription, Event, FilterDimension, EventScenario)
application/port/in/    Inbound ports (ManageSubscriptionPort)
application/port/out/   Outbound ports (EventStore, SubscriptionStore, RemoteSubscriptionManagerPort)
application/usecase/    Use cases (DnotamSubscriptionUseCase, DnotamEventProcessingUseCase)
application/service/    Domain services (DnotamEventFilterService, DnotamEventPersistenceService, DnotamEventDataValidator)
infrastructure/in/amqp/ AMQP inbound adapter (DnotamInboxMessageHandler)
infrastructure/in/rest/ REST endpoints (ConsumerSubscriptionResource, ConsumerEventResource, OperationalResource)
infrastructure/out/     Outbound adapters: persistence (MongoDB), messaging (Kafka), client (SM REST), xml (JAXB)
```

### Framework Wiring

This service extends abstract classes from `swim-developer-framework`. Key mappings:

| Framework Abstract | Implementation |
|---|---|
| `AbstractAmqpConsumerManager` | `AmqpConsumerManager` |
| `AbstractEventProcessor` | `EventProcessor` |
| `AbstractInboxEventConsumer` | `InboxEventConsumer` |
| `AbstractSubscriptionService` | `SubscriptionService` |
| `AbstractIdempotencyCache` | `IdempotencyCache` (Caffeine L1 + MongoDB L2) |
| `AbstractDeadLetterService` | `DeadLetterService` |
| `SwimEventExtractor` SPI | `DnotamEventExtractor` |
| `SwimPayloadValidator` SPI | `DnotamEventDataValidator` |

### Event Flow

AMQP broker -> `DnotamInboxMessageHandler` -> Kafka inbox topic -> `DnotamEventProcessingUseCase` (unmarshal JAXB -> validate XSD -> filter -> persist MongoDB -> route outbox) -> `DnotamOutboxMessageHandler` -> domain Kafka topics.

### Consumer-Validator Connectivity

**The consumer NEVER connects to `swim-dnotam-provider`.** Consumer and Provider are independent products. During dev/test, the consumer connects to `dnotam-consumer-validator` (mock AISP with its own Artemis + Subscription Manager API + event generator). The `compose.yml` provisions this validator automatically.

## Code Standards

- Logging: always `@Slf4j` (Lombok). Never `LoggerFactory.getLogger()`.
- Max 400 lines per Java file.
- No inner/nested classes — every class in its own file.
- No comments in code.
- No Java Reflection (no `Field.setAccessible`, nowhere).
- Tests use RestAssured for HTTP and AssertJ for assertions.
- JSON processing in shell: `jq` only (no Python, no Node).

## Test Integrity

**NEVER change production code, disable features, or remove functionality to make a test pass.** If a test fails, investigate the real bug. Ask before touching production code for test reasons.
