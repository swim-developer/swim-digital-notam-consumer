package com.github.swim_developer.dnotam.consumer.infrastructure.in.rest;

import com.github.swim_developer.framework.consumer.application.port.in.ConsumerStatisticsPort;
import com.github.swim_developer.framework.consumer.application.port.out.DeadLetterStore;
import com.github.swim_developer.framework.consumer.infrastructure.in.rest.AbstractOperationalResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@ApplicationScoped
@Tag(name = "SWIM DNOTAM Operational API", description = "DLQ and statistics endpoints")
public class OperationalResource extends AbstractOperationalResource {

    public OperationalResource() {
        this(null, null);
    }

    @Inject
    public OperationalResource(DeadLetterStore dlqRepository,
                               ConsumerStatisticsPort statisticsPort) {
        super(dlqRepository, statisticsPort);
    }
}
