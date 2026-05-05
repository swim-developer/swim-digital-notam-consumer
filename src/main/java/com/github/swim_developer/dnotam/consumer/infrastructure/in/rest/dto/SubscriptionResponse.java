package com.github.swim_developer.dnotam.consumer.infrastructure.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubscriptionResponse(
        @JsonAlias("subscription_id")
        String subscriptionId,

        @JsonAlias("subscription_status")
        String subscriptionStatus,

        @JsonAlias("queue")
        String queueName,

        String topic,

        @JsonAlias("event_scenario")
        List<String> eventScenario,

        @JsonAlias("airport_heliport")
        List<String> airportHeliport,

        List<String> airspace,

        @JsonAlias("event_series")
        String eventSeries,

        String publisher,
        String comment,
        String description,
        Instant createdAt,
        Instant updatedAt,

        @JsonAlias("subscription_end")
        Instant subscriptionEnd,

        @JsonAlias("provider_name")
        String providerName,

        @JsonAlias("heartbeat_queue")
        String heartbeatQueue
) {
}

