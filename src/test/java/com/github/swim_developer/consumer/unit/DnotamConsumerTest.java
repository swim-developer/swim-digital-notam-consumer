package com.github.swim_developer.consumer.unit;

import aero.aixm.message.AIXMBasicMessageType;
import com.github.swim_developer.dnotam.consumer.domain.model.EventData;
import com.github.swim_developer.dnotam.consumer.domain.model.FilterDimension;
import com.github.swim_developer.framework.application.model.ProcessingOutcome;
import com.github.swim_developer.framework.application.model.OutboxDeliveryStatus;
import com.github.swim_developer.framework.application.port.in.SwimMessageInterceptor;
import com.github.swim_developer.framework.consumer.application.messaging.outbox.OutboxRouterFanOut;
import jakarta.enterprise.inject.Instance;
import com.github.swim_developer.framework.consumer.infrastructure.out.filter.SubscriptionFilterCache;
import com.github.swim_developer.framework.domain.exception.XmlValidationException;
import com.github.swim_developer.framework.infrastructure.util.HashUtil;
import com.github.swim_developer.dnotam.consumer.domain.model.Event;
import com.github.swim_developer.dnotam.consumer.infrastructure.out.persistence.MongoEventStore;
import com.github.swim_developer.framework.consumer.infrastructure.out.dlq.AbstractDeadLetterService;
import com.github.swim_developer.dnotam.consumer.infrastructure.in.rest.dto.SubscriptionRequest;
import com.github.swim_developer.dnotam.consumer.infrastructure.out.xml.DnotamJaxbUnmarshallerPool;
import com.github.swim_developer.dnotam.consumer.infrastructure.out.xml.DnotamEventExtractor;
import com.github.swim_developer.dnotam.consumer.infrastructure.out.xml.DnotamXmlEnvelopeParser;
import com.github.swim_developer.dnotam.consumer.application.metrics.DnotamProcessingMetrics;
import com.github.swim_developer.dnotam.consumer.application.service.DnotamEventDataValidator;
import com.github.swim_developer.dnotam.consumer.application.service.DnotamEventFilterService;
import com.github.swim_developer.dnotam.consumer.application.service.DnotamEventPersistenceService;
import com.github.swim_developer.dnotam.consumer.application.port.out.SubscriptionStore;
import com.github.swim_developer.framework.consumer.application.messaging.processing.DefaultEventProcessorConfig;
import com.github.swim_developer.dnotam.consumer.application.usecase.DnotamEventProcessingUseCase;
import com.github.swim_developer.framework.consumer.infrastructure.out.idempotency.AbstractIdempotencyCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the DNOTAM (Digital NOTAM) consumer business logic.
 *
 * <p>This class validates the end-to-end message processing pipeline for
 * DNOTAM events received via AMQP from an Aeronautical Information Service
 * Provider (AISP). DNOTAM events are XML payloads conforming to AIXM 5.1.1
 * (Aeronautical Information Exchange Model) and represent operational changes
 * such as runway closures, taxiway closures, airspace activations, navaid
 * outages, and obstacle notifications.</p>
 *
 * <h2>Business Rules Defended</h2>
 * <ul>
 *   <li><b>Schema Integrity</b> — Every incoming DNOTAM XML must pass AIXM 5.1.1
 *       XSD validation before any processing. Invalid payloads are rejected and
 *       routed to the Dead Letter Queue (DLQ) with a VALIDATION_ERROR reason.</li>
 *   <li><b>Mandatory Event Metadata</b> — Each DNOTAM must contain an {@code event:Event}
 *       element with a valid {@code event:scenario} (e.g., AD.CLS, RWY.CLS, SAA.ACT).
 *       Messages missing these mandatory elements are rejected to DLQ.</li>
 *   <li><b>Idempotent Processing</b> — Under AMQP AT_LEAST_ONCE delivery, the broker
 *       may redeliver messages. The consumer uses a SHA-256 content hash to detect
 *       and discard duplicates without hitting business logic or the database.</li>
 *   <li><b>Persistence-First Contract</b> — The idempotency cache is only marked
 *       AFTER successful database persistence. If persistence fails, the message
 *       can be reprocessed on retry instead of being silently lost.</li>
 *   <li><b>Kafka Scenario Routing</b> — After persistence, events are dispatched to
 *       scenario-specific Kafka topics (closures, airspace, navaid, obstacle, others)
 *       for downstream consumers. Unknown scenarios go to a catch-all topic.</li>
 *   <li><b>Envelope Splitting</b> — DNOTAM providers may bundle multiple AIXM messages
 *       in a single AMQP envelope using CDATA sections. The extractor splits them
 *       into individual messages for independent processing.</li>
 *   <li><b>Audit Trail</b> — Every processed event is persisted to MongoDB with
 *       operational context (subscriptionId, queueName, content hash) and the raw XML
 *       payload for CP1 compliance. Detailed AIXM data is available directly in the
 *       payload and is not duplicated in the document fields.</li>
 * </ul>
 *
 * @see DnotamEventProcessingUseCase
 * @see DnotamEventExtractor
 */
