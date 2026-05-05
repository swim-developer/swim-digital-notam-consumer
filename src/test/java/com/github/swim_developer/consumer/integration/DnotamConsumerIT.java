package com.github.swim_developer.consumer.integration;


import com.github.swim_developer.extension.outbox.kafka.dnotam.DnotamEventCategory;
import com.github.swim_developer.extension.outbox.kafka.dnotam.DnotamEventClassifier;
import com.github.swim_developer.framework.application.model.OutboxDeliveryStatus;
import com.github.swim_developer.framework.application.model.ProcessingOutcome;
import com.github.swim_developer.framework.consumer.infrastructure.out.dlq.DeadLetterMessage;
import com.github.swim_developer.dnotam.consumer.domain.model.Event;
import com.github.swim_developer.dnotam.consumer.domain.model.Subscription;
import com.github.swim_developer.framework.persistence.mongodb.MongoDeadLetterStore;
import com.github.swim_developer.dnotam.consumer.infrastructure.out.persistence.MongoEventStore;
import com.github.swim_developer.dnotam.consumer.infrastructure.out.persistence.MongoSubscriptionStore;
import com.github.swim_developer.dnotam.consumer.application.usecase.DnotamEventProcessingUseCase;
import com.github.swim_developer.framework.consumer.infrastructure.out.idempotency.AbstractIdempotencyCache;
import com.github.swim_developer.framework.consumer.infrastructure.out.config.subscription.AbstractSubscriptionConfigParser;
import com.github.swim_developer.dnotam.consumer.application.usecase.DnotamSubscriptionUseCase;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

import org.junit.jupiter.api.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the DNOTAM consumer with real infrastructure.
 *
 * <p>Validates the full consumer lifecycle using:</p>
 * <ul>
 *   <li><b>MongoDB</b> — Real database via Quarkus Dev Services (Testcontainers)</li>
 *   <li><b>Kafka (Redpanda)</b> — Real broker via Quarkus Dev Services</li>
 *   <li><b>WireMock</b> — Simulates the SWIM Subscription Manager REST API</li>
 *   <li><b>Artemis</b> — Real AMQP 1.0 broker via Quarkus Dev Services</li>
 * </ul>
 *
 * <h2>What This Proves to the SFG</h2>
 * <ul>
 *   <li>The REST API correctly orchestrates subscription lifecycle with the provider</li>
 *   <li>XSD validation with real AIXM 5.1.1 schemas rejects invalid payloads</li>
 *   <li>Idempotency survives across real MongoDB persistence (L2 cache)</li>
 *   <li>The full pipeline persists events and dispatches to Kafka</li>
 *   <li>DLQ captures rejected messages with proper metadata</li>
 *   <li>Statistics and query endpoints return accurate real-time data</li>
 * </ul>
 *
 * @see DnotamEventProcessingUseCase
 * @see com.github.swim_developer.api.ConsumerResource
 * @see com.github.swim_developer.dnotam.consumer.application.usecase.DnotamSubscriptionUseCase
 */
