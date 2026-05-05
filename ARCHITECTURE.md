# swim-dnotam-consumer — Architecture

> Diagrams use [Mermaid](https://mermaid.js.org) and render natively on GitHub.

**Role**: ANSP (Air Navigation Service Provider) — consumes Digital NOTAM events from an external AISP via AMQP, processes AIXM 5.1.1 XML payloads, persists events to MongoDB, and forwards domain events to Kafka.

---

## 1. System Context (C4 Level 1)

```mermaid
C4Context
    title System Context — swim-dnotam-consumer

    Person(operator, "ANSP Operator", "Configures subscriptions and queries features via REST API")

    System(consumer, "swim-dnotam-consumer", "DNOTAM Consumer: subscribes to AISP DNOTAM topics, processes AIXM 5.1.1 events")

    System_Ext(aisp, "AISP / Consumer Validator", "External DNOTAM provider — Subscription Manager REST API + AMQP broker")
    System_Ext(atm, "ATM Systems", "Downstream consumers of processed DNOTAM events via Kafka")
    System_Ext(mongo, "MongoDB", "Event and subscription persistence")
    System_Ext(kafka, "Apache Kafka", "Domain event forwarding")

    Rel(operator, consumer, "Manages subscriptions and queries features", "REST / HTTPS")
    Rel(consumer, aisp, "Subscribes to DNOTAM topics, manages subscriptions", "AMQP 1.0 / mTLS + REST / HTTPS / mTLS")
    Rel(consumer, mongo, "Persists events and subscriptions")
    Rel(consumer, kafka, "Forwards DNOTAM domain events")
    Rel(consumer, atm, "Consumed by ATM systems", "Apache Kafka")

    UpdateLayoutConfig($c4ShapeInRow="3", $c4BoundaryInRow="1")
```

---

## 2. Container Diagram (C4 Level 2)

```mermaid
C4Container
    title Container Diagram — swim-dnotam-consumer

    Person(operator, "ANSP Operator")

    System_Ext(extBroker, "AISP AMQP Broker", "ActiveMQ Artemis — AMQP 1.0 / mTLS")
    System_Ext(extSM, "External Subscription Manager", "AISP REST API — HTTPS / mTLS")
    System_Ext(kafka, "Apache Kafka", "Domain event forwarding")

    System_Boundary(sys, "swim-dnotam-consumer") {
        Container(app, "swim-dnotam-consumer", "Quarkus / Java 21", "DNOTAM event subscription, AIXM 5.1.1 processing, idempotent persistence")
        ContainerDb(mongo, "MongoDB", "Document store", "DNOTAM events and subscription state")
    }

    Rel(operator, app, "Manages subscriptions", "REST / HTTPS")
    Rel(app, extBroker, "Consumes DNOTAM events", "AMQP 1.0 / mTLS")
    Rel(app, extSM, "Registers and manages subscriptions", "REST / HTTPS / mTLS")
    Rel(app, mongo, "Persists events and subscriptions")
    Rel(app, kafka, "Forwards processed domain events")
```

---

## 3. Component Diagram (C4 Level 3)

```mermaid
C4Component
    title Component Diagram — swim-dnotam-consumer

    System_Ext(broker, "AISP AMQP Broker")
    System_Ext(sm, "External Subscription Manager")
    System_Ext(mongo, "MongoDB")
    System_Ext(kafka, "Apache Kafka")

    Container_Boundary(consumer, "swim-dnotam-consumer") {
        Component(subRes, "ConsumerSubscriptionResource", "JAX-RS / port/in", "REST endpoint — subscription CRUD")
        Component(evtRes, "ConsumerEventResource", "JAX-RS", "REST endpoint — event queries")
        Component(opRes, "OperationalResource", "JAX-RS", "Operational metrics and DLQ queries")
        Component(ftRes, "FeatureResource", "JAX-RS", "WFS-style feature queries (forwarded to Subscription Manager)")

        Component(inboxHandler, "DnotamInboxMessageHandler", "SmallRye Messaging", "AMQP consumer — receives events, delegates to event processing use case")
        Component(outboxHandler, "DnotamOutboxMessageHandler", "Kafka Producer", "Routes processed events to domain-specific Kafka topics")

        Component(subUC, "DnotamSubscriptionUseCase", "CDI", "Subscription lifecycle: create, pause, resume, delete — via Subscription Manager")
        Component(evtUC, "DnotamEventProcessingUseCase", "CDI", "Orchestrates: parse XML, validate, filter, persist, route to outbox")

        Component(filterSvc, "DnotamEventFilterService", "CDI", "Evaluates subscription filter criteria against event")
        Component(persistSvc, "DnotamEventPersistenceService", "CDI", "Persists event to MongoDB via EventStore port")
        Component(validator, "DnotamEventDataValidator", "CDI", "Validates event data against business rules")

        Component(jaxbPool, "DnotamJaxbUnmarshallerPool", "JAXB", "Thread-safe pool of JAXB unmarshallers for AIXM 5.1.1 XML")
        Component(extractor, "DnotamEventExtractor", "CDI / SwimEventExtractor SPI", "Extracts event type and metadata from AIXM Basic Message")
        Component(xmlParser, "DnotamXmlEnvelopeParser", "CDI", "Parses the AIXM Basic Message envelope")

        Component(smAdapter, "DnotamSubscriptionManagerAdapter", "MicroProfile REST Client", "HTTP client to external Subscription Manager — implements RemoteSubscriptionManagerPort")
        Component(mongoSub, "MongoSubscriptionStore", "Panache / port/out", "Subscription state persistence")
        Component(mongoEvt, "MongoEventStore", "Panache / port/out", "DNOTAM event persistence")
        Component(idempotency, "DnotamIdempotencyCacheDeclaration", "CDI", "Idempotency cache — prevents duplicate event processing")
    }

    Rel(subRes, subUC, "calls via", "ManageSubscriptionPort")
    Rel(inboxHandler, evtUC, "delegates to")
    Rel(evtUC, jaxbPool, "unmarshals with")
    Rel(evtUC, extractor, "extracts metadata with")
    Rel(evtUC, validator, "validates with")
    Rel(evtUC, filterSvc, "filters with")
    Rel(evtUC, persistSvc, "persists with")
    Rel(evtUC, outboxHandler, "routes to")
    Rel(subUC, smAdapter, "registers / updates via", "RemoteSubscriptionManagerPort")
    Rel(subUC, mongoSub, "persists via", "SubscriptionStore port")
    Rel(persistSvc, mongoEvt, "persists via", "EventStore port")

    Rel(inboxHandler, broker, "consumes", "AMQP 1.0 / mTLS")
    Rel(smAdapter, sm, "manages subscriptions", "REST / HTTPS / mTLS")
    Rel(mongoSub, mongo, "persists to")
    Rel(mongoEvt, mongo, "persists to")
    Rel(outboxHandler, kafka, "publishes to")
```

---

## 4. Subscription Lifecycle — Sequence

```mermaid
sequenceDiagram
    autonumber
    actor Op as ANSP Operator
    participant Res as ConsumerSubscriptionResource
    participant UC as DnotamSubscriptionUseCase
    participant Adapter as DnotamSubscriptionManagerAdapter
    participant ExtSM as External Subscription Manager
    participant Store as MongoSubscriptionStore

    Op->>Res: POST /subscriptions
    Res->>UC: createSubscription(request) via ManageSubscriptionPort
    UC->>Adapter: register(topic, queue) via RemoteSubscriptionManagerPort
    Adapter->>ExtSM: POST /swim/v1/subscriptions (REST / mTLS)
    ExtSM-->>Adapter: subscriptionId, queueName, status PAUSED
    Adapter-->>UC: subscription registered
    UC->>Store: persist subscription state
    Store-->>UC: ok
    UC-->>Res: subscription details
    Res-->>Op: 201 Created

    Note over UC: AbstractReconciliationScheduler (framework) runs<br/>periodically to sync local state with Subscription Manager.
```

---

## 5. Event Processing — Sequence

```mermaid
sequenceDiagram
    autonumber
    participant Broker as AISP AMQP Broker
    participant Handler as DnotamInboxMessageHandler
    participant UC as DnotamEventProcessingUseCase
    participant JAXB as DnotamJaxbUnmarshallerPool
    participant Filter as DnotamEventFilterService
    participant Store as MongoEventStore
    participant Outbox as DnotamOutboxMessageHandler

    Broker->>Handler: deliver AIXM 5.1.1 XML message (AMQP / mTLS)
    Handler->>UC: process(message)
    UC->>JAXB: unmarshal(xml)
    JAXB-->>UC: AIXM object graph
    UC->>Filter: evaluate(event, subscription)
    Filter-->>UC: match / no-match
    UC->>Store: persist(event) via EventStore port
    Store-->>UC: ok
    UC->>Outbox: route(event)
    Outbox-->>UC: ok
    UC-->>Handler: SUCCESS — message acknowledged

    Note over Handler,Outbox: On failure: event routed to dead letter store.<br/>Message is still acknowledged to prevent redelivery loops.
```
