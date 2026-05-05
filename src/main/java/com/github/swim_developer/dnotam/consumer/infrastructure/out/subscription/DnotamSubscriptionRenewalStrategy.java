package com.github.swim_developer.dnotam.consumer.infrastructure.out.subscription;

import com.github.swim_developer.dnotam.consumer.domain.model.Subscription;
import com.github.swim_developer.dnotam.consumer.application.port.out.SubscriptionStore;
import com.github.swim_developer.dnotam.consumer.infrastructure.out.client.DnotamSubscriptionManagerAdapter;
import com.github.swim_developer.dnotam.consumer.infrastructure.out.client.SubscriptionManagerRestClient;
import com.github.swim_developer.dnotam.consumer.infrastructure.in.rest.dto.SubscriptionResponse;
import com.github.swim_developer.framework.consumer.infrastructure.out.config.provider.ProviderConfigParser;
import com.github.swim_developer.framework.application.model.ProviderConfiguration;
import com.github.swim_developer.framework.domain.model.SubscriptionRenewalInfo;
import com.github.swim_developer.framework.domain.exception.SubscriptionRenewalException;
import com.github.swim_developer.framework.domain.model.SubscriptionStatus;
import com.github.swim_developer.framework.application.port.out.SubscriptionRenewalStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;

@Slf4j
@ApplicationScoped
public class DnotamSubscriptionRenewalStrategy implements SubscriptionRenewalStrategy {

    private final SubscriptionStore subscriptionStore;
    private final DnotamSubscriptionManagerAdapter smClientRegistry;
    private final ProviderConfigParser providerConfigParser;

    @Inject
    public DnotamSubscriptionRenewalStrategy(SubscriptionStore subscriptionStore,
                          DnotamSubscriptionManagerAdapter smClientRegistry,
                          ProviderConfigParser providerConfigParser) {
        this.subscriptionStore = subscriptionStore;
        this.smClientRegistry = smClientRegistry;
        this.providerConfigParser = providerConfigParser;
    }

    @Override
    public List<SubscriptionRenewalInfo> findSubscriptionsNearExpiry(Instant threshold) {
        return subscriptionStore.findBySubscriptionEndBefore(threshold)
                .stream()
                .filter(sub -> SubscriptionStatus.ACTIVE.name().equals(sub.getSubscriptionStatus()))
                .map(sub -> new SubscriptionRenewalInfo(
                        sub.getSubscriptionId(),
                        sub.getSubscriptionEnd()
                ))
                .toList();
    }

    @Override
    public void renewSubscription(String subscriptionId) throws SubscriptionRenewalException {
        log.info("Calling subscription manager to renew subscription: {}", subscriptionId);

        Subscription subscription = subscriptionStore.findBySubscriptionId(subscriptionId)
                .orElseThrow(() -> new IllegalStateException("Subscription not found: " + subscriptionId));

        SubscriptionManagerRestClient client = resolveSmClient(subscription.getProviderId());
        SubscriptionResponse response = client.renewSubscription(subscriptionId);

        subscription.setSubscriptionEnd(response.subscriptionEnd());
        subscriptionStore.updateSubscription(subscription);

        log.info("Subscription renewed locally - ID: {}, New subscriptionEnd: {}",
                subscriptionId, response.subscriptionEnd());
    }

    private SubscriptionManagerRestClient resolveSmClient(String providerId) {
        ProviderConfiguration provider = providerConfigParser.findByProviderId(providerId)
                .orElseThrow(() -> new IllegalStateException("Provider not configured: " + providerId));
        return smClientRegistry.getOrCreate(provider);
    }
}
