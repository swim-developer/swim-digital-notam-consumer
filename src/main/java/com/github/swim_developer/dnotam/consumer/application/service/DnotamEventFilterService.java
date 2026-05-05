package com.github.swim_developer.dnotam.consumer.application.service;

import com.github.swim_developer.dnotam.consumer.domain.model.EventData;
import com.github.swim_developer.dnotam.consumer.domain.model.FilterDimension;
import com.github.swim_developer.framework.application.port.out.SwimDeadLetterPort;
import com.github.swim_developer.framework.application.port.out.SwimSubscriptionFilterPort;
import com.github.swim_developer.framework.consumer.application.messaging.processing.AbstractEventFilterService;
import com.github.swim_developer.framework.consumer.application.messaging.processing.FilterRule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class DnotamEventFilterService extends AbstractEventFilterService<EventData> {

    @Inject
    public DnotamEventFilterService(SwimSubscriptionFilterPort filterCache,
                                    SwimDeadLetterPort deadLetterService) {
        super(filterCache, deadLetterService);
    }

    @Override
    protected List<FilterRule<EventData>> buildFilterRules(EventData event) {
        return List.of(
                new FilterRule<>(FilterDimension.EVENT_SCENARIO, e -> e.scenario().value()),
                new FilterRule<>(FilterDimension.AIRPORT_HELIPORT, EventData::location),
                new FilterRule<>(FilterDimension.AIRSPACE, EventData::affectedFir)
        );
    }
}