@SuppressWarnings("unchecked")
class DnotamConsumerTest {

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

    private static final String XML_NO_EVENT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <message:AIXMBasicMessage xmlns:message="http://www.aixm.aero/schema/5.1.1/message"
                xmlns:aixm="http://www.aixm.aero/schema/5.1.1" xmlns:gml="http://www.opengis.net/gml/3.2"
                gml:id="M00002">
                <message:hasMember>
                    <aixm:AirportHeliport gml:id="uuid.1b54b2d6"/>
                </message:hasMember>
            </message:AIXMBasicMessage>
            """;

    private static final String XML_NO_SCENARIO = """
            <?xml version="1.0" encoding="UTF-8"?>
            <message:AIXMBasicMessage xmlns:message="http://www.aixm.aero/schema/5.1.1/message"
                xmlns:gml="http://www.opengis.net/gml/3.2" xmlns:event="http://www.aixm.aero/schema/5.1.1/event"
                gml:id="M00003">
                <message:hasMember>
                    <event:Event gml:id="evt-no-scenario">
                        <event:timeSlice>
                            <event:EventTimeSlice gml:id="ts-1">
                                <gml:validTime>
                                    <gml:TimePeriod gml:id="tp-1">
                                        <gml:beginPosition>2022-02-01T11:00:00Z</gml:beginPosition>
                                        <gml:endPosition>2022-02-08T12:00:00Z</gml:endPosition>
                                    </gml:TimePeriod>
                                </gml:validTime>
                                <event:notification>
                                    <event:NOTAM gml:id="notam-1">
                                        <event:series>B</event:series>
                                        <event:number>1</event:number>
                                        <event:year>2022</event:year>
                                    </event:NOTAM>
                                </event:notification>
                            </event:EventTimeSlice>
                        </event:timeSlice>
                    </event:Event>
                </message:hasMember>
            </message:AIXMBasicMessage>
            """;

    private static final String MULTI_MESSAGE_ENVELOPE = """
            <messages>
                <message><![CDATA[<message:AIXMBasicMessage>first</message:AIXMBasicMessage>]]></message>
                <message><![CDATA[<message:AIXMBasicMessage>second</message:AIXMBasicMessage>]]></message>
            </messages>
            """;

    private static final String AIXM_WITH_EVENT_AND_AIRPORT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <message:AIXMBasicMessage xmlns:message="http://www.aixm.aero/schema/5.1.1/message"
                xmlns:gml="http://www.opengis.net/gml/3.2" xmlns:aixm="http://www.aixm.aero/schema/5.1.1"
                xmlns:event="http://www.aixm.aero/schema/5.1.1/event" xmlns:xlink="http://www.w3.org/1999/xlink"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                xsi:schemaLocation="http://www.aixm.aero/schema/5.1.1/message http://www.aixm.aero/schema/5.1.1/message/AIXM_BasicMessage.xsd
                http://www.aixm.aero/schema/5.1.1/event http://www.aixm.aero/schema/5.1.1/event/version_5.1.1-j/Event_Features.xsd"
                gml:id="M00004">
                <message:hasMember>
                    <aixm:AirportHeliport gml:id="uuid.1b54b2d6-a5ff-4e57-94c2-f4047a381c64">
                        <aixm:timeSlice>
                            <aixm:AirportHeliportTimeSlice gml:id="ID_ACT_55">
                                <gml:validTime>
                                    <gml:TimePeriod gml:id="tp-ah-1">
                                        <gml:beginPosition>2022-02-01T11:00:00Z</gml:beginPosition>
                                        <gml:endPosition>2022-02-08T12:00:00Z</gml:endPosition>
                                    </gml:TimePeriod>
                                </gml:validTime>
                                <aixm:interpretation>TEMPDELTA</aixm:interpretation>
                                <aixm:sequenceNumber>1</aixm:sequenceNumber>
                                <aixm:correctionNumber>0</aixm:correctionNumber>
                            </aixm:AirportHeliportTimeSlice>
                        </aixm:timeSlice>
                    </aixm:AirportHeliport>
                </message:hasMember>
                <message:hasMember>
                    <event:Event gml:id="uuid.53432671-c3f4-4b5d-b72a-85722755b4d6">
                        <event:timeSlice>
                            <event:EventTimeSlice gml:id="IDE_ACT_81">
                                <gml:validTime>
                                    <gml:TimePeriod gml:id="IDE_ACT_82">
                                        <gml:beginPosition>2022-02-01T11:00:00Z</gml:beginPosition>
                                        <gml:endPosition>2022-02-08T12:00:00Z</gml:endPosition>
                                    </gml:TimePeriod>
                                </gml:validTime>
                                <aixm:interpretation>BASELINE</aixm:interpretation>
                                <aixm:sequenceNumber>1</aixm:sequenceNumber>
                                <aixm:correctionNumber>0</aixm:correctionNumber>
                                <aixm:featureLifetime>
                                    <gml:TimePeriod gml:id="IDE_ACT_83">
                                        <gml:beginPosition>2022-02-01T11:00:00Z</gml:beginPosition>
                                        <gml:endPosition>2022-02-08T12:00:00Z</gml:endPosition>
                                    </gml:TimePeriod>
                                </aixm:featureLifetime>
                                <event:scenario>AD.CLS</event:scenario>
                                <event:notification>
                                    <event:NOTAM gml:id="IDE_ACT_84">
                                        <event:series>B</event:series>
                                        <event:number>214</event:number>
                                        <event:year>2022</event:year>
                                        <event:type>N</event:type>
                                        <event:affectedFIR>EAAD</event:affectedFIR>
                                        <event:location>EADD</event:location>
                                        <event:text>AD closed.</event:text>
                                    </event:NOTAM>
                                </event:notification>
                            </event:EventTimeSlice>
                        </event:timeSlice>
                    </event:Event>
                </message:hasMember>
            </message:AIXMBasicMessage>
            """;

