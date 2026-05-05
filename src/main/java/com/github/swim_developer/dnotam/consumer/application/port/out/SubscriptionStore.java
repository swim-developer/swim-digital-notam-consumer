package com.github.swim_developer.dnotam.consumer.application.port.out;

import com.github.swim_developer.dnotam.consumer.domain.model.Subscription;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SubscriptionStore {

    void persistSubscription(Subscription subscription);

    void updateSubscription(Subscription subscription);

    boolean deleteBySubscriptionId(String subscriptionId);

    void updateStatus(String subscriptionId, String newStatus);

    Optional<Subscription> findBySubscriptionId(String subscriptionId);

    List<Subscription> findAllSubscriptions();

    List<Subscription> findActiveSubscriptions();

    List<Subscription> findDeclaredSubscriptions();

    Optional<Subscription> findByQueueName(String queueName);

    Optional<Subscription> findByConfigHashAndType(String configHash, String type);

    Optional<Subscription> findByConfigHash(String configHash);

    List<Subscription> findBySubscriptionEndBefore(Instant threshold);

    long countSubscriptions();

    void deleteAllSubscriptions();
}
