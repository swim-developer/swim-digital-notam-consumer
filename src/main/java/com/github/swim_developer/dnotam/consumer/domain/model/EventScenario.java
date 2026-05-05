package com.github.swim_developer.dnotam.consumer.domain.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record EventScenario(String value) {

    public EventScenario {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Event scenario cannot be null or blank");
        }
    }
}
