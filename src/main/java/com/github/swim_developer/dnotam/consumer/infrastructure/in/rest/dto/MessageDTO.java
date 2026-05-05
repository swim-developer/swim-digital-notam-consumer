package com.github.swim_developer.dnotam.consumer.infrastructure.in.rest.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

@RegisterForReflection
@Schema(description = "DNOTAM event message")
public record MessageDTO(
        @Schema(description = "MongoDB document ID")
        String id,

        @Schema(description = "Message ID from AMQP", required = true)
        String messageId,

        @Schema(description = "Subscription ID", required = true)
        String subscriptionId,

        @Schema(description = "AMQP queue name", required = true)
        String queueName,

        @Schema(description = "Raw XML payload", required = true)
        String rawPayload,

        @Schema(description = "Timestamp when message was received from AMQP", required = true)
        Instant receivedAt,

        @Schema(description = "Timestamp when message was processed", required = true)
        Instant processedAt
) {
}
