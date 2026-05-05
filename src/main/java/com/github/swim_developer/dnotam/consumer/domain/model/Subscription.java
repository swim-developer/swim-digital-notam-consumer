package com.github.swim_developer.dnotam.consumer.domain.model;

import com.github.swim_developer.framework.domain.model.SwimConsumerSubscription;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class Subscription implements SwimConsumerSubscription {

    private String id;
    private String subscriptionId;
    private String queueName;
    private String subscriptionStatus;
    private String topic;
    private List<String> eventScenario;
    private List<String> airportHeliport;
    private List<String> airspace;
    private String eventSeries;
    private String publisher;
    private String comment;
    private String description;
    private String type;
    private String configHash;
    private Instant subscriptionEnd;
    private String providerName;
    private String providerId;
    private String heartbeatQueue;

    @Override
    public Map<String, Set<String>> projectFilterDimensions() {
        Map<String, Set<String>> dimensions = new HashMap<>();
        dimensions.put(FilterDimension.EVENT_SCENARIO, toSet(eventScenario));
        dimensions.put(FilterDimension.AIRPORT_HELIPORT, toSet(airportHeliport));
        dimensions.put(FilterDimension.AIRSPACE, toSet(airspace));
        return dimensions;
    }

    private Set<String> toSet(Collection<String> values) {
        return (values != null && !values.isEmpty()) ? Set.copyOf(values) : Set.of();
    }
}
