package com.github.swim_developer.dnotam.consumer.infrastructure.in.rest.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record TopicDetailsResponse(
        String topicId,
        String title,
        String description,
        String eventScenario,
        List<String> aixmFeatures,
        List<String> mandatoryFor,
        List<String> useCase
) {
}

