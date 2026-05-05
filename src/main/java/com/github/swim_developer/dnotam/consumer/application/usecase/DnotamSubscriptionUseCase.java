package com.github.swim_developer.dnotam.consumer.application.usecase;

import com.github.swim_developer.dnotam.consumer.domain.model.FilterDimension;
import com.github.swim_developer.dnotam.consumer.domain.model.Subscription;
import com.github.swim_developer.dnotam.consumer.domain.model.command.SubscriptionCommand;
import com.github.swim_developer.dnotam.consumer.application.port.in.ManageSubscriptionPort;
import com.github.swim_developer.dnotam.consumer.application.port.out.RemoteSubscriptionManagerPort;
import com.github.swim_developer.dnotam.consumer.application.port.out.SubscriptionStore;
import com.github.swim_developer.framework.domain.exception.SubscriptionNotFoundException;
import com.github.swim_developer.framework.consumer.application.subscription.service.AbstractSubscriptionService;
import com.github.swim_developer.framework.domain.model.SubscriptionStatus;
import com.github.swim_developer.framework.application.port.out.SwimConsumerManagerPort;
import com.github.swim_developer.framework.application.port.out.SwimProviderConfigPort;
import com.github.swim_developer.framework.application.port.out.SwimSubscriptionFilterPort;
import com.github.swim_developer.framework.application.model.ProviderConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.github.swim_developer.framework.domain.model.SubscriptionType.DECLARED;
import static com.github.swim_developer.framework.domain.model.SubscriptionType.ON_DEMAND;

