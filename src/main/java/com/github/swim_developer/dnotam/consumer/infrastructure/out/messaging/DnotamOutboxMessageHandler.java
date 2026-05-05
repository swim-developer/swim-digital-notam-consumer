package com.github.swim_developer.dnotam.consumer.infrastructure.out.messaging;

import com.github.swim_developer.framework.consumer.application.messaging.outbox.AbstractOutboxEventConsumer;
import com.github.swim_developer.framework.consumer.application.messaging.outbox.OutboxRouterFanOut;
import com.github.swim_developer.framework.consumer.application.port.out.SwimOutboxRetryPort;
import com.github.swim_developer.framework.domain.model.SwimOutboxEvent;
import com.github.swim_developer.framework.infrastructure.out.cache.HandoffCache;
import com.github.swim_developer.dnotam.consumer.domain.model.Event;
import com.github.swim_developer.dnotam.consumer.infrastructure.out.persistence.MongoEventStore;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;

import java.util.Optional;

@Slf4j
@ApplicationScoped
public class DnotamOutboxMessageHandler extends AbstractOutboxEventConsumer<Event> implements SwimOutboxRetryPort {

    public static final String OUTBOX_EVENT_ADDRESS = "outbox.pending";

    private final MongoEventStore eventRepository;
    private final OutboxRouterFanOut outboxRouterFanOut;
    private final HandoffCache handoffCache;

    @Inject
    public DnotamOutboxMessageHandler(MongoEventStore eventRepository,
                              OutboxRouterFanOut outboxRouterFanOut,
                              HandoffCache handoffCache,
                              @ConfigProperty(name = "swim.outbox.kafka.max-retries", defaultValue = "3") int maxKafkaRetries) {
        super(maxKafkaRetries);
        this.eventRepository = eventRepository;
        this.outboxRouterFanOut = outboxRouterFanOut;
        this.handoffCache = handoffCache;
    }

    @Override
    @ConsumeEvent(OUTBOX_EVENT_ADDRESS)
    @Blocking
    @Timeout(10000)
    @Retry(maxRetries = 3, delay = 1000)
    @Bulkhead(250)
    @WithSpan("dnotam.consumer.outbox.kafka")
    public void processOutboxEvent(String eventId) {
        super.processOutboxEvent(eventId);
    }

    public void retrySend(Event event) {
        sendAndUpdateStatus(event);
    }

    @Override
    public void retryOutboxEvent(SwimOutboxEvent event) {
        sendAndUpdateStatus((Event) event);
    }

    @Override
    protected Event resolveEvent(String eventIdStr) {
        Optional<Event> cached = handoffCache.getAndRemove(eventIdStr, Event.class);
        if (cached.isPresent()) {
            log.debug("Event retrieved from handoff cache - EventId: {}", eventIdStr);
            return cached.get();
        }
        return eventRepository.findEventById(eventIdStr);
    }

    @Override
    protected OutboxRouterFanOut getRouterFanOut() {
        return outboxRouterFanOut;
    }

    @Override
    protected String getEventId(Event event) {
        return event.getId() != null ? event.getId().toHexString() : null;
    }

    @Override
    protected void updateEvent(Event event) {
        eventRepository.persistOrUpdate(event);
    }

    public static String getEventAddress() {
        return OUTBOX_EVENT_ADDRESS;
    }
}
