package com.github.swim_developer.dnotam.consumer.infrastructure.in.amqp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.swim_developer.dnotam.consumer.domain.model.EventData;
import com.github.swim_developer.dnotam.consumer.infrastructure.out.xml.DnotamXmlEnvelopeParser;
import com.github.swim_developer.dnotam.consumer.application.usecase.DnotamEventProcessingUseCase;
import com.github.swim_developer.extension.inbox.reader.kafka.AbstractKafkaInboxReader;
import com.github.swim_developer.framework.application.model.PreparedEvent;
import com.github.swim_developer.framework.application.model.ProcessingOutcome;
import com.github.swim_developer.framework.infrastructure.out.messaging.InboxEnvelope;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.reactive.messaging.kafka.KafkaRecordBatch;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import java.util.List;
import java.util.concurrent.CompletionStage;

@Slf4j
@ApplicationScoped
public class DnotamInboxMessageHandler extends AbstractKafkaInboxReader {

    private final DnotamEventProcessingUseCase eventProcessor;
    private final DnotamXmlEnvelopeParser envelopeParser;

    protected DnotamInboxMessageHandler() {
        this(null, null, null, null);
    }

    @Inject
    public DnotamInboxMessageHandler(ObjectMapper objectMapper,
                             MeterRegistry meterRegistry,
                             DnotamEventProcessingUseCase eventProcessor,
                             DnotamXmlEnvelopeParser envelopeParser) {
        super(objectMapper, meterRegistry);
        this.eventProcessor = eventProcessor;
        this.envelopeParser = envelopeParser;
    }

    @Incoming("in-dnotam-inbox")
    @Blocking
    public CompletionStage<Void> onInboxBatch(KafkaRecordBatch<String, String> batch) {
        List<PreparedEvent<EventData>> prepared = prepareBatch(batch, eventProcessor.eventProcessingOrchestrator());

        if (!prepared.isEmpty()) {
            eventProcessor.batchPersistAndDispatch(prepared);
            eventProcessor.markBatchAsProcessed(prepared);
        }

        processedCounter.increment(prepared.size());
        return batch.ack();
    }

    @Override
    public List<String> extractMessages(String rawPayload) {
        return envelopeParser.splitEnvelope(rawPayload);
    }

    @WithSpan("dnotam.consumer.event.process")
    @Override
    public void processSingleMessage(InboxEnvelope envelope, String xmlPayload, int index) {
        Span.current().setAttribute("dnotam.subscription", envelope.subscriptionId());
        Span.current().setAttribute("dnotam.queue", envelope.queueName());
        Span.current().setAttribute("dnotam.messageIndex", index);

        ProcessingOutcome outcome = eventProcessor.processAndPersistSingleMessage(
                envelope.subscriptionId(),
                envelope.queueName(),
                envelope.amqpMessageId(),
                xmlPayload,
                index);
        Span.current().setAttribute("dnotam.outcome", outcome.name());
    }

    @Override
    public String getMetricPrefix() {
        return "dnotam";
    }
}