    private static final String AIXM_AIRPORT_ONLY_NO_EVENT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <message:AIXMBasicMessage xmlns:message="http://www.aixm.aero/schema/5.1.1/message"
                xmlns:aixm="http://www.aixm.aero/schema/5.1.1"
                xmlns:gml="http://www.opengis.net/gml/3.2"
                gml:id="M00005">
                <message:hasMember>
                    <aixm:AirportHeliport gml:id="uuid.1b54b2d6">
                        <aixm:timeSlice>
                            <aixm:AirportHeliportTimeSlice gml:id="ID_1">
                                <gml:validTime>
                                    <gml:TimePeriod gml:id="tp-ah-2">
                                        <gml:beginPosition>2022-02-01T11:00:00Z</gml:beginPosition>
                                        <gml:endPosition>2022-02-08T12:00:00Z</gml:endPosition>
                                    </gml:TimePeriod>
                                </gml:validTime>
                                <aixm:interpretation>BASELINE</aixm:interpretation>
                                <aixm:sequenceNumber>1</aixm:sequenceNumber>
                                <aixm:correctionNumber>0</aixm:correctionNumber>
                            </aixm:AirportHeliportTimeSlice>
                        </aixm:timeSlice>
                    </aixm:AirportHeliport>
                </message:hasMember>
            </message:AIXMBasicMessage>
            """;

    private DnotamEventProcessingUseCase eventProcessor;
    private MongoEventStore repository;
    private AbstractIdempotencyCache idempotencyCache;
    private AbstractDeadLetterService deadLetterService;
    private OutboxRouterFanOut outboxRouterFanOut;
    private DnotamJaxbUnmarshallerPool jaxbPool;
    private DnotamEventExtractor eventExtractor;
    private DnotamXmlEnvelopeParser envelopeParser;
    private SimpleMeterRegistry meterRegistry;
    private SubscriptionFilterCache filterCache;

    @BeforeEach
    void setup(TestInfo testInfo) throws Exception {
        System.out.printf("%n══ ▶ %s.%s%n", getClass().getSimpleName(), testInfo.getDisplayName());
        meterRegistry = new SimpleMeterRegistry();
        eventExtractor = new DnotamEventExtractor();
        envelopeParser = new DnotamXmlEnvelopeParser();

        repository = mock(MongoEventStore.class);
        idempotencyCache = mock(AbstractIdempotencyCache.class);
        deadLetterService = mock(AbstractDeadLetterService.class);
        outboxRouterFanOut = mock(OutboxRouterFanOut.class);
        jaxbPool = new DnotamJaxbUnmarshallerPool();

        filterCache = new SubscriptionFilterCache();
        @SuppressWarnings("unchecked")
        Instance<SwimMessageInterceptor> interceptors = mock(Instance.class);
        when(interceptors.isUnsatisfied()).thenReturn(true);
        DnotamProcessingMetrics metrics = new DnotamProcessingMetrics(meterRegistry);
        DefaultEventProcessorConfig processorConfig = new DefaultEventProcessorConfig("dnotam", idempotencyCache, deadLetterService);
        DnotamEventDataValidator validator = new DnotamEventDataValidator(metrics, deadLetterService);
        DnotamEventFilterService filterService = new DnotamEventFilterService(filterCache, deadLetterService);
        DnotamEventPersistenceService persistenceService = new DnotamEventPersistenceService(
                repository, outboxRouterFanOut, deadLetterService);
        SubscriptionStore subscriptionStore = mock(SubscriptionStore.class);
        eventProcessor = new DnotamEventProcessingUseCase(
                processorConfig, jaxbPool, eventExtractor, validator, filterService, persistenceService,
                metrics, meterRegistry, subscriptionStore, interceptors);
    }


    // ── Event Processing Pipeline ────────────────────────────────────────

    /**
     * Validates the complete happy path: a valid AIXM 5.1.1 DNOTAM event (AD.CLS scenario)
     * is parsed, persisted to MongoDB with operational context and raw payload, and dispatched
     * to the outbox for Kafka delivery. The content hash is marked in the idempotency cache
     * only after successful persistence.
     */
    @Test
    void validDnotamEventIsPersistedAndDispatched() {
        when(idempotencyCache.isAlreadyProcessed(anyString(), anyString())).thenReturn(false);

        eventProcessor.processAndPersistSingleMessage("sub-1", "queue-1", "msg-001", VALID_DNOTAM_XML, 0);

        var captor = ArgumentCaptor.forClass(Event.class);
        verify(repository).persist(captor.capture());
        Event saved = captor.getValue();
        assertThat(saved.getSubscriptionId()).isEqualTo("sub-1");
        assertThat(saved.getQueueName()).isEqualTo("queue-1");
        assertThat(saved.getDeliveryStatus()).isEqualTo(OutboxDeliveryStatus.SENT);
        assertThat(saved.getContentHash()).isEqualTo(HashUtil.sha256(VALID_DNOTAM_XML));
        assertThat(saved.getRawPayload()).isEqualTo(VALID_DNOTAM_XML);

        verify(idempotencyCache).markAsProcessed("sub-1", saved.getContentHash());
        verify(outboxRouterFanOut).route(anyString(), anyString());
    }

    /**
     * Under AMQP AT_LEAST_ONCE delivery, the broker may redeliver messages after
     * acknowledgment timeout. Duplicate messages (same content hash) must be silently
     * discarded without persisting, without DLQ routing, and without outbox dispatch.
     * A metric counter is incremented for observability.
     */
    @Test
    void duplicateMessageIsDiscardedWithoutProcessing() {
        when(idempotencyCache.isAlreadyProcessed(anyString(), anyString())).thenReturn(true);

        eventProcessor.processAndPersistSingleMessage("sub-1", "q-1", "msg-dup", VALID_DNOTAM_XML, 0);

        verify(repository, never()).persist((Event) any());
        verify(deadLetterService, never()).sendToDeadLetterQueue(
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), any(Exception.class));
        verify(outboxRouterFanOut, never()).route(anyString(), anyString());
        assertThat(meterRegistry.counter("dnotam_duplicate_messages_total").count()).isEqualTo(1.0);
    }

    /**
     * XML payloads that fail AIXM 5.1.1 XSD validation are immediately rejected.
     * The payload is sent to the DLQ with reason VALIDATION_ERROR, and no business
     * processing or persistence occurs. This is the primary defense against corrupted
     * or malicious payloads entering the ATM data pipeline.
     */
    @Test
    void invalidXmlIsSentToDeadLetterQueue() {
        String badXml = "<broken>not-aixm</broken>";

        ProcessingOutcome outcome = eventProcessor.processAndPersistSingleMessage(
                "sub-1", "q-1", "msg-bad", badXml, 0);

        assertThat(outcome).isEqualTo(ProcessingOutcome.SKIPPED);
        verify(deadLetterService).sendToDeadLetterQueue(
                eq("sub-1"), eq("q-1"), eq("msg-bad"), eq(0), eq(badXml),
                eq("VALIDATION_ERROR"), any(Exception.class));
        verify(repository, never()).persist((Event) any());
    }

    /**
     * An AIXM BasicMessage without an {@code event:Event} element is structurally
     * incomplete for DNOTAM processing. Such messages are rejected to DLQ with
     * reason VALIDATION_ERROR because they cannot produce meaningful event metadata.
     */
    @Test
    void missingEventElementIsSentToDeadLetterQueue() {
        ProcessingOutcome outcome = eventProcessor.processAndPersistSingleMessage(
                "sub-1", "q-1", "msg-noevt", XML_NO_EVENT, 0);

        assertThat(outcome).isEqualTo(ProcessingOutcome.SKIPPED);
        verify(deadLetterService).sendToDeadLetterQueue(
                eq("sub-1"), eq("q-1"), eq("msg-noevt"), eq(0), eq(XML_NO_EVENT),
                eq("VALIDATION_ERROR"), any(Exception.class));
        verify(repository, never()).persist((Event) any());
    }

    /**
     * The {@code event:scenario} field identifies the type of operational change
     * (AD.CLS, RWY.CLS, SAA.ACT, etc.) and determines Kafka routing. A DNOTAM
     * event without a scenario cannot be classified or routed, so it is rejected.
     */
    @Test
    void missingScenarioIsSentToDeadLetterQueue() {
        ProcessingOutcome outcome = eventProcessor.processAndPersistSingleMessage(
                "sub-1", "q-1", "msg-noscn", XML_NO_SCENARIO, 0);

        assertThat(outcome).isEqualTo(ProcessingOutcome.SKIPPED);
        verify(deadLetterService).sendToDeadLetterQueue(
                eq("sub-1"), eq("q-1"), eq("msg-noscn"), eq(0), eq(XML_NO_SCENARIO),
                eq("VALIDATION_ERROR"), any(Exception.class));
        verify(repository, never()).persist((Event) any());
    }

    /**
     * If MongoDB persistence fails (connection error, disk full, etc.), the message
     * is routed to DLQ with reason PERSISTENCE_ERROR. Critically, the idempotency
     * cache is NOT marked, allowing the message to be reprocessed after recovery.
     */
    @Test
    void persistenceFailureIsSentToDeadLetterQueue() {
        when(idempotencyCache.isAlreadyProcessed(anyString(), anyString())).thenReturn(false);
        doThrow(new RuntimeException("MongoDB connection refused"))
                .when(repository).persist((Event) any());

        assertThatThrownBy(() -> eventProcessor.processAndPersistSingleMessage(
                "sub-1", "q-1", "msg-fail", VALID_DNOTAM_XML, 0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("persist");

        verify(deadLetterService).sendToDeadLetterQueue(
                eq("sub-1"), eq("q-1"), eq("msg-fail"), eq(0), eq(VALID_DNOTAM_XML),
                eq("PERSISTENCE_ERROR"), any(Exception.class));
        verify(idempotencyCache, never()).markAsProcessed(anyString(), anyString());
    }

    /**
     * The ordering contract: persist FIRST, then mark the idempotency cache.
     * If this order is reversed, a persistence failure after cache marking would
     * permanently lose the message (treated as duplicate on retry).
     */
    @Test
    void contentHashIsMarkedOnlyAfterSuccessfulPersist() {
        when(idempotencyCache.isAlreadyProcessed(anyString(), anyString())).thenReturn(false);

        eventProcessor.processAndPersistSingleMessage("sub-1", "q-1", "msg-1", VALID_DNOTAM_XML, 0);

        var inOrder = inOrder(repository, idempotencyCache);
        inOrder.verify(repository).persist((Event) any());
        inOrder.verify(idempotencyCache).markAsProcessed(anyString(), anyString());
    }


    // ── Envelope Splitting ───────────────────────────────────────────────

    /**
     * DNOTAM providers may bundle multiple AIXM BasicMessages in a single AMQP
     * message using a {@code <messages>} envelope with CDATA sections. The extractor
     * splits them into individual messages for independent processing, ensuring
     * one failed event does not block others in the same envelope.
     */
    @Test
    void splitEnvelopeExtractsMultipleMessagesFromCdata() {
        List<String> messages = envelopeParser.splitEnvelope(MULTI_MESSAGE_ENVELOPE);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).contains("first");
        assertThat(messages.get(1)).contains("second");
    }

    /**
     * When the payload is already a single AIXM BasicMessage (no envelope),
     * the extractor returns it as-is in a single-element list.
     */
    @Test
    void splitEnvelopeHandlesSingleAixmMessage() {
        List<String> messages = envelopeParser.splitEnvelope(VALID_DNOTAM_XML);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).contains("AIXMBasicMessage");
    }

    /**
     * Null or empty payloads are rejected at the envelope splitting stage before
     * any XML parsing is attempted.
     */
    @Test
    void splitEnvelopeRejectsNullPayload() {
        assertThatThrownBy(() -> envelopeParser.splitEnvelope(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null or empty");
    }

    // ── DnotamEventExtractor.extract / hasEvent ────────────────────────────────

    @Test
    void extractReturnsFilterMetadataFromAixmMessage() throws Exception {
        AIXMBasicMessageType message = jaxbPool.unmarshalAndValidate(AIXM_WITH_EVENT_AND_AIRPORT);
        List<Optional<EventData>> results = eventExtractor.extract(message);

        assertThat(results).hasSize(1);
        assertThat(results.get(0)).isPresent();
        EventData eventData = results.get(0).get();
        assertThat(eventData.scenario().value()).isEqualTo("AD.CLS");
        assertThat(eventData.affectedFir()).isEqualTo("EAAD");
        assertThat(eventData.location()).isEqualTo("EADD");
    }

    @Test
    void extractReturnsEmptyOptionalWhenNoEventElement() throws Exception {
        AIXMBasicMessageType message = jaxbPool.unmarshalAndValidate(AIXM_AIRPORT_ONLY_NO_EVENT);
        List<Optional<EventData>> results = eventExtractor.extract(message);

        assertThat(results).hasSize(1);
        assertThat(results.get(0)).isEmpty();
    }

    @Test
    void extractReturnsEmptyOptionalForNullPayload() {
        List<Optional<EventData>> results = eventExtractor.extract(null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0)).isEmpty();
    }

    @Test
    void hasEventDetectsEventElementInPayload() {
        assertThat(DnotamXmlEnvelopeParser.hasEvent(AIXM_WITH_EVENT_AND_AIRPORT)).isTrue();
    }

    @Test
    void hasEventFalseWhenNoEventElement() {
        assertThat(DnotamXmlEnvelopeParser.hasEvent(AIXM_AIRPORT_ONLY_NO_EVENT)).isFalse();
    }

    @Test
    void hasEventFalseForNullPayload() {
        assertThat(DnotamXmlEnvelopeParser.hasEvent(null)).isFalse();
    }

    // ── SubscriptionRequest ────────────────────────────────────────────────

    @Test
    void subscriptionRequestConfigHashIsStableAcrossCalls() {
        SubscriptionRequest request = new SubscriptionRequest(
                "DNOTAM/v1", null,
                List.of("RWY.CLS"),
                List.of("LPPT"),
                null, null, null, null,
                "Test subscription", null);

        assertThat(request.generateConfigHash()).isEqualTo(request.generateConfigHash());
    }

    @Test
    void subscriptionRequestConfigHashExcludesNonFilterFields() {
        SubscriptionRequest request1 = new SubscriptionRequest(
                "DNOTAM/v1", null,
                List.of("RWY.CLS", "AD.CLS"),
                List.of("LPPT", "EHAM"),
                List.of("LPPC"), null, null, null,
                "Description A", null);

        SubscriptionRequest request2 = new SubscriptionRequest(
                "DNOTAM/v1", null,
                List.of("RWY.CLS", "AD.CLS"),
                List.of("LPPT", "EHAM"),
                List.of("LPPC"), null, null, null,
                "Description B", null);

        assertThat(request1.generateConfigHash()).isEqualTo(request2.generateConfigHash());
    }

    // ── Subscription Filter ─────────────────────────────────────────────

    @Test
    void filterMismatch_scenario_sendsToDeadLetter() {
        filterCache.updateFilters("sub-1", FilterDimension.EVENT_SCENARIO, List.of("RWY.CLS"));

        eventProcessor.processAndPersistSingleMessage(
                "sub-1", "q-1", "msg-filter-scn", VALID_DNOTAM_XML, 0);

        verify(repository, never()).persist(any(Event.class));
        verify(deadLetterService).sendToDeadLetterQueue(
                eq("sub-1"), eq("q-1"), eq("msg-filter-scn"), eq(0), anyString(),
                eq("SUBSCRIPTION_FILTER_MISMATCH"), any(IllegalArgumentException.class));
    }

    @Test
    void filterMismatch_airport_sendsToDeadLetter() {
        filterCache.updateFilters("sub-1", FilterDimension.EVENT_SCENARIO, List.of("AD.CLS"));
        filterCache.updateFilters("sub-1", FilterDimension.AIRPORT_HELIPORT, List.of("LPPT"));

        eventProcessor.processAndPersistSingleMessage(
                "sub-1", "q-1", "msg-filter-apt", VALID_DNOTAM_XML, 0);

        verify(repository, never()).persist(any(Event.class));
        verify(deadLetterService).sendToDeadLetterQueue(
                eq("sub-1"), eq("q-1"), eq("msg-filter-apt"), eq(0), anyString(),
                eq("SUBSCRIPTION_FILTER_MISMATCH"), any(IllegalArgumentException.class));
    }

    @Test
    void filterMatch_scenario_persistsNormally() {
        filterCache.updateFilters("sub-1", FilterDimension.EVENT_SCENARIO, List.of("AD.CLS"));
        when(idempotencyCache.isAlreadyProcessed(anyString(), anyString())).thenReturn(false);

        eventProcessor.processAndPersistSingleMessage(
                "sub-1", "q-1", "msg-filter-ok", VALID_DNOTAM_XML, 0);

        verify(repository).persist(any(Event.class));
        verify(deadLetterService, never()).sendToDeadLetterQueue(
                anyString(), anyString(), anyString(), anyInt(), anyString(),
                eq("SUBSCRIPTION_FILTER_MISMATCH"), any());
    }

    @Test
    void emptyFilterCache_allowsAll() {
        when(idempotencyCache.isAlreadyProcessed(anyString(), anyString())).thenReturn(false);

        eventProcessor.processAndPersistSingleMessage(
                "sub-1", "q-1", "msg-filter-empty", VALID_DNOTAM_XML, 0);

        verify(repository).persist(any(Event.class));
    }

    // ── Utilities ────────────────────────────────────────────────────────

}
