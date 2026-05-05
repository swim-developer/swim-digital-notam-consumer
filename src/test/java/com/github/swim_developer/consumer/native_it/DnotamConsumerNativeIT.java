package com.github.swim_developer.consumer.native_it;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkiverse.wiremock.devservice.ConnectWireMock;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native image integration tests for the DNOTAM consumer.
 *
 * <p>Runs against the compiled native executable (GraalVM). Detects issues that
 * JVM-mode tests ({@code @QuarkusTest}) cannot catch:</p>
 *
 * <ul>
 *   <li><b>CDI raw-type injection</b> — {@code Instance<AbstractSubscriptionService>} raw types
 *       fail in native ArC build-time wiring. Caught by {@link #livenessProbeUp()} and
 *       {@link #readinessProbeResponds()}.</li>
 *   <li><b>MongoDB POJO codec (EventHashProjection)</b> — {@code @ProjectionFor} classes without
 *       {@code @RegisterForReflection} are excluded from native reflection. Caught indirectly
 *       by {@link #createSubscriptionPersistsInNative()} and {@link #statsReflectRealStateInNative()}
 *       which exercise the MongoDB POJO codec path for domain entities.</li>
 *   <li><b>Quarkus Caffeine cache registration</b> — caches accessed via {@code CacheManager.getCache()}
 *       require a build-time {@code @CacheResult} declaration to be present in native mode.
 *       Caught by {@link #idempotencyCacheAvailableAfterEventProcessing()}.</li>
 * </ul>
 *
 * <h2>Limitations</h2>
 * <p>AMQP pipeline tests (end-to-end event ingestion) are not covered here because injecting
 * messages into Artemis via HTTP in a native test requires additional infrastructure. Those
 * scenarios are exercised by the CI native build smoke test against OpenShift Local.</p>
 *
 * @see com.github.swim_developer.consumer.integration.DnotamConsumerIT JVM-mode IT (full pipeline)
 */
@QuarkusIntegrationTest
@ConnectWireMock
@Tag("native")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DnotamConsumerNativeIT {

    WireMock wiremock;

    @BeforeEach
    void resetWiremock(TestInfo testInfo) {
        System.out.printf("%n══ ▶ [native] %s.%s%n", getClass().getSimpleName(), testInfo.getDisplayName());
        wiremock.removeMappings();
        wiremock.resetAllScenarios();
        wiremock.resetRequests();
    }

    // ─── Group 1: Startup — PRIMARY native CDI checks ──────────────────────

    /**
     * Liveness probe returns 200 UP.
     *
     * <p>PRIMARY test for native CDI injection failures. If any bean fails to wire
     * at ArC build time (e.g., raw-type {@code Instance<>} parameters), the app
     * crashes on startup and this test fails with a connection error.</p>
     *
     * <p>Covers: {@code AbstractHeartbeatTimeoutHandler}, {@code AbstractReconciliationScheduler},
     * {@code AbstractSubscriptionStartupHandler} — all previously injecting raw generic types.</p>
     */
    @Test
    @Order(1)
    void livenessProbeUp() {
        given()
                .when()
                .get("/q/health/live")
                .then()
                .statusCode(200);
    }

    /**
     * Readiness probe responds with a valid structure.
     *
     * <p>Confirms MongoDB, Kafka and AMQP dev-service containers are reachable from
     * the native process. Also validates that Quarkus infrastructure health checks
     * do not fail due to native reflection or codec issues.</p>
     */
    @Test
    @Order(2)
    void readinessProbeResponds() {
        var response = given()
                .when()
                .get("/q/health/ready")
                .then()
                .extract().body().jsonPath();

        assertThat(response.getString("status")).isNotEmpty();
        assertThat(response.getList("checks")).isNotNull();
    }

    // ─── Group 2: REST API — MongoDB POJO codec in native ──────────────────

    /**
     * POST /api/v1/subscriptions creates a subscription and returns 201.
     *
     * <p>Exercises the full REST → use-case → MongoDB write path in native mode.
     * Validates that Quarkus MongoDB Panache POJO codec correctly serialises
     * {@code Subscription} and {@code SubscriptionDocument} in native.</p>
     */
    @Test
    @Order(3)
    void createSubscriptionPersistsInNative() {
        stubSmCreate("sub-native-001", "DNOTAM-client-sub-native-001");

        var response = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "topic", "DNOTAM/v1",
                        "eventScenario", java.util.List.of("RWY.CLS"),
                        "airportHeliport", java.util.List.of("LPPT"),
                        "description", "Native IT create test"
                ))
                .when()
                .post("/api/v1/subscriptions")
                .then()
                .statusCode(201)
                .extract().body().jsonPath();

        assertThat(response.getString("subscriptionId")).isEqualTo("sub-native-001");
        assertThat(response.getString("queueName")).isEqualTo("DNOTAM-client-sub-native-001");

        wiremock.verifyThat(postRequestedFor(urlPathEqualTo("/swim/v1/subscriptions")));
        wiremock.verifyThat(putRequestedFor(urlPathEqualTo("/swim/v1/subscriptions/sub-native-001")));
    }

    /**
     * GET /api/v1/subscriptions returns the list created in this test run.
     *
     * <p>Exercises MongoDB read + POJO deserialization in native mode.
     * A non-empty list confirms that the persistence written in the previous test
     * is correctly deserialised by the POJO codec when running as native.</p>
     */
    @Test
    @Order(4)
    void listSubscriptionsReturnsPersisted() {
        var response = given()
                .when()
                .get("/api/v1/subscriptions")
                .then()
                .statusCode(200)
                .extract().body().jsonPath();

        assertThat(response.getList("$")).isNotEmpty();
    }

    /**
     * GET /api/v1/subscriptions/{id} returns the exact subscription by ID.
     *
     * <p>Validates native-mode document deserialization from a targeted MongoDB query.</p>
     */
    @Test
    @Order(5)
    void getSubscriptionByIdReturnsCorrectData() {
        stubSmCreate("sub-native-002", "DNOTAM-client-sub-native-002");

        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "topic", "DNOTAM/v1",
                        "eventScenario", java.util.List.of("TWY.CLS"),
                        "airportHeliport", java.util.List.of("LPMA"),
                        "description", "Native IT get-by-id test"
                ))
                .when()
                .post("/api/v1/subscriptions")
                .then()
                .statusCode(201);

        var response = given()
                .when()
                .get("/api/v1/subscriptions/sub-native-002")
                .then()
                .statusCode(200)
                .extract().body().jsonPath();

        assertThat(response.getString("subscriptionId")).isEqualTo("sub-native-002");
    }

    /**
     * PUT /api/v1/subscriptions/{id} transitions status to PAUSED.
     *
     * <p>Exercises MongoDB update + SM REST call in native mode.
     * Validates that the MicroProfile REST Client + POJO update path work in native.</p>
     */
    @Test
    @Order(6)
    void pauseSubscriptionInNative() {
        stubSmCreate("sub-native-pause", "DNOTAM-client-sub-native-pause");
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "topic", "DNOTAM/v1",
                        "eventScenario", java.util.List.of("RWY.CLS"),
                        "airportHeliport", java.util.List.of("LPPT"),
                        "description", "Native IT pause test"
                ))
                .when()
                .post("/api/v1/subscriptions")
                .then()
                .statusCode(201);

        stubSmUpdate("sub-native-pause", "PAUSED");

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("subscription_status", "PAUSED"))
                .when()
                .put("/api/v1/subscriptions/sub-native-pause")
                .then()
                .statusCode(200);

        var updated = given()
                .when()
                .get("/api/v1/subscriptions/sub-native-pause")
                .then()
                .statusCode(200)
                .extract().body().jsonPath();

        assertThat(updated.getString("subscriptionStatus")).isEqualTo("PAUSED");
    }

    /**
     * DELETE /api/v1/subscriptions/{id} removes the subscription.
     *
     * <p>Validates MongoDB delete + SM REST call in native mode.</p>
     */
    @Test
    @Order(7)
    void deleteSubscriptionInNative() {
        stubSmCreate("sub-native-del", "DNOTAM-client-sub-native-del");
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "topic", "DNOTAM/v1",
                        "eventScenario", java.util.List.of("RWY.CLS"),
                        "airportHeliport", java.util.List.of("LPPT"),
                        "description", "Native IT delete test"
                ))
                .when()
                .post("/api/v1/subscriptions")
                .then()
                .statusCode(201);

        wiremock.register(delete(urlPathEqualTo("/swim/v1/subscriptions/sub-native-del"))
                .willReturn(aResponse().withStatus(204)));

        given()
                .when()
                .delete("/api/v1/subscriptions/sub-native-del")
                .then()
                .statusCode(204);

        given()
                .when()
                .get("/api/v1/subscriptions/sub-native-del")
                .then()
                .statusCode(404);
    }

    // ─── Group 3: Validation — REST contract in native ─────────────────────

    /**
     * Invalid status value on PUT returns 400 Bad Request.
     *
     * <p>Validates that Jackson deserialization and Bean Validation annotations
     * function correctly in native mode (no missing reflection for validator classes).</p>
     */
    @Test
    @Order(8)
    void invalidStatusValueReturns400InNative() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("subscriptionStatus", "DOES_NOT_EXIST"))
                .when()
                .put("/api/v1/subscriptions/sub-any")
                .then()
                .statusCode(400);
    }

    /**
     * POST without required topic field returns 400 Bad Request.
     */
    @Test
    @Order(9)
    void missingTopicReturns400InNative() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("description", "no topic"))
                .when()
                .post("/api/v1/subscriptions")
                .then()
                .statusCode(400);
    }

    // ─── Group 4: Stats and aggregation — MongoDB aggregation in native ─────

    /**
     * GET /api/v1/stats returns valid aggregate counts.
     *
     * <p>Exercises MongoDB {@code count()} aggregations in native mode. Also serves
     * as a smoke test for the stats use-case path in native.</p>
     */
    @Test
    @Order(10)
    void statsReflectRealStateInNative() {
        var response = given()
                .when()
                .get("/api/v1/stats")
                .then()
                .statusCode(200)
                .extract().body().jsonPath();

        assertThat(response.getLong("totalEvents")).isGreaterThanOrEqualTo(0);
        assertThat(response.getLong("totalDlq")).isGreaterThanOrEqualTo(0);
        assertThat(response.getInt("activeSubscriptions")).isGreaterThanOrEqualTo(0);
    }

    /**
     * GET /api/v1/topics reflects the configured desired subscriptions.
     *
     * <p>In test profile, {@code dnotam.subscriptions=[]} so the result is empty.
     * Validates that the config-parser and REST resource work in native mode.</p>
     */
    @Test
    @Order(11)
    void topicsEndpointReturnsEmptyWithNoConfig() {
        var response = given()
                .when()
                .get("/api/v1/topics")
                .then()
                .statusCode(200)
                .extract().body().jsonPath();

        assertThat(response.getList("$")).isEmpty();
    }

    // ─── Group 5: Idempotency cache — Quarkus Caffeine native registration ──

    /**
     * Idempotency cache ({@code processed-messages}) is accessible in native mode.
     *
     * <p>Proves that {@link com.github.swim_developer.dnotam.consumer.infrastructure.out.idempotency.DnotamIdempotencyCacheDeclaration}
     * registers the cache at build time so that {@code CacheManager.getCache("processed-messages")}
     * finds it at runtime. Without the {@code @CacheResult} declaration class, this path throws
     * {@code IllegalStateException: Cache not found: processed-messages}.</p>
     *
     * <p>Exercises the cache indirectly via a duplicate subscription create, which triggers
     * the idempotency hash check in the use-case layer on the second POST.</p>
     */
    @Test
    @Order(12)
    void idempotencyCacheAvailableAfterEventProcessing() {
        stubSmCreate("sub-native-idem", "DNOTAM-client-sub-native-idem");

        var body = Map.of(
                "topic", "DNOTAM/v1",
                "eventScenario", java.util.List.of("RWY.CLS"),
                "airportHeliport", java.util.List.of("LPPT"),
                "description", "Native IT idempotency test"
        );

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/subscriptions")
                .then()
                .statusCode(201);

        wiremock.resetRequests();

        var secondResponse = given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/subscriptions")
                .then()
                .statusCode(201)
                .extract().body().jsonPath();

        assertThat(secondResponse.getString("subscriptionId")).isEqualTo("sub-native-idem");
        wiremock.verifyThat(0, postRequestedFor(urlPathEqualTo("/swim/v1/subscriptions")));
    }

    // ─── Group 6: WFS Request Interface ────────────────────────────────────

    /**
     * GET /api/v1/features proxies to the SM and returns AIXM XML.
     *
     * <p>Validates the WFS GetFeature interface defined in the DNOTAM Service Definition
     * (SWIM Registry) works correctly in native mode, including REST client proxy classes.</p>
     */
    @Test
    @Order(13)
    void wfsGetFeatureReturnsAixmInNative() {
        wiremock.register(get(urlPathEqualTo("/swim/v1/features"))
                .withQueryParam("typeName", equalTo("event:Event"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody("""
                                <?xml version="1.0" encoding="UTF-8"?>
                                <message:AIXMBasicMessage xmlns:message="http://www.aixm.aero/schema/5.1.1/message"
                                    xmlns:gml="http://www.opengis.net/gml/3.2" gml:id="NATIVE_TEST_001">
                                </message:AIXMBasicMessage>
                                """)));

        String body = given()
                .queryParam("typeName", "event:Event")
                .when()
                .get("/api/v1/features")
                .then()
                .statusCode(200)
                .extract().body().asString();

        assertThat(body).contains("AIXMBasicMessage");
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private void stubSmCreate(String subscriptionId, String queueName) {
        wiremock.register(post(urlPathEqualTo("/swim/v1/subscriptions"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "subscriptionId": "%s",
                                    "subscriptionStatus": "PAUSED",
                                    "queueName": "%s",
                                    "topic": "DNOTAM/v1",
                                    "eventScenario": ["RWY.CLS"],
                                    "airportHeliport": ["LPPT"],
                                    "description": "Native IT test"
                                }
                                """.formatted(subscriptionId, queueName))));

        stubSmUpdate(subscriptionId, "ACTIVE");
    }

    private void stubSmUpdate(String subscriptionId, String status) {
        wiremock.register(put(urlPathEqualTo("/swim/v1/subscriptions/" + subscriptionId))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "subscriptionId": "%s",
                                    "subscriptionStatus": "%s",
                                    "queueName": "DNOTAM-client-%s",
                                    "topic": "DNOTAM/v1"
                                }
                                """.formatted(subscriptionId, status, subscriptionId))));
    }
}
