package com.github.swim_developer.dnotam.consumer.application.service;

import com.github.swim_developer.dnotam.consumer.application.metrics.DnotamProcessingMetrics;
import com.github.swim_developer.framework.domain.exception.ConsumerValidationException;
import com.github.swim_developer.dnotam.consumer.domain.model.EventData;
import com.github.swim_developer.framework.application.model.ProcessingContext;
import com.github.swim_developer.framework.consumer.application.messaging.processing.SwimEventValidator;
import com.github.swim_developer.framework.application.port.out.SwimDeadLetterPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class DnotamEventDataValidator implements SwimEventValidator<EventData> {

    private final DnotamProcessingMetrics metrics;
    private final SwimDeadLetterPort deadLetterService;

    @Inject
    public DnotamEventDataValidator(DnotamProcessingMetrics metrics, SwimDeadLetterPort deadLetterService) {
        this.metrics = metrics;
        this.deadLetterService = deadLetterService;
    }

    @Override
    public void validateExtractedData(ProcessingContext ctx, EventData event) {
        if (event.scenario() == null) {
            metrics.incrementNoScenario();
            String messageId = ctx.compositeMessageId();
            log.error("Invalid DNOTAM: missing scenario - MessageId: {}", messageId);
            deadLetterService.sendToDeadLetterQueue(
                    ctx.subscriptionId(), ctx.queueName(), ctx.amqpMessageId(),
                    ctx.index(), ctx.xmlPayload(), "VALIDATION_ERROR",
                    new IllegalArgumentException("Missing required scenario element"));
            throw new ConsumerValidationException("Invalid DNOTAM: missing scenario");
        }
    }
}
