package com.github.swim_developer.dnotam.consumer.application.port.out;

import com.github.swim_developer.dnotam.consumer.domain.model.Event;
import com.github.swim_developer.framework.application.model.OutboxDeliveryStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EventStore {

    void persist(Event event);

    List<Event> listAllDomain();

    Optional<Event> findById(String id);

    Optional<Event> findByMessageId(String messageId);

    List<Event> findBySubscriptionId(String subscriptionId);

    List<Event> findBySubscriptionIdPaginated(String subscriptionId, int page, int size);

    long countBySubscriptionId(String subscriptionId);

    List<Event> findBySubscriptionIdAndDateRange(
            String subscriptionId, Instant startDate, Instant endDate, int page, int size);

    long countBySubscriptionIdAndDateRange(
            String subscriptionId, Instant startDate, Instant endDate);

    boolean existsByContentHash(String contentHash);

    boolean existsBySubscriptionAndContentHash(String subscriptionId, String contentHash);

    List<Event> findByDeliveryStatus(OutboxDeliveryStatus status);

    long updateDeliveryStatusByMessageId(String messageId, OutboxDeliveryStatus status);

    List<Event> findPendingDispatch(int limit);

    List<String> findRecentContentHashes(Instant since, int limit);

    List<String> findRecentCacheKeys(Instant since, int limit);

    long countAll();

    void persist(List<Event> events);

    void update(Event event);
}
