# swim-digital-notam-consumer — Knowledge Base


## What This Is

**ANSP (Air Navigation Service Provider) role.** Consumes Digital NOTAM events from an AISP broker (e.g., EUROCONTROL EAD) via AMQP 1.0. Validates AIXM 5.1.1 XML, persists to MongoDB, routes to 6 Kafka topics by business intent.

~55 classes. 14 unit + 27 integration tests.

## CRITICAL: Who This Connects To

**NEVER connects to `swim-digital-notam-provider`.** Consumer and Provider are independent products.

During dev/test → connects to `swim-dnotam-consumer-validator` (mock AISP).

| Config field | Points to |
|---|---|
| `amqpBrokerHost` | `dnotam-consumer-validator` Artemis |
| `subscriptionManager.url` | `dnotam-consumer-validator` SM API |

## Architecture

```
com.github.swim_developer.dnotam.consumer
├── domain/model/        Subscription, FilterDimension
├── application/usecase/ DnotamSubscriptionUseCase, DnotamEventProcessingUseCase
├── application/service/ DnotamEventDataValidator, DnotamEventExtractorAdapter
└── infrastructure/
    ├── in/amqp/         AMQP inbound (from broker)
    ├── in/rest/         REST endpoints
    ├── in/scheduling/   Heartbeat checker, renewal scheduler
    └── out/
        ├── persistence/ MongoDB repositories
        ├── kafka/       Kafka outbox adapters
        └── client/      HTTP clients (Subscription Manager)
```

## Framework Wiring

| Framework Abstract | This Repo Implementation |
|---|---|
| `AbstractAmqpConsumerManager` | `AmqpConsumerManager` |
| `AbstractEventProcessor` | `EventProcessor` |
| `AbstractInboxEventConsumer` | `InboxEventConsumer` |
| `AbstractSubscriptionService` | `SubscriptionService` |
| `AbstractIdempotencyCache` | `IdempotencyCache` (Caffeine L1 + MongoDB L2) |
| `AbstractDeadLetterService` | `DeadLetterService` |
| `SubscriptionHeartbeatChecker` | `HeartbeatTimeoutHandler` |
| `SwimInboxStore` EP1 | `KafkaInboxStore` (in `swim-inbox-store-kafka`) |
| `SwimInboxReader` EP2 | `DnotamInboxMessageHandler` (extends `AbstractKafkaInboxReader`, `@Incoming dnotam-inbox`) |
| `SwimOutboxRouter` EP3 | `DnotamKafkaOutboxRouter` (in `swim-outbox-kafka-dnotam`) |
| `SwimEventExtractor` | `DnotamEventExtractorAdapter` |
| `SwimPayloadValidator` | `DnotamEventDataValidator` |

## Supported DNOTAM Scenarios (CP1)

| Scenario | Kafka Topic |
|----------|-------------|
| RWY.CLS, AD.CLS | `dnotam-events-closure-topic` |
| RWY.LIM | `dnotam-events-restriction-topic` |
| SFC.CON | `dnotam-events-surface-condition-topic` |
| SAA.ACT | `dnotam-events-airspace-topic` |
| OBS.NEW, NAV.UNS | `dnotam-events-hazards-navaids-topic` |
| UNKNOWN | `dnotam-events-others-topic` |

## MongoDB

- DB: `swim-dnotam`
- Collections: `inbox_messages` (TTL 30d), `dnotam_events` (TTL 90d), `subscriptions`, `dead_letter_queue`

## Key Features

- Multi-provider (ADR-015): connects to N providers simultaneously via `AmqpConnectionRegistry` + `SmClientRegistry`
- Self-healing: 404/410 from provider triggers `reconcileCreate` (auto re-subscribe)
- Heartbeat timeout: detects silence > 45s, queries provider, recovers
- Auto-renewal: renews subscriptions before `subscriptionEnd` expiry
- Circuit breaker: per-provider, 5 failures → OPEN 30s
- GraphQL API for querying processed events
- Idempotency: SHA-256 hash, Caffeine L1 + MongoDB L2

## Performance

| Metric | Value |
|--------|-------|
| Processing time | ~9ms avg |
| GZIP compression | ~90% reduction |
| Bulkhead | 250 concurrent |

## Build & Run

```bash
# Prerequisites: swim-developer-framework installed
cd ../swim-developer-framework && mvn clean install -DskipTests

# Build
./mvnw clean package -DskipTests

# Dev mode (auto-provisions Kafka, MongoDB, Artemis via Dev Services)
quarkus dev

# Integration tests (Testcontainers: MongoDB, Artemis, Redpanda, WireMock)
./mvnw verify -DskipITs=false
```

Local infra: `podman compose up -d` (requires a compose.yml with Kafka, MongoDB/PostgreSQL, Artemis — see repo root)`