@Slf4j
@ApplicationScoped
public class DnotamSubscriptionUseCase extends AbstractSubscriptionService<SubscriptionCommand, Subscription>
        implements ManageSubscriptionPort {

    private static final Pattern QUEUE_NAME_PATTERN = Pattern.compile("^DNOTAM-[^-]+-[a-f0-9\\-]+$");

    private final SubscriptionStore repository;
    private final RemoteSubscriptionManagerPort smPort;
    private final SwimProviderConfigPort providerConfigParser;
    private final SwimSubscriptionFilterPort filterCache;

    DnotamSubscriptionUseCase() {
        this.repository = null;
        this.smPort = null;
        this.providerConfigParser = null;
        this.filterCache = null;
    }

    @Inject
    public DnotamSubscriptionUseCase(SubscriptionStore repository,
                              RemoteSubscriptionManagerPort smPort,
                              SwimProviderConfigPort providerConfigParser,
                              SwimConsumerManagerPort consumerManager,
                              SwimSubscriptionFilterPort filterCache) {
        super(consumerManager);
        this.repository = repository;
        this.smPort = smPort;
        this.providerConfigParser = providerConfigParser;
        this.filterCache = filterCache;
    }

    public void resetAllSubscriptions(boolean deleteAndRecreate) {
        if (deleteAndRecreate) {
            repository.deleteAllSubscriptions();
            filterCache.clear();
            log.info("All subscriptions deleted (delete-and-recreate mode)");
        }
    }

    @Override
    protected List<Subscription> findActiveSubscriptions() {
        return repository.findActiveSubscriptions();
    }

    @Override
    protected void onAllConsumersRegistered() {
        populateFilterCache();
    }

    public void populateFilterCache() {
        repository.findActiveSubscriptions().forEach(this::cacheFilters);
        log.info("Subscription filter cache populated with {} entries", filterCache.size());
    }

    @Override
    public Subscription createSubscription(SubscriptionCommand command) {
        String configHash = command.generateConfigHash();

        Optional<Subscription> existing = repository.findByConfigHash(configHash);
        if (existing.isPresent()) {
            log.warn("Subscription with same configuration already exists: {}", existing.get().getSubscriptionId());
            return existing.get();
        }

        ProviderConfiguration provider = resolveProvider(command.provider());
        Subscription subscription = smPort.createSubscription(command, provider);

        if (subscription.getSubscriptionStatus() != null
                && !SubscriptionStatus.PAUSED.name().equals(subscription.getSubscriptionStatus())) {
            log.warn("Subscription Manager violated spec: initial status is '{}', expected 'PAUSED' (subscriptionId: {})",
                    subscription.getSubscriptionStatus(), subscription.getSubscriptionId());
        }

        if (subscription.getQueueName() != null && !QUEUE_NAME_PATTERN.matcher(subscription.getQueueName()).matches()) {
            log.warn("Queue name doesn't match pattern DNOTAM-<userId>-<uuid>: '{}' (subscriptionId: {})",
                    subscription.getQueueName(), subscription.getSubscriptionId());
        }

        subscription.setType(ON_DEMAND.name());
        subscription.setConfigHash(configHash);
        applyFilterFallback(subscription, command);
        repository.persistSubscription(subscription);

        cacheFilters(subscription);
        activateSubscription(subscription.getSubscriptionId(), subscription.getQueueName(), provider);
        return repository.findBySubscriptionId(subscription.getSubscriptionId()).orElse(subscription);
    }

    @Override
    protected Subscription callCreateAndPersist(SubscriptionCommand desired) {
        String configHash = desired.generateConfigHash();
        ProviderConfiguration provider = resolveProvider(desired.provider());
        Subscription remote = smPort.createSubscription(desired, provider);

        if (remote.getSubscriptionStatus() != null
                && !SubscriptionStatus.PAUSED.name().equals(remote.getSubscriptionStatus())) {
            log.warn("Subscription Manager violated spec: initial status is '{}', expected 'PAUSED' (subscriptionId: {})",
                    remote.getSubscriptionStatus(), remote.getSubscriptionId());
        }

        if (remote.getQueueName() != null && !QUEUE_NAME_PATTERN.matcher(remote.getQueueName()).matches()) {
            log.warn("Queue name doesn't match pattern DNOTAM-<userId>-<uuid>: '{}' (subscriptionId: {})",
                    remote.getQueueName(), remote.getSubscriptionId());
        }

        Optional<Subscription> duplicate = repository.findBySubscriptionId(remote.getSubscriptionId());
        if (duplicate.isPresent()) {
            Subscription existing = duplicate.get();
            existing.setQueueName(remote.getQueueName());
            existing.setSubscriptionStatus(remote.getSubscriptionStatus());
            existing.setTopic(remote.getTopic());
            existing.setEventScenario(remote.getEventScenario());
            existing.setAirportHeliport(remote.getAirportHeliport());
            existing.setAirspace(remote.getAirspace());
            existing.setEventSeries(remote.getEventSeries());
            existing.setPublisher(remote.getPublisher());
            existing.setComment(remote.getComment());
            existing.setDescription(remote.getDescription());
            existing.setConfigHash(configHash);
            existing.setProviderId(desired.provider());
            applyFilterFallback(existing, desired);
            repository.updateSubscription(existing);
            cacheFilters(existing);
            return existing;
        }

        remote.setType(DECLARED.name());
        remote.setConfigHash(configHash);
        applyFilterFallback(remote, desired);
        repository.persistSubscription(remote);
        cacheFilters(remote);
        return remote;
    }

    @Override
    protected String callUpdateStatus(String subscriptionId, String newStatus) {
        try {
            ProviderConfiguration provider = resolveProviderForSubscription(subscriptionId);
            return smPort.updateSubscriptionStatus(subscriptionId, newStatus, provider);
        } catch (WebApplicationException e) {
            int status = e.getResponse().getStatus();
            if (status == 404 || status == 410) {
                throw new SubscriptionNotFoundException(subscriptionId, e);
            }
            throw e;
        }
    }

    @Override
    protected void callDeleteRemoteSubscription(String subscriptionId) {
        ProviderConfiguration provider = resolveProviderForSubscription(subscriptionId);
        smPort.deleteSubscription(subscriptionId, provider);
    }

    @Override
    protected boolean existsLocally(SubscriptionCommand desired) {
        String configHash = desired.generateConfigHash();
        return repository.findByConfigHashAndType(configHash, DECLARED.name()).isPresent();
    }

    @Override
    public Optional<Subscription> findBySubscriptionId(String subscriptionId) {
        return repository.findBySubscriptionId(subscriptionId);
    }

    @Override
    protected List<Subscription> loadDeclaredSubscriptions() {
        return repository.findDeclaredSubscriptions();
    }

    @Override
    protected boolean isStillDesired(Subscription current, List<SubscriptionCommand> desiredSubscriptions) {
        return desiredSubscriptions.stream()
                .anyMatch(desired -> desired.generateConfigHash().equals(current.getConfigHash()));
    }

    @Override
    protected void deleteLocalSubscription(String subscriptionId) {
        filterCache.removeSubscription(subscriptionId);
        repository.deleteBySubscriptionId(subscriptionId);
    }

    @Override
    protected void updateLocalStatus(String subscriptionId, String status) {
        repository.updateStatus(subscriptionId, status);
    }

    @Override
    protected String describeDesired(SubscriptionCommand desired) {
        return desired.description();
    }

    @Override
    protected Optional<SubscriptionCommand> toDesiredConfig(Subscription subscription) {
        return Optional.of(new SubscriptionCommand(
                subscription.getTopic(),
                null,
                subscription.getEventScenario(),
                subscription.getAirportHeliport(),
                subscription.getAirspace(),
                subscription.getEventSeries(),
                subscription.getPublisher(),
                subscription.getProviderId(),
                subscription.getDescription(),
                subscription.getComment()
        ));
    }

    @Override
    public ProviderConfiguration resolveProvider(String providerId) {
        return providerConfigParser.findByProviderIdOrDefault(providerId)
                .orElseThrow(() -> new IllegalStateException("Provider not configured: " + providerId));
    }

    private ProviderConfiguration resolveProviderForSubscription(String subscriptionId) {
        Subscription subscription = repository.findBySubscriptionId(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + subscriptionId));
        return resolveProvider(subscription.getProviderId());
    }

    private void applyFilterFallback(Subscription subscription, SubscriptionCommand command) {
        if (subscription.getEventScenario() == null || subscription.getEventScenario().isEmpty()) {
            subscription.setEventScenario(command.eventScenario());
        }
        if (subscription.getAirportHeliport() == null || subscription.getAirportHeliport().isEmpty()) {
            subscription.setAirportHeliport(command.airportHeliport());
        }
        if (subscription.getAirspace() == null || subscription.getAirspace().isEmpty()) {
            subscription.setAirspace(command.airspace());
        }
    }

    private void cacheFilters(Subscription subscription) {
        String id = subscription.getSubscriptionId();
        filterCache.updateFilters(id, FilterDimension.EVENT_SCENARIO, subscription.getEventScenario());
        filterCache.updateFilters(id, FilterDimension.AIRPORT_HELIPORT, subscription.getAirportHeliport());
        filterCache.updateFilters(id, FilterDimension.AIRSPACE, subscription.getAirspace());
    }
}
