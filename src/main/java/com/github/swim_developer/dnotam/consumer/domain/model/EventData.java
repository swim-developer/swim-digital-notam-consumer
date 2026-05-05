package com.github.swim_developer.dnotam.consumer.domain.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record EventData(
        EventScenario scenario,
        String location,
        String affectedFir
) {}
