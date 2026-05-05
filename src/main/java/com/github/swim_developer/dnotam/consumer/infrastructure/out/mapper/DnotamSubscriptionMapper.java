package com.github.swim_developer.dnotam.consumer.infrastructure.out.mapper;

import com.github.swim_developer.framework.consumer.infrastructure.out.dlq.DeadLetterMessage;
import com.github.swim_developer.dnotam.consumer.domain.model.Event;
import com.github.swim_developer.dnotam.consumer.infrastructure.in.rest.dto.MessageDTO;
import com.github.swim_developer.framework.infrastructure.out.messaging.DlqMessageDTO;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DnotamSubscriptionMapper {

    public MessageDTO toDTO(Event event) {
        return new MessageDTO(
                event.getId() != null ? event.getId().toHexString() : null,
                event.getMessageId(),
                event.getSubscriptionId(),
                event.getQueueName(),
                event.getRawPayload(),
                event.getReceivedAt(),
                event.getProcessedAt()
        );
    }

    public DlqMessageDTO toDTO(DeadLetterMessage dlq) {
        return new DlqMessageDTO(
                dlq.getId(),
                dlq.getAmqpMessageId(),
                dlq.getMessageIndex(),
                dlq.getSubscriptionId(),
                dlq.getQueueName(),
                dlq.getErrorType(),
                dlq.getErrorMessage(),
                dlq.getRawPayload(),
                dlq.getReceivedAt(),
                dlq.getFailedAt()
        );
    }
}
