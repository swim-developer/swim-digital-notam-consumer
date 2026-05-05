package com.github.swim_developer.dnotam.consumer.infrastructure.out.persistence;

import com.github.swim_developer.dnotam.consumer.domain.model.Event;
import com.github.swim_developer.dnotam.consumer.application.port.out.EventStore;
import com.github.swim_developer.framework.application.model.OutboxDeliveryStatus;
import com.github.swim_developer.framework.consumer.application.port.out.SwimEventCountPort;
import com.github.swim_developer.framework.consumer.application.port.out.SwimOutboxEventStorePort;
import com.github.swim_developer.framework.consumer.infrastructure.out.idempotency.SwimIdempotencyEventPort;
import com.github.swim_developer.framework.domain.model.SwimOutboxEvent;
import io.quarkus.mongodb.panache.PanacheMongoRepository;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class MongoEventStore implements PanacheMongoRepository<Event>, EventStore, SwimIdempotencyEventPort, SwimOutboxEventStorePort, SwimEventCountPort {

    private static final String FIELD_SUBSCRIPTION_ID = "subscriptionId";
    private static final String FIELD_DELIVERY_STATUS = "kafkaStatus";
    private static final int FIRST_PAGE = 0;

    @Override
    public List<Event> listAllDomain() {
        return listAll();
    }

    @Override
    public Optional<Event> findById(String id) {
        return findByIdOptional(new ObjectId(id));
    }

    @Override
    public Optional<Event> findByMessageId(String messageId) {
        return find("messageId", messageId).firstResultOptional();
    }

    @Override
    public List<Event> findBySubscriptionId(String subscriptionId) {
        return find(FIELD_SUBSCRIPTION_ID, subscriptionId).list();
    }

    @Override
    public List<Event> findBySubscriptionIdPaginated(String subscriptionId, int page, int size) {
        return find(FIELD_SUBSCRIPTION_ID, subscriptionId)
                .page(Page.of(page, size))
                .list();
    }

    @Override
    public long countBySubscriptionId(String subscriptionId) {
        return count(FIELD_SUBSCRIPTION_ID, subscriptionId);
    }

    @Override
    public List<Event> findBySubscriptionIdAndDateRange(String subscriptionId, Instant startDate, Instant endDate, int page, int size) {
        return find(FIELD_SUBSCRIPTION_ID + " = ?1 and receivedAt >= ?2 and receivedAt <= ?3", subscriptionId, startDate, endDate)
                .page(Page.of(page, size))
                .list();
    }

    @Override
    public long countBySubscriptionIdAndDateRange(String subscriptionId, Instant startDate, Instant endDate) {
        return count(FIELD_SUBSCRIPTION_ID + " = ?1 and receivedAt >= ?2 and receivedAt <= ?3", subscriptionId, startDate, endDate);
    }

    @Override
    public boolean existsByContentHash(String contentHash) {
        return count("contentHash", contentHash) > 0;
    }

    @Override
    public boolean existsBySubscriptionAndContentHash(String subscriptionId, String contentHash) {
        return count(FIELD_SUBSCRIPTION_ID + " = ?1 and contentHash = ?2", subscriptionId, contentHash) > 0;
    }

    @Override
    public List<Event> findByDeliveryStatus(OutboxDeliveryStatus status) {
        return find(FIELD_DELIVERY_STATUS, status).list();
    }

    @Override
    public long updateDeliveryStatusByMessageId(String messageId, OutboxDeliveryStatus status) {
        return update(FIELD_DELIVERY_STATUS, status).where("messageId", messageId);
    }

    @Override
    public List<Event> findPendingDispatch(int limit) {
        return find(FIELD_DELIVERY_STATUS, OutboxDeliveryStatus.PENDING)
                .page(Page.of(FIRST_PAGE, limit))
                .list();
    }

    @Override
    public List<String> findRecentContentHashes(Instant since, int limit) {
        return find("receivedAt >= ?1", since)
                .page(Page.of(FIRST_PAGE, limit))
                .project(EventHashProjection.class)
                .list()
                .stream()
                .map(EventHashProjection::getContentHash)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Override
    public List<String> findRecentCacheKeys(Instant since, int limit) {
        return find("receivedAt >= ?1", since)
                .page(Page.of(FIRST_PAGE, limit))
                .project(EventHashProjection.class)
                .list()
                .stream()
                .filter(p -> p.getSubscriptionId() != null && p.getContentHash() != null)
                .map(p -> p.getSubscriptionId() + ":" + p.getContentHash())
                .toList();
    }

    @Override
    public long countAll() {
        return count();
    }

    @Override
    public long countEvents() {
        return count();
    }

    @Override
    public void persist(Event event) {
        PanacheMongoRepository.super.persist(event);
    }

    @Override
    public void persist(List<Event> events) {
        PanacheMongoRepository.super.persist(events.stream());
    }

    @Override
    public void update(Event event) {
        PanacheMongoRepository.super.persistOrUpdate(event);
    }

    public Event findEventById(String id) {
        return findByIdOptional(new ObjectId(id)).orElse(null);
    }

    @Override
    public List<? extends SwimOutboxEvent> findPendingOutboxEvents(int batchSize) {
        return findPendingDispatch(batchSize);
    }

    @Override
    public void updateOutboxEvent(SwimOutboxEvent event) {
        PanacheMongoRepository.super.persistOrUpdate((Event) event);
    }
}
