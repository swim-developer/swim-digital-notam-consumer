package com.github.swim_developer.dnotam.consumer.application.usecase;

import aero.aixm.message.AIXMBasicMessageType;
import com.github.swim_developer.dnotam.consumer.application.metrics.DnotamProcessingMetrics;
import com.github.swim_developer.dnotam.consumer.application.port.out.SubscriptionStore;
import com.github.swim_developer.dnotam.consumer.application.service.DnotamEventDataValidator;
import com.github.swim_developer.dnotam.consumer.infrastructure.out.xml.DnotamEventExtractor;
import com.github.swim_developer.dnotam.consumer.application.service.DnotamEventFilterService;
import com.github.swim_developer.dnotam.consumer.application.service.DnotamEventPersistenceService;
import com.github.swim_developer.dnotam.consumer.application.service.DnotamProcessorCallbacks;
import com.github.swim_developer.framework.consumer.application.messaging.processing.DefaultEventProcessorConfig;
import com.github.swim_developer.dnotam.consumer.domain.model.EventData;
import com.github.swim_developer.framework.application.model.PreparedEvent;
import com.github.swim_developer.framework.application.model.ProcessingContext;
import com.github.swim_developer.framework.application.model.ProcessingOutcome;
import com.github.swim_developer.framework.consumer.application.messaging.processing.EventProcessingOrchestrator;
import com.github.swim_developer.framework.consumer.application.messaging.processing.EventProcessingOrchestratorDependencies;
import com.github.swim_developer.framework.consumer.application.messaging.processing.SwimEventParser;
import com.github.swim_developer.framework.consumer.application.messaging.processing.SwimEventProcessorCallbacks;
import com.github.swim_developer.framework.application.port.in.SwimMessageInterceptor;
import com.github.swim_developer.framework.application.port.out.SwimXmlUnmarshallerPort;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class DnotamEventProcessingUseCase {

    private final EventProcessingOrchestrator<EventData, AIXMBasicMessageType> orchestrator;
    private final DnotamEventPersistenceService persistenceService;

    @Inject
    public DnotamEventProcessingUseCase(
            DefaultEventProcessorConfig processorConfig,
            SwimXmlUnmarshallerPort<AIXMBasicMessageType> jaxbPool,
            DnotamEventExtractor eventExtractor,
            DnotamEventDataValidator validator,
            DnotamEventFilterService filterService,
            DnotamEventPersistenceService persistenceService,
            DnotamProcessingMetrics metrics,
            MeterRegistry meterRegistry,
            SubscriptionStore subscriptionStore,
            @Any Instance<SwimMessageInterceptor> interceptorInstances) {
        this.persistenceService = persistenceService;
        SwimEventParser<AIXMBasicMessageType> parser = jaxbPool::unmarshalAndValidate;
        SwimEventProcessorCallbacks<EventData> callbacks = new DnotamProcessorCallbacks(metrics, subscriptionStore);
        this.orchestrator = new EventProcessingOrchestrator<>(new EventProcessingOrchestratorDependencies<>(
                processorConfig, parser, eventExtractor, validator, filterService,
                persistenceService, callbacks, meterRegistry, interceptorInstances));
    }

    public ProcessingOutcome processAndPersistSingleMessage(String subscriptionId, String queueName,
                                                            String amqpMessageId, String aixmXml, int index) {
        return orchestrator.processMessage(new ProcessingContext(subscriptionId, queueName, amqpMessageId, aixmXml, index, null));
    }

    public EventProcessingOrchestrator<EventData, AIXMBasicMessageType> eventProcessingOrchestrator() {
        return orchestrator;
    }

    public void batchPersistAndDispatch(List<PreparedEvent<EventData>> batch) {
        persistenceService.batchPersistAndDispatch(batch);
    }

    public void markBatchAsProcessed(List<PreparedEvent<EventData>> batch) {
        orchestrator.markBatchAsProcessed(batch);
    }
}
