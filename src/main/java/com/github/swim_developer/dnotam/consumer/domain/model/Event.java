package com.github.swim_developer.dnotam.consumer.domain.model;

import com.github.swim_developer.framework.application.model.OutboxDeliveryStatus;
import com.github.swim_developer.framework.domain.model.SwimOutboxEvent;
import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.codecs.pojo.annotations.BsonProperty;
import org.bson.types.ObjectId;

import java.time.Instant;

@RegisterForReflection
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@MongoEntity(collection = "dnotam_events")
public class Event implements SwimOutboxEvent {

    @BsonId
    private ObjectId id;
    private String subscriptionId;
    private String queueName;
    private String messageId;
    private String inboxId;
    private int messageIndex;
    private String contentHash;
    @BsonProperty("kafkaStatus")
    private OutboxDeliveryStatus deliveryStatus;
    @BsonProperty("kafkaRetryCount")
    private int outboxRetryCount;
    private String rawPayload;
    private Instant receivedAt;
    private Instant processedAt;
    @BsonProperty("sentToKafkaAt")
    private Instant dispatchedAt;
}
