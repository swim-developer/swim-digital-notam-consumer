package com.github.swim_developer.consumer.unit;

import com.github.swim_developer.dnotam.consumer.domain.model.Event;
import com.github.swim_developer.dnotam.consumer.infrastructure.in.rest.dto.MessageDTO;
import com.github.swim_developer.dnotam.consumer.infrastructure.out.mapper.DnotamSubscriptionMapper;
import com.github.swim_developer.framework.consumer.infrastructure.out.dlq.DeadLetterMessage;
import com.github.swim_developer.framework.infrastructure.out.messaging.DlqMessageDTO;
import com.github.swim_developer.framework.infrastructure.testing.TestNameLoggerExtension;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(TestNameLoggerExtension.class)
class DnotamSubscriptionMapperTest {

    private DnotamSubscriptionMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new DnotamSubscriptionMapper();
    }

    @Test
    void toDTO_event_mapsAllFields() {
        ObjectId id = new ObjectId();
        Instant received = Instant.parse("2026-01-01T10:00:00Z");
        Instant processed = Instant.parse("2026-01-01T10:00:01Z");
        Event event = new Event(id, "sub-1", "queue-1", "msg-1", "inbox-1",
                0, "hash-abc", null, 0, "<xml/>", received, processed, null);

        MessageDTO dto = mapper.toDTO(event);

        assertThat(dto.id()).isEqualTo(id.toHexString());
        assertThat(dto.messageId()).isEqualTo("msg-1");
        assertThat(dto.subscriptionId()).isEqualTo("sub-1");
        assertThat(dto.queueName()).isEqualTo("queue-1");
        assertThat(dto.rawPayload()).isEqualTo("<xml/>");
        assertThat(dto.receivedAt()).isEqualTo(received);
        assertThat(dto.processedAt()).isEqualTo(processed);
    }

    @Test
    void toDTO_event_mapsNullId_asNull() {
        Event event = new Event(null, "sub-1", "queue-1", "msg-1", null,
                0, null, null, 0, "<xml/>", null, null, null);

        MessageDTO dto = mapper.toDTO(event);

        assertThat(dto.id()).isNull();
    }

    @Test
    void toDTO_deadLetterMessage_mapsAllFields() {
        Instant received = Instant.parse("2026-01-02T08:00:00Z");
        Instant failed = Instant.parse("2026-01-02T08:00:05Z");
        DeadLetterMessage dlq = new DeadLetterMessage(
                "dlq-id-1", "amqp-msg-1", 2, "sub-1", "queue-1",
                "<xml/>", "VALIDATION_ERROR", "invalid schema", "stacktrace...",
                received, failed);

        DlqMessageDTO dto = mapper.toDTO(dlq);

        assertThat(dto.id()).isEqualTo("dlq-id-1");
        assertThat(dto.amqpMessageId()).isEqualTo("amqp-msg-1");
        assertThat(dto.messageIndex()).isEqualTo(2);
        assertThat(dto.subscriptionId()).isEqualTo("sub-1");
        assertThat(dto.queueName()).isEqualTo("queue-1");
        assertThat(dto.errorType()).isEqualTo("VALIDATION_ERROR");
        assertThat(dto.errorMessage()).isEqualTo("invalid schema");
        assertThat(dto.rawPayload()).isEqualTo("<xml/>");
        assertThat(dto.receivedAt()).isEqualTo(received);
        assertThat(dto.failedAt()).isEqualTo(failed);
    }
}
