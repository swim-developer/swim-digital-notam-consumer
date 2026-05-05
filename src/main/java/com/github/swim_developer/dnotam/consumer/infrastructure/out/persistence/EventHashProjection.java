package com.github.swim_developer.dnotam.consumer.infrastructure.out.persistence;

import com.github.swim_developer.dnotam.consumer.domain.model.Event;
import io.quarkus.mongodb.panache.common.ProjectionFor;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Getter;
import lombok.Setter;

@RegisterForReflection
@ProjectionFor(Event.class)
@Getter
@Setter
public class EventHashProjection {
    private String subscriptionId;
    private String contentHash;
}
