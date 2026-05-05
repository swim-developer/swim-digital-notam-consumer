package com.github.swim_developer.dnotam.consumer.application.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class DnotamProcessingMetrics {

    private static final String REASON_NO_EVENT = "NO_EVENT";
    private static final String REASON_NO_SCENARIO = "NO_SCENARIO";

    private final Map<String, Counter> invalidCounters = new ConcurrentHashMap<>();
    private final Counter duplicateMessagesCounter;

    @Inject
    public DnotamProcessingMetrics(MeterRegistry meterRegistry) {
        String[] invalidReasons = {REASON_NO_EVENT, REASON_NO_SCENARIO};
        for (String reason : invalidReasons) {
            invalidCounters.put(reason, Counter.builder("dnotam_events_invalid_total")
                    .tag("reason", reason)
                    .description("Total invalid DNOTAM events by reason")
                    .register(meterRegistry));
        }
        duplicateMessagesCounter = Counter.builder("dnotam_duplicate_messages_total")
                .description("Total duplicate DNOTAM messages discarded")
                .register(meterRegistry);
    }

    public void incrementNoEvent() {
        invalidCounters.get(REASON_NO_EVENT).increment();
    }

    public void incrementNoScenario() {
        invalidCounters.get(REASON_NO_SCENARIO).increment();
    }

    public void incrementDuplicate() {
        duplicateMessagesCounter.increment();
    }
}
