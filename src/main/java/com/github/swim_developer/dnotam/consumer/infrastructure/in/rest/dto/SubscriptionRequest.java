package com.github.swim_developer.dnotam.consumer.infrastructure.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.swim_developer.framework.infrastructure.util.HashUtil;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubscriptionRequest(
        String topic,
        String queueName,
        List<String> eventScenario,
        List<String> airportHeliport,
        List<String> airspace,
        String eventSeries,
        String publisher,
        String provider,
        String description,
        String comment
) {
    public String generateConfigHash() {
        String prov = provider != null ? provider : "";
        String scenarios = eventScenario != null ? String.join(",", eventScenario) : "";
        String airports = airportHeliport != null ? String.join(",", airportHeliport) : "";
        String airspaces = airspace != null ? String.join(",", airspace) : "";
        return HashUtil.sha256(prov + topic + scenarios + airports + airspaces);
    }
}

