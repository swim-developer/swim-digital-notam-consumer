package com.github.swim_developer.dnotam.consumer.infrastructure.out.persistence;

import com.github.swim_developer.dnotam.consumer.domain.model.Subscription;
import com.github.swim_developer.dnotam.consumer.application.port.out.SubscriptionStore;
import com.github.swim_developer.dnotam.consumer.infrastructure.out.persistence.document.SubscriptionDocument;
import com.github.swim_developer.dnotam.consumer.infrastructure.out.persistence.repository.SubscriptionDocumentRepository;
import com.github.swim_developer.framework.application.port.out.SwimConsumerSubscriptionRepository;
import com.github.swim_developer.framework.consumer.application.port.out.SwimPersistenceHealthPort;
import com.github.swim_developer.framework.consumer.application.port.out.SwimSubscriptionCountPort;
import com.github.swim_developer.framework.consumer.application.port.out.SwimSubscriptionListPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class MongoSubscriptionStore implements SubscriptionStore, SwimConsumerSubscriptionRepository<Subscription>,
        SwimPersistenceHealthPort, SwimSubscriptionCountPort, SwimSubscriptionListPort {

    private final SubscriptionDocumentRepository repository;

    @Inject
    public MongoSubscriptionStore(SubscriptionDocumentRepository repository) {
        this.repository = repository;
    }

    @Override
    public long count() {
        return repository.count();
    }

    @Override
    public String getCollectionName() {
        return "subscriptions";
    }

    @Override
    public void persistSubscription(Subscription subscription) {
        repository.persistSubscription(SubscriptionDocument.fromDomain(subscription));
    }

    @Override
    public void updateSubscription(Subscription subscription) {
        repository.updateSubscription(SubscriptionDocument.fromDomain(subscription));
    }

    @Override
    public boolean deleteBySubscriptionId(String subscriptionId) {
        return repository.deleteBySubscriptionId(subscriptionId);
    }

    @Override
    public void updateStatus(String subscriptionId, String newStatus) {
        repository.updateStatus(subscriptionId, newStatus);
    }

    @Override
    public Optional<Subscription> findBySubscriptionId(String subscriptionId) {
        return repository.findBySubscriptionId(subscriptionId).map(SubscriptionDocument::toDomain);
    }

    @Override
    public List<Subscription> findAllSubscriptions() {
        return repository.findAllSubscriptions().stream().map(SubscriptionDocument::toDomain).toList();
    }

    @Override
    public List<Subscription> findActiveSubscriptions() {
        return repository.findActiveSubscriptions().stream().map(SubscriptionDocument::toDomain).toList();
    }

    @Override
    public List<Subscription> findDeclaredSubscriptions() {
        return repository.findDeclaredSubscriptions().stream().map(SubscriptionDocument::toDomain).toList();
    }

    @Override
    public Optional<Subscription> findByQueueName(String queueName) {
        return repository.findByQueueName(queueName).map(SubscriptionDocument::toDomain);
    }

    @Override
    public Optional<Subscription> findByConfigHashAndType(String configHash, String type) {
        return repository.findByConfigHashAndType(configHash, type).map(SubscriptionDocument::toDomain);
    }

    @Override
    public Optional<Subscription> findByConfigHash(String configHash) {
        return repository.findByConfigHash(configHash).map(SubscriptionDocument::toDomain);
    }

    @Override
    public List<Subscription> findBySubscriptionEndBefore(Instant threshold) {
        return repository.findBySubscriptionEndBefore(threshold).stream().map(SubscriptionDocument::toDomain).toList();
    }

    @Override
    public long countSubscriptions() {
        return repository.countSubscriptions();
    }

    @Override
    public void deleteAllSubscriptions() {
        repository.deleteAllSubscriptions();
    }

    @Override
    public void clearCache() {
        repository.clearCache();
    }

    @Override
    public long countActiveSubscriptions() {
        return findActiveSubscriptions().size();
    }

    @Override
    public long countTotalSubscriptions() {
        return countSubscriptions();
    }
}