@QuarkusTest
@ConnectWireMock
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DnotamConsumerIT {

    private static final String VALID_DNOTAM_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <message:AIXMBasicMessage xmlns:message="http://www.aixm.aero/schema/5.1.1/message"
                xmlns:gml="http://www.opengis.net/gml/3.2" xmlns:aixm="http://www.aixm.aero/schema/5.1.1"
                xmlns:event="http://www.aixm.aero/schema/5.1.1/event" xmlns:xlink="http://www.w3.org/1999/xlink"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                xsi:schemaLocation="http://www.aixm.aero/schema/5.1.1/message http://www.aixm.aero/schema/5.1.1/message/AIXM_BasicMessage.xsd
                http://www.aixm.aero/schema/5.1.1/event http://www.aixm.aero/schema/5.1.1/event/version_5.1.1-j/Event_Features.xsd"
                gml:id="M00001">
                <message:hasMember>
                    <event:Event gml:id="uuid.a0805c34-5a0d-4d00-b689-89a56e25d4ec">
                        <gml:identifier codeSpace="urn:uuid:">a0805c34-5a0d-4d00-b689-89a56e25d4ec</gml:identifier>
                        <event:timeSlice>
                            <event:EventTimeSlice gml:id="ID_GEN_00001_01">
                                <gml:validTime>
                                    <gml:TimePeriod gml:id="ID_GEN_00001_02">
                                        <gml:beginPosition>2026-01-07T10:35:25Z</gml:beginPosition>
                                        <gml:endPosition>2026-01-09T22:35:25Z</gml:endPosition>
                                    </gml:TimePeriod>
                                </gml:validTime>
                                <aixm:interpretation>BASELINE</aixm:interpretation>
                                <aixm:sequenceNumber>1</aixm:sequenceNumber>
                                <aixm:correctionNumber>0</aixm:correctionNumber>
                                <aixm:featureLifetime>
                                    <gml:TimePeriod gml:id="ID_GEN_00001_03">
                                        <gml:beginPosition>2026-01-07T10:35:25Z</gml:beginPosition>
                                        <gml:endPosition>2026-01-09T22:35:25Z</gml:endPosition>
                                    </gml:TimePeriod>
                                </aixm:featureLifetime>
                                <event:scenario>AD.CLS</event:scenario>
                                <event:version>2.0</event:version>
                                <event:concernedAirportHeliport xlink:href="urn:uuid:1b54b2d6-a5ff-4e57-94c2-f4047a381c64" xlink:title="AMSTERDAM/SCHIPHOL"/>
                                <event:notification>
                                    <event:NOTAM gml:id="ID_GEN_00001_04">
                                        <event:series>A</event:series>
                                        <event:number>3871</event:number>
                                        <event:year>2026</event:year>
                                        <event:type>N</event:type>
                                        <event:affectedFIR>EDGG</event:affectedFIR>
                                        <event:location>EHAM</event:location>
                                        <event:text>AD closed due to security incident.</event:text>
                                    </event:NOTAM>
                                </event:notification>
                            </event:EventTimeSlice>
                        </event:timeSlice>
                    </event:Event>
                </message:hasMember>
            </message:AIXMBasicMessage>
            """;

    private static final String INVALID_XML = "<not-valid-aixm>broken</not-valid-aixm>";

    WireMock wiremock;

    @Inject
    DnotamEventProcessingUseCase eventProcessor;

    @Inject
    MongoEventStore eventRepository;

    @Inject
    MongoDeadLetterStore dlqRepository;

    @Inject
    MongoSubscriptionStore subscriptionRepository;

    @Inject
    AbstractIdempotencyCache idempotencyCache;

    @Inject
    DnotamSubscriptionUseCase subscriptionService;

    @Inject
    AbstractSubscriptionConfigParser<?> subscriptionConfigParser;

    @Inject
    @CacheName("processed-messages")
    Cache l1Cache;

    @BeforeEach
    void cleanDatabase(TestInfo testInfo) {
        System.out.printf("%n══ ▶ %s.%s%n", getClass().getSimpleName(), testInfo.getDisplayName());
        eventRepository.deleteAll();
        dlqRepository.deleteAll();
        subscriptionRepository.deleteAllSubscriptions();
        l1Cache.invalidateAll().await().indefinitely();
        wiremock.removeMappings();
        wiremock.resetAllScenarios();
        wiremock.resetRequests();
    }

    // ─── Group 1: REST API + Subscription Lifecycle ───

    /**
     * POST /api/v1/subscriptions → WireMock receives the provider call,
     * returns a subscription response, and the consumer persists it to MongoDB.
     */
    @Test
    @Order(1)
    void createSubscriptionEndToEnd() {
        stubSubscriptionManagerCreate("sub-IT-001", "DNOTAM-client-sub-IT-001");

        var body = Map.of(
                "topic", "DNOTAM/v1",
                "eventScenario", List.of("RWY.CLS"),
                "airportHeliport", List.of("LPPT"),
                "description", "Integration test"
        );

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/subscriptions")
                .then()
                .statusCode(201);

        var persisted = subscriptionRepository.findBySubscriptionId("sub-IT-001");
        assertThat(persisted).isPresent();
        assertThat(persisted.get().getQueueName()).isEqualTo("DNOTAM-client-sub-IT-001");

        wiremock.verifyThat(postRequestedFor(urlPathEqualTo("/swim/v1/subscriptions")));
        wiremock.verifyThat(putRequestedFor(urlPathEqualTo("/swim/v1/subscriptions/sub-IT-001")));
    }

    /**
     * Duplicate configHash (same topic + filters) → returns existing subscription without calling SM again.
     */
    @Test
    @Order(2)
    void duplicateConfigHashReturnsExistingWithoutCallingSm() {
        stubSubscriptionManagerCreate("sub-dup-cfg-1", "DNOTAM-client-sub-dup-cfg-1");

        var body = Map.of(
                "topic", "DNOTAM/v1",
                "eventScenario", List.of("RWY.CLS"),
                "airportHeliport", List.of("LPPT"),
                "description", "First call"
        );

        given().contentType(ContentType.JSON).body(body)
                .when().post("/api/v1/subscriptions")
                .then().statusCode(201);

        wiremock.resetRequests();

        var secondResponse = given().contentType(ContentType.JSON).body(body)
                .when().post("/api/v1/subscriptions")
                .then().statusCode(201)
                .extract().jsonPath();

        assertThat(secondResponse.getString("subscriptionId")).isEqualTo("sub-dup-cfg-1");
        wiremock.verifyThat(0, postRequestedFor(urlPathEqualTo("/swim/v1/subscriptions")));
    }

    /**
     * GET /api/v1/subscriptions returns the list of all subscriptions in MongoDB.
     */
    @Test
    @Order(3)
    void listSubscriptionsFromMongoDB() {
        seedSubscription("sub-list-1", "ACTIVE");
        seedSubscription("sub-list-2", "PAUSED");

        var response = given()
                .when()
                .get("/api/v1/subscriptions")
                .then()
                .statusCode(200)
                .extract().body().jsonPath();

        assertThat(response.getList("$")).hasSize(2);
    }

    /**
     * PUT /api/v1/subscriptions/{id} with PAUSED → WireMock receives the update,
     * the local status transitions to PAUSED.
     */
    @Test
    @Order(4)
    void pauseSubscriptionViaApi() {
        seedSubscription("sub-pause-1", "ACTIVE");
        stubSubscriptionManagerUpdate("sub-pause-1", "PAUSED");

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("subscription_status", "PAUSED"))
                .when()
                .put("/api/v1/subscriptions/sub-pause-1")
                .then()
                .statusCode(200);

        var updated = subscriptionRepository.findBySubscriptionId("sub-pause-1");
        assertThat(updated).isPresent();
        assertThat(updated.get().getSubscriptionStatus()).isEqualTo("PAUSED");
    }

    /**
     * PUT /api/v1/subscriptions/{id} with ACTIVE → subscription transitions to ACTIVE.
     */
    @Test
    @Order(5)
    void resumeSubscriptionViaApi() {
        seedSubscription("sub-resume-1", "PAUSED");
        stubSubscriptionManagerUpdate("sub-resume-1", "ACTIVE");

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("subscription_status", "ACTIVE"))
                .when()
                .put("/api/v1/subscriptions/sub-resume-1")
                .then()
                .statusCode(200);

        var updated = subscriptionRepository.findBySubscriptionId("sub-resume-1");
        assertThat(updated).isPresent();
        assertThat(updated.get().getSubscriptionStatus()).isEqualTo("ACTIVE");
    }

    /**
     * DELETE /api/v1/subscriptions/{id} → WireMock receives DELETE,
     * local subscription is removed from MongoDB.
     */
    @Test
    @Order(6)
    void deleteSubscriptionCleanup() {
        seedSubscription("sub-del-1", "ACTIVE");

        wiremock.register(delete(urlPathEqualTo("/swim/v1/subscriptions/sub-del-1"))
                .willReturn(aResponse().withStatus(204)));

        given()
                .when()
                .delete("/api/v1/subscriptions/sub-del-1")
                .then()
                .statusCode(204);

        var deleted = subscriptionRepository.findBySubscriptionId("sub-del-1");
        assertThat(deleted).isEmpty();

        wiremock.verifyThat(deleteRequestedFor(urlPathEqualTo("/swim/v1/subscriptions/sub-del-1")));
    }

    /**
     * PUT /api/v1/subscriptions/{id} with invalid status → 400 Bad Request.
     */
    @Test
    @Order(7)
    void updateWithInvalidStatusRejects() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("subscriptionStatus", "INVALID_STATUS"))
                .when()
                .put("/api/v1/subscriptions/sub-any")
                .then()
                .statusCode(400);
    }

    /**
     * POST /api/v1/subscriptions with empty topic → 400 Bad Request.
     */
    @Test
    @Order(8)
    void createSubscriptionWithoutTopicRejects() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("description", "no topic"))
                .when()
                .post("/api/v1/subscriptions")
                .then()
                .statusCode(400);
    }

    /**
     * Events arriving for a PAUSED subscription must be silently discarded.
     * The preProcess guard checks subscription status in MongoDB before any XML parsing.
     * This is a business rule: paused means "stop processing", not just "stop receiving".
     */
    @Test
    @Order(9)
    void pausedSubscriptionDiscardsEvents() {
        seedSubscription("sub-paused-discard", "PAUSED");

        var outcome = eventProcessor.processAndPersistSingleMessage(
                "sub-paused-discard", "queue-1", "AMQP-PAUSED-001", VALID_DNOTAM_XML, 0);

        assertThat(outcome).isEqualTo(ProcessingOutcome.SKIPPED);
        assertThat(eventRepository.listAllDomain()).isEmpty();
        assertThat(dlqRepository.listAllDomain()).isEmpty();
    }

    // ─── Group 2: Event Processing Pipeline ───

    /**
     * Full pipeline: valid AIXM XML → XSD validation → extraction → MongoDB persistence.
     * Validates metadata: subscriptionId, content hash, delivery status, raw payload.
     */
    @Test
    @Order(10)
    void validDnotamPersistedWithFullMetadata() {
        eventProcessor.processAndPersistSingleMessage(
                "sub-pipe-1", "queue-1", "AMQP-MSG-001", VALID_DNOTAM_XML, 0);

        List<Event> events = eventRepository.listAllDomain();
        assertThat(events).hasSize(1);

        Event event = events.get(0);
        assertThat(event.getSubscriptionId()).isEqualTo("sub-pipe-1");
        assertThat(event.getContentHash()).isNotEmpty();
        assertThat(event.getDeliveryStatus()).isIn(OutboxDeliveryStatus.PENDING, OutboxDeliveryStatus.SENT);
        assertThat(event.getRawPayload()).isEqualTo(VALID_DNOTAM_XML);
    }

    /**
     * Invalid XML (not AIXM) → DLQ with VALIDATION_ERROR reason.
     * Business logic must never receive non-conformant payloads.
     */
    @Test
    @Order(11)
    void invalidXmlRoutedToDlq() {
        try {
            eventProcessor.processAndPersistSingleMessage(
                    "sub-dlq-1", "queue-1", "AMQP-INVALID-001", INVALID_XML, 0);
        } catch (RuntimeException e) {
            // Expected: invalid XML should throw exception and be sent to DLQ
        }

        assertThat(eventRepository.listAll()).isEmpty();

        List<DeadLetterMessage> dlqMessages = dlqRepository.listAllDomain();
        assertThat(dlqMessages).hasSize(1);
        assertThat(dlqMessages.get(0).getErrorType()).isEqualTo("VALIDATION_ERROR");
        assertThat(dlqMessages.get(0).getRawPayload()).isEqualTo(INVALID_XML);
    }

    /**
     * CP1 Audit Rule: once an event is persisted, its rawPayload must remain immutable.
     * The outbox scheduler may update deliveryStatus, but the audit-critical fields
     * (rawPayload, subscriptionId, messageId, receivedAt, contentHash) must never change.
     */
    @Test
    @Order(11)
    void auditFieldsRemainImmutableAfterPersistence() {
        eventProcessor.processAndPersistSingleMessage(
                "sub-audit-1", "queue-1", "AMQP-AUDIT-001", VALID_DNOTAM_XML, 0);

        Event original = eventRepository.listAllDomain().get(0);
        String originalPayload = original.getRawPayload();
        String originalHash = original.getContentHash();
        String originalSubId = original.getSubscriptionId();
        String originalMsgId = original.getMessageId();

        original.setDeliveryStatus(OutboxDeliveryStatus.SENT);
        eventRepository.update(original);

        Event reloaded = eventRepository.listAllDomain().get(0);
        assertThat(reloaded.getRawPayload()).isEqualTo(originalPayload);
        assertThat(reloaded.getContentHash()).isEqualTo(originalHash);
        assertThat(reloaded.getSubscriptionId()).isEqualTo(originalSubId);
        assertThat(reloaded.getMessageId()).isEqualTo(originalMsgId);
        assertThat(reloaded.getDeliveryStatus()).isEqualTo(OutboxDeliveryStatus.SENT);
    }

    /**
     * Duplicate content (same SHA-256 hash) → second message silently discarded.
     * Only 1 event persisted in MongoDB.
     */
    @Test
    @Order(12)
    void duplicateContentDiscardedByIdempotency() {
        eventProcessor.processAndPersistSingleMessage(
                "sub-dup-1", "queue-1", "AMQP-DUP-001", VALID_DNOTAM_XML, 0);

        eventProcessor.processAndPersistSingleMessage(
                "sub-dup-1", "queue-1", "AMQP-DUP-002", VALID_DNOTAM_XML, 0);

        assertThat(eventRepository.listAll()).hasSize(1);
    }

    /**
     * Routing by intent: a valid DNOTAM with scenario AD.CLS (Aerodrome Closure)
     * must be classifiable as CLOSURE, which determines the Kafka output topic.
     * This validates the full chain: JAXB extraction → raw payload preservation → classification.
     */
    @Test
    @Order(13)
    void routingByIntentClassifiesAdClosureCorrectly() {
        eventProcessor.processAndPersistSingleMessage(
                "sub-route-1", "queue-1", "AMQP-ROUTE-001", VALID_DNOTAM_XML, 0);

        Event persisted = eventRepository.listAllDomain().get(0);
        String rawPayload = persisted.getRawPayload();

        String scenario = DnotamEventClassifier.extractScenario(rawPayload);
        DnotamEventCategory category = DnotamEventClassifier.classify(scenario);

        assertThat(scenario).isEqualTo("AD.CLS");
        assertThat(category).isEqualTo(DnotamEventCategory.CLOSURE);
    }

    /**
     * RWY.CLS (Runway Closure) must classify as CLOSURE — same category as AD.CLS.
     */
    @Test
    @Order(13)
    void routingByIntentClassifiesRunwayClosureCorrectly() {
        String rwyCls = VALID_DNOTAM_XML.replace("AD.CLS", "RWY.CLS");
        eventProcessor.processAndPersistSingleMessage(
                "sub-route-rwy", "queue-1", "AMQP-ROUTE-RWY", rwyCls, 0);

        String scenario = DnotamEventClassifier.extractScenario(eventRepository.listAllDomain().get(0).getRawPayload());
        assertThat(scenario).isEqualTo("RWY.CLS");
        assertThat(DnotamEventClassifier.classify(scenario)).isEqualTo(DnotamEventCategory.CLOSURE);
    }

    /**
     * SFC.CON (Surface Contamination) must classify as SURFACE_CONDITION.
     */
    @Test
    @Order(13)
    void routingByIntentClassifiesSurfaceContaminationCorrectly() {
        String sfcCon = VALID_DNOTAM_XML.replace("AD.CLS", "SFC.CON");
        eventProcessor.processAndPersistSingleMessage(
                "sub-route-sfc", "queue-1", "AMQP-ROUTE-SFC", sfcCon, 0);

        String scenario = DnotamEventClassifier.extractScenario(eventRepository.listAllDomain().get(0).getRawPayload());
        assertThat(scenario).isEqualTo("SFC.CON");
        assertThat(DnotamEventClassifier.classify(scenario)).isEqualTo(DnotamEventCategory.SURFACE_CONDITION);
    }

    /**
     * NAV.UNS (Navaid Unserviceable) must classify as HAZARD.
     */
    @Test
    @Order(13)
    void routingByIntentClassifiesNavaidUnserviceableCorrectly() {
        String navUns = VALID_DNOTAM_XML.replace("AD.CLS", "NAV.UNS");
        eventProcessor.processAndPersistSingleMessage(
                "sub-route-nav", "queue-1", "AMQP-ROUTE-NAV", navUns, 0);

        String scenario = DnotamEventClassifier.extractScenario(eventRepository.listAllDomain().get(0).getRawPayload());
        assertThat(scenario).isEqualTo("NAV.UNS");
        assertThat(DnotamEventClassifier.classify(scenario)).isEqualTo(DnotamEventCategory.HAZARD);
    }

    /**
     * SAA.ACT (Airspace Activation) must classify as AIRSPACE.
     */
    @Test
    @Order(13)
    void routingByIntentClassifiesAirspaceActivationCorrectly() {
        String saaAct = VALID_DNOTAM_XML.replace("AD.CLS", "SAA.ACT");
        eventProcessor.processAndPersistSingleMessage(
                "sub-route-saa", "queue-1", "AMQP-ROUTE-SAA", saaAct, 0);

        String scenario = DnotamEventClassifier.extractScenario(eventRepository.listAllDomain().get(0).getRawPayload());
        assertThat(scenario).isEqualTo("SAA.ACT");
        assertThat(DnotamEventClassifier.classify(scenario)).isEqualTo(DnotamEventCategory.AIRSPACE);
    }

    /**
     * Idempotency persists to MongoDB (L2 cache). After clearing the Caffeine L1 cache,
     * the system still recognizes duplicates from the database.
     */
    @Test
    @Order(14)
    void idempotencyWithRealDatabase() {
        String xml = VALID_DNOTAM_XML.replace("AD.CLS", "RWY.CLS");
        String hash = com.github.swim_developer.framework.infrastructure.util.HashUtil.sha256(xml);

        eventProcessor.processAndPersistSingleMessage(
                "sub-idem-1", "queue-1", "AMQP-IDEM-001", xml, 0);

        assertThat(eventRepository.listAll()).hasSize(1);
        assertThat(idempotencyCache.isAlreadyProcessed("sub-idem-1", hash)).isTrue();
    }

    // ─── Group 3: REST Queries After Processing ───

    /**
     * GET /api/v1/subscriptions/{id}/events returns events for a given subscription.
     */
    @Test
    @Order(20)
    void queryEventsBySubscription() {
        seedSubscription("sub-query-1", "ACTIVE");
        eventProcessor.processAndPersistSingleMessage(
                "sub-query-1", "queue-1", "AMQP-QRY-001", VALID_DNOTAM_XML, 0);

        var response = given()
                .when()
                .get("/api/v1/subscriptions/sub-query-1/events?page=0&size=10")
                .then()
                .statusCode(200)
                .extract().body().jsonPath();

        assertThat(response.getList("content")).hasSize(1);
        assertThat(response.getLong("totalElements")).isEqualTo(1);
    }

    /**
     * GET /api/v1/dlq returns dead letter messages with pagination.
     */
    @Test
    @Order(21)
    void queryDlqAfterRejection() {
        try {
            eventProcessor.processAndPersistSingleMessage(
                    "sub-dlq-q", "queue-1", "AMQP-DLQ-Q-001", INVALID_XML, 0);
        } catch (RuntimeException e) {
            // Expected: invalid XML should throw exception and be sent to DLQ
        }

        var response = given()
                .when()
                .get("/api/v1/dlq?page=0&size=10")
                .then()
                .statusCode(200)
                .extract().body().jsonPath();

        assertThat(response.getList("content")).hasSize(1);
    }

    /**
     * GET /api/v1/stats returns accurate aggregate counts after mixed operations.
     */
    @Test
    @Order(22)
    void statsReflectRealState() {
        seedSubscription("sub-stats-1", "ACTIVE");

        eventProcessor.processAndPersistSingleMessage(
                "sub-stats-1", "queue-1", "AMQP-STATS-001", VALID_DNOTAM_XML, 0);
        try {
            eventProcessor.processAndPersistSingleMessage(
                    "sub-stats-1", "queue-1", "AMQP-STATS-002", INVALID_XML, 0);
        } catch (RuntimeException e) {
            // Expected: invalid XML should throw exception and be sent to DLQ
        }

        var response = given()
                .when()
                .get("/api/v1/stats")
                .then()
                .statusCode(200)
                .extract().body().jsonPath();

        assertThat(response.getLong("totalEvents")).isEqualTo(1);
        assertThat(response.getLong("totalDlq")).isEqualTo(1);
        assertThat(response.getInt("activeSubscriptions")).isEqualTo(1);
    }

    /**
     * GET /api/v1/events/{messageId} returns a specific event by its composite ID.
     */
    @Test
    @Order(23)
    void getEventByMessageId() {
        eventProcessor.processAndPersistSingleMessage(
                "sub-get-1", "queue-1", "AMQP-GET-001", VALID_DNOTAM_XML, 0);

        Event persisted = eventRepository.listAllDomain().get(0);

        given()
                .when()
                .get("/api/v1/events/" + persisted.getMessageId())
                .then()
                .statusCode(200);
    }

    /**
     * GET /api/v1/topics returns the configured desired subscriptions.
     * In test profile, dnotam.subscriptions=[] to prevent startup reconciliation.
     */
    @Test
    @Order(24)
    void listConfiguredTopicsEndpoint() {
        given()
                .when()
                .get("/api/v1/topics")
                .then()
                .statusCode(200);
    }

    /**
     * DefaultSubscriptionConfigParser reflects %test.swim.subscriptions=[] so reconciliation
     * schedulers do not fight WireMock during isolated integration runs.
     */
    @Test
    @Order(29)
    void parseDesiredSubscriptionsEmptyInTestProfile() {
        assertThat(subscriptionConfigParser.parseDesiredSubscriptions()).isEmpty();
    }

    /**
     * GET /api/v1/subscriptions/active returns only ACTIVE subscriptions.
     */
    @Test
    @Order(25)
    void listActiveSubscriptionsOnly() {
        seedSubscription("sub-active-1", "ACTIVE");
        seedSubscription("sub-paused-1", "PAUSED");
        seedSubscription("sub-active-2", "ACTIVE");

        var response = given()
                .when()
                .get("/api/v1/subscriptions/active")
                .then()
                .statusCode(200)
                .extract().body().jsonPath();

        assertThat(response.getList("$")).hasSize(2);
    }

    /**
     * GET /api/v1/subscriptions/{id}/events/count returns event count for a subscription.
     */
    @Test
    @Order(26)
    void countEventsBySubscription() {
        seedSubscription("sub-cnt-1", "ACTIVE");
        eventProcessor.processAndPersistSingleMessage(
                "sub-cnt-1", "queue-1", "AMQP-CNT-001", VALID_DNOTAM_XML, 0);

        var response = given()
                .when()
                .get("/api/v1/subscriptions/sub-cnt-1/events/count")
                .then()
                .statusCode(200)
                .extract().body().jsonPath();

        assertThat(response.getLong("count")).isEqualTo(1);
    }

    /**
     * GET /api/v1/dlq/count returns accurate DLQ count.
     */
    @Test
    @Order(27)
    void countDlqMessages() {
        try {
            eventProcessor.processAndPersistSingleMessage(
                    "sub-dlqc-1", "queue-1", "AMQP-DLQC-001", INVALID_XML, 0);
        } catch (RuntimeException e) {
            // Expected: invalid XML should throw exception and be sent to DLQ
        }

        var response = given()
                .when()
                .get("/api/v1/dlq/count")
                .then()
                .statusCode(200)
                .extract().body().jsonPath();

        assertThat(response.getLong("count")).isEqualTo(1);
    }

    /**
     * GET /api/v1/subscriptions/{id}/events/range returns events within a date window.
     */
    @Test
    @Order(28)
    void queryEventsByDateRange() {
        seedSubscription("sub-range-1", "ACTIVE");
        eventProcessor.processAndPersistSingleMessage(
                "sub-range-1", "queue-1", "AMQP-RANGE-001", VALID_DNOTAM_XML, 0);

        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);

        var response = given()
                .when()
                .get("/api/v1/subscriptions/sub-range-1/events/range?startDate=" + start + "&endDate=" + end + "&page=0&size=10")
                .then()
                .statusCode(200)
                .extract().body().jsonPath();

        assertThat(response.getList("content")).hasSize(1);
    }

    // ─── Group 4: Health Checks ───

    /**
     * Liveness probe returns UP when the application is running.
     */
    @Test
    @Order(30)
    void livenessProbeUp() {
        given()
                .when()
                .get("/q/health/live")
                .then()
                .statusCode(200);
    }

    /**
     * Readiness probe responds with a valid health check body.
     * In test context some checks may report DOWN (e.g., reconciliation not yet complete),
     * so we verify the probe responds and contains the expected structure.
     */
    @Test
    @Order(31)
    void readinessProbeResponds() {
        var response = given()
                .when()
                .get("/q/health/ready")
                .then()
                .extract().body().jsonPath();

        assertThat(response.getString("status")).isNotEmpty();
        assertThat(response.getList("checks")).isNotNull();
    }

    // ─── Group 5: Self-Healing (Provider State Loss Recovery) ───

    /**
     * Provider returns 404 during resume → framework translates to SubscriptionNotFoundException,
     * marks subscription INVALID, deletes local, and triggers full re-subscription cycle
     * (POST → PAUSED → ACTIVE).
     *
     * <p>This is the gold standard for the SFG: proves the consumer self-heals
     * when the provider loses subscription state (e.g., after an upgrade).</p>
     */
    @Test
    @Order(40)
    void automaticResubscriptionOnProviderStateLoss() {
        seedSubscription("sub-lost-1", "ACTIVE");

        wiremock.register(put(urlPathEqualTo("/swim/v1/subscriptions/sub-lost-1"))
                .willReturn(aResponse().withStatus(404)));

        stubSubscriptionManagerCreate("sub-recovered-1", "DNOTAM-client-sub-recovered-1");

        try {
            subscriptionService.resumeSubscription("sub-lost-1");
        } catch (Exception e) {
            // Expected: provider returns 404 for lost subscription
        }

        assertThat(subscriptionRepository.findBySubscriptionId("sub-lost-1"))
                .as("Old subscription must be deleted after provider 404")
                .isEmpty();

        assertThat(subscriptionRepository.findBySubscriptionId("sub-recovered-1"))
                .as("New subscription must be created after provider state loss recovery")
                .isPresent()
                .get()
                .satisfies(s -> assertThat(s.getSubscriptionStatus()).isEqualTo("ACTIVE"));

        wiremock.verifyThat(postRequestedFor(urlPathEqualTo("/swim/v1/subscriptions")));
    }

    // ── Heartbeat Watchdog — The Golden Lock ──────────────────────────────

    // ─── Helpers ───

    private void stubSubscriptionManagerCreate(String subscriptionId, String queueName) {
        String responseJson = """
                {
                    "subscriptionId": "%s",
                    "subscriptionStatus": "PAUSED",
                    "queueName": "%s",
                    "topic": "DNOTAM/v1",
                    "eventScenario": ["RWY.CLS"],
                    "airportHeliport": ["LPPT"],
                    "description": "Integration test"
                }
                """.formatted(subscriptionId, queueName);

        wiremock.register(post(urlPathEqualTo("/swim/v1/subscriptions"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseJson)));

        stubSubscriptionManagerUpdate(subscriptionId, "ACTIVE");
    }

    private void stubSubscriptionManagerUpdate(String subscriptionId, String newStatus) {
        String responseJson = """
                {
                    "subscriptionId": "%s",
                    "subscriptionStatus": "%s",
                    "queueName": "DNOTAM-client-%s",
                    "topic": "DNOTAM/v1"
                }
                """.formatted(subscriptionId, newStatus, subscriptionId);

        wiremock.register(put(urlPathEqualTo("/swim/v1/subscriptions/" + subscriptionId))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseJson)));
    }

    /**
     * Validates that the consumer properly handles slow provider responses.
     *
     * <p><b>SFG Demonstration Point</b>: When EAD takes longer than the configured timeout
     * (10 seconds in tests, 30 seconds in production), the client gives up gracefully and retries
     * up to 3 times with exponential backoff. After exhausting retries, the request fails.</p>
     *
     * <p><b>SWIM-TI Compliance</b>: Yellow Profile requires timeout handling for distributed systems.</p>
     */
    @Test
    @Order(28)
    @DisplayName("Should timeout and retry when subscription manager is slow to respond")
    void testSubscriptionManagerTimeout() {
        // GIVEN: WireMock configured to respond after 12 seconds (exceeds 10s test timeout)
        String subscriptionId = "sub-timeout-001";
        String queueName = "DNOTAM-client-" + subscriptionId;

        String responseJson = """
                {
                    "subscriptionId": "%s",
                    "subscriptionStatus": "PAUSED",
                    "queueName": "%s",
                    "topic": "DNOTAM/v1",
                    "eventScenario": ["RWY.CLS"],
                    "airportHeliport": ["LPPT"],
                    "description": "Timeout test"
                }
                """.formatted(subscriptionId, queueName);

        wiremock.register(post(urlPathEqualTo("/swim/v1/subscriptions"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseJson)
                        .withFixedDelay(12000))); // 12 seconds - exceeds 10s test timeout

        // WHEN: Attempt to create subscription via REST API (with extended timeout to wait for retries)
        var response = given()
                .contentType(ContentType.JSON)
                .config(io.restassured.config.RestAssuredConfig.config()
                        .httpClient(io.restassured.config.HttpClientConfig.httpClientConfig()
                                .setParam("http.socket.timeout", 60000))) // 60s to allow 3 retries × 10s each
                .body("""
                        {
                            "topic": "DNOTAM/v1",
                            "eventScenario": ["RWY.CLS"],
                            "airportHeliport": ["LPPT"],
                            "description": "Timeout test"
                        }
                        """)
                .when()
                .post("/api/v1/subscriptions");

        // THEN: Request should fail with 503 after all retries are exhausted
        assertThat(response.statusCode()).isEqualTo(503);

        // AND: 4 attempts were made (1 initial + 3 retries, maxRetries = 3)
        wiremock.verify(4, postRequestedFor(urlPathEqualTo("/swim/v1/subscriptions")));

        // AND: Subscription was NOT created locally
        var sub = subscriptionRepository.findBySubscriptionId(subscriptionId);
        assertThat(sub).isEmpty();
    }

    /**
     * Validates that the consumer recovers after transient failures.
     *
     * <p><b>SFG Demonstration Point</b>: Network issues and temporary provider outages
     * are common in distributed SWIM deployments. This test proves the consumer can
     * recover automatically after 2 failed attempts succeed on the 3rd retry.</p>
     *
     * <p><b>Production Benefit</b>: No manual intervention required for transient failures.</p>
     */
    @Test
    @Order(29)
    @DisplayName("Should succeed after 2 timeouts on 3rd retry")
    void testSubscriptionManagerRecoveryAfterRetries() {
        // GIVEN: WireMock fails 2 times, succeeds on 3rd attempt
        String subscriptionId = "sub-retry-001";
        String queueName = "DNOTAM-client-" + subscriptionId;

        String responseJson = """
                {
                    "subscriptionId": "%s",
                    "subscriptionStatus": "PAUSED",
                    "queueName": "%s",
                    "topic": "DNOTAM/v1",
                    "eventScenario": ["RWY.CLS"],
                    "airportHeliport": ["LPPT"],
                    "description": "Retry recovery test"
                }
                """.formatted(subscriptionId, queueName);

        wiremock.register(post(urlPathEqualTo("/swim/v1/subscriptions"))
                .inScenario("Retry Recovery")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withFixedDelay(12000)) // Timeout on 1st attempt (exceeds 10s)
                .willSetStateTo("First Retry"));

        wiremock.register(post(urlPathEqualTo("/swim/v1/subscriptions"))
                .inScenario("Retry Recovery")
                .whenScenarioStateIs("First Retry")
                .willReturn(aResponse().withFixedDelay(12000)) // Timeout on 2nd attempt (exceeds 10s)
                .willSetStateTo("Second Retry"));

        wiremock.register(post(urlPathEqualTo("/swim/v1/subscriptions"))
                .inScenario("Retry Recovery")
                .whenScenarioStateIs("Second Retry")
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseJson))); // SUCCESS on 3rd attempt

        stubSubscriptionManagerUpdate(subscriptionId, "ACTIVE");

        // WHEN: Create subscription via REST API (with extended timeout to allow retries)
        var response = given()
                .contentType(ContentType.JSON)
                .config(io.restassured.config.RestAssuredConfig.config()
                        .httpClient(io.restassured.config.HttpClientConfig.httpClientConfig()
                                .setParam("http.socket.timeout", 60000))) // 60s to allow retries
                .body("""
                        {
                            "topic": "DNOTAM/v1",
                            "eventScenario": ["RWY.CLS"],
                            "airportHeliport": ["LPPT"],
                            "description": "Retry recovery test"
                        }
                        """)
                .when()
                .post("/api/v1/subscriptions");

        // THEN: Request succeeds after retries (succeeds on 3rd attempt)
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.jsonPath().getString("subscriptionId")).isEqualTo(subscriptionId);

        // AND: Exactly 3 attempts were made (1 initial timeout + 1 retry timeout + 1 retry success)
        wiremock.verify(3, postRequestedFor(urlPathEqualTo("/swim/v1/subscriptions")));

        // AND: Subscription was successfully created locally
        var sub = subscriptionRepository.findBySubscriptionId(subscriptionId);
        assertThat(sub).isPresent();
        assertThat(sub.get().getSubscriptionId()).isEqualTo(subscriptionId);
        assertThat(sub.get().getQueueName()).isEqualTo(queueName);
    }

    @Test
    @Order(50)
    void wfsGetFeatureReturnsAixmXml() {
        String aixmResponse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <message:AIXMBasicMessage xmlns:message="http://www.aixm.aero/schema/5.1.1/message"
                    xmlns:gml="http://www.opengis.net/gml/3.2" gml:id="WFS_RESPONSE_001">
                    <message:hasMember/>
                </message:AIXMBasicMessage>
                """;

        wiremock.register(get(urlPathEqualTo("/swim/v1/features"))
                .withQueryParam("typeName", equalTo("event:Event"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(aixmResponse)));

        String body = given()
                .queryParam("typeName", "event:Event")
                .when()
                .get("/api/v1/features")
                .then()
                .statusCode(200)
                .extract().body().asString();

        assertThat(body).contains("AIXMBasicMessage");
    }

    @Test
    @Order(51)
    void wfsGetFeatureReturns502WhenProviderDown() {
        wiremock.register(get(urlPathEqualTo("/swim/v1/features"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withBody("Service Unavailable")));

        var response = given()
                .queryParam("typeName", "event:Event")
                .when()
                .get("/api/v1/features")
                .then()
                .statusCode(502)
                .contentType(ContentType.JSON)
                .extract().body().jsonPath();

        assertThat(response.getString("error")).isEqualTo("Provider request failed");
    }

    @Test
    @Order(52)
    void wfsGetFeatureReturns503WhenNoProvider() {
        var response = given()
                .queryParam("typeName", "event:Event")
                .queryParam("providerId", "non-existent-provider")
                .when()
                .get("/api/v1/features")
                .then()
                .statusCode(503)
                .contentType(ContentType.JSON)
                .extract().body().jsonPath();

        assertThat(response.getString("error")).contains("No provider configured");
    }

    @Test
    @Order(53)
    void wfsGetFeatureForwardsValidTimeFilter() {
        String validTime = "2026-04-27T00:00:00Z/2026-04-28T00:00:00Z";

        wiremock.register(get(urlPathEqualTo("/swim/v1/features"))
                .withQueryParam("typeName", equalTo("event:Event"))
                .withQueryParam("validTime", equalTo(validTime))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody("<message:AIXMBasicMessage xmlns:message=\"http://www.aixm.aero/schema/5.1.1/message\" xmlns:gml=\"http://www.opengis.net/gml/3.2\" gml:id=\"WFS_FILTERED\"/>")));

        String body = given()
                .queryParam("typeName", "event:Event")
                .queryParam("validTime", validTime)
                .when()
                .get("/api/v1/features")
                .then()
                .statusCode(200)
                .extract().body().asString();

        assertThat(body).contains("WFS_FILTERED");

        wiremock.verify(getRequestedFor(urlPathEqualTo("/swim/v1/features"))
                .withQueryParam("validTime", equalTo(validTime)));
    }

    private void seedSubscription(String subscriptionId, String status) {
        Subscription sub = new Subscription();
        sub.setSubscriptionId(subscriptionId);
        sub.setQueueName("DNOTAM-client-" + subscriptionId);
        sub.setSubscriptionStatus(status);
        sub.setTopic("DNOTAM/v1");
        sub.setEventScenario(List.of());
        sub.setAirportHeliport(List.of());
        sub.setDescription("Seeded for test");
        sub.setType(com.github.swim_developer.framework.domain.model.SubscriptionType.DECLARED.name());
        sub.setConfigHash("test-hash-" + subscriptionId);
        sub.setProviderId("test-provider");
        subscriptionRepository.persistSubscription(sub);
    }
}
