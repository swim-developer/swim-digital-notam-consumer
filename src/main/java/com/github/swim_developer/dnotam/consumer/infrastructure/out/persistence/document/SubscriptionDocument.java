package com.github.swim_developer.dnotam.consumer.infrastructure.out.persistence.document;

import com.github.swim_developer.dnotam.consumer.domain.model.Subscription;
import com.github.swim_developer.framework.persistence.mongodb.MongoSubscriptionDocumentPort;
import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.List;

@RegisterForReflection
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@MongoEntity(collection = "subscriptions")
public class SubscriptionDocument implements MongoSubscriptionDocumentPort {

    private ObjectId id;
    private String subscriptionId;
    private String queueName;
    private String subscriptionStatus;
    private String topic;
    private List<String> eventScenario;
    private List<String> airportHeliport;
    private List<String> airspace;
    private String eventSeries;
    private String publisher;
    private String comment;
    private String description;
    private String type;
    private String configHash;
    private Instant subscriptionEnd;
    private String providerName;
    private String providerId;
    private String heartbeatQueue;

    public static SubscriptionDocument fromDomain(Subscription subscription) {
        SubscriptionDocument doc = new SubscriptionDocument();
        doc.setId(subscription.getId() != null ? new ObjectId(subscription.getId()) : null);
        doc.setSubscriptionId(subscription.getSubscriptionId());
        doc.setQueueName(subscription.getQueueName());
        doc.setSubscriptionStatus(subscription.getSubscriptionStatus());
        doc.setTopic(subscription.getTopic());
        doc.setEventScenario(subscription.getEventScenario());
        doc.setAirportHeliport(subscription.getAirportHeliport());
        doc.setAirspace(subscription.getAirspace());
        doc.setEventSeries(subscription.getEventSeries());
        doc.setPublisher(subscription.getPublisher());
        doc.setComment(subscription.getComment());
        doc.setDescription(subscription.getDescription());
        doc.setType(subscription.getType());
        doc.setConfigHash(subscription.getConfigHash());
        doc.setSubscriptionEnd(subscription.getSubscriptionEnd());
        doc.setProviderName(subscription.getProviderName());
        doc.setProviderId(subscription.getProviderId());
        doc.setHeartbeatQueue(subscription.getHeartbeatQueue());
        return doc;
    }

    public Subscription toDomain() {
        Subscription subscription = new Subscription();
        subscription.setId(id != null ? id.toHexString() : null);
        subscription.setSubscriptionId(subscriptionId);
        subscription.setQueueName(queueName);
        subscription.setSubscriptionStatus(subscriptionStatus);
        subscription.setTopic(topic);
        subscription.setEventScenario(eventScenario);
        subscription.setAirportHeliport(airportHeliport);
        subscription.setAirspace(airspace);
        subscription.setEventSeries(eventSeries);
        subscription.setPublisher(publisher);
        subscription.setComment(comment);
        subscription.setDescription(description);
        subscription.setType(type);
        subscription.setConfigHash(configHash);
        subscription.setSubscriptionEnd(subscriptionEnd);
        subscription.setProviderName(providerName);
        subscription.setProviderId(providerId);
        subscription.setHeartbeatQueue(heartbeatQueue);
        return subscription;
    }
}
