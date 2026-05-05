package com.github.swim_developer.dnotam.consumer.application.service;

import com.github.swim_developer.dnotam.consumer.application.metrics.DnotamProcessingMetrics;
import com.github.swim_developer.dnotam.consumer.application.port.out.SubscriptionStore;
import com.github.swim_developer.dnotam.consumer.domain.model.Subscription;
import com.github.swim_developer.framework.domain.exception.ConsumerValidationException;
import com.github.swim_developer.framework.application.model.ProcessingContext;
import com.github.swim_developer.dnotam.consumer.domain.model.EventData;
import com.github.swim_developer.framework.consumer.application.messaging.processing.SwimEventProcessorCallbacks;
import com.github.swim_developer.framework.consumer.application.messaging.processing.SwimEventProcessorConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public class DnotamProcessorCallbacks implements SwimEventProcessorCallbacks<EventData> {

    private final DnotamProcessingMetrics metrics;
    private final SubscriptionStore subscriptionStore;

    public DnotamProcessorCallbacks(DnotamProcessingMetrics metrics, SubscriptionStore subscriptionStore) {
        this.metrics = metrics;
        this.subscriptionStore = subscriptionStore;
    }

    @Override
    public boolean preProcess(ProcessingContext ctx) {
        Optional<Subscription> sub = subscriptionStore.findBySubscriptionId(ctx.subscriptionId());
        if (sub.isPresent() && "PAUSED".equals(sub.get().getSubscriptionStatus())) {
            log.warn("PAUSED_SUBSCRIPTION_DISCARD: SubscriptionId={}, MessageId={}, Queue={}",
                    ctx.subscriptionId(), ctx.amqpMessageId(), ctx.queueName());
            return true;
        }
        return false;
    }

    @Override
    public void onDuplicateDetected(ProcessingContext ctx, String contentHash) {
        metrics.incrementDuplicate();
    }

    @Override
    public void onExtractionFailure(ProcessingContext ctx, SwimEventProcessorConfig config) {
        metrics.incrementNoEvent();
        String messageId = ctx.compositeMessageId();
        log.error("Invalid DNOTAM: missing event:Event element - MessageId: {}", messageId);
        config.getDeadLetterService().sendToDeadLetterQueue(
                ctx.subscriptionId(), ctx.queueName(), ctx.amqpMessageId(),
                ctx.index(), ctx.xmlPayload(), "VALIDATION_ERROR",
                new IllegalArgumentException("Missing required event:Event element"));
        throw new ConsumerValidationException("Invalid DNOTAM: missing event:Event element");
    }

    @Override
    public void onValidationFailure(ProcessingContext ctx, Exception e) {
        log.error("Problematic AIXM XML (first 500 chars): {}",
                ctx.xmlPayload().length() > 500 ? ctx.xmlPayload().substring(0, 500) : ctx.xmlPayload());
    }
}
