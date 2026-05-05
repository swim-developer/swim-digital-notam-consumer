package com.github.swim_developer.dnotam.consumer.infrastructure.out.client;

import com.github.swim_developer.dnotam.consumer.domain.model.Subscription;
import com.github.swim_developer.dnotam.consumer.domain.model.command.SubscriptionCommand;
import com.github.swim_developer.dnotam.consumer.application.port.out.RemoteSubscriptionManagerPort;
import com.github.swim_developer.framework.consumer.application.port.out.SwimRemoteFeatureQueryPort;
import com.github.swim_developer.dnotam.consumer.infrastructure.in.rest.dto.SubscriptionRequest;
import com.github.swim_developer.dnotam.consumer.infrastructure.in.rest.dto.SubscriptionResponse;
import com.github.swim_developer.framework.consumer.infrastructure.out.client.AbstractSubscriptionManagerClientRegistry;
import com.github.swim_developer.framework.application.model.ProviderConfiguration;
import com.github.swim_developer.framework.application.model.SubscriptionStatusUpdate;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DnotamSubscriptionManagerAdapter
        extends AbstractSubscriptionManagerClientRegistry<SubscriptionManagerRestClient>
        implements RemoteSubscriptionManagerPort, SwimRemoteFeatureQueryPort {

    @Override
    protected Class<SubscriptionManagerRestClient> getClientClass() {
        return SubscriptionManagerRestClient.class;
    }

    @Override
    public Subscription createSubscription(SubscriptionCommand command, ProviderConfiguration provider) {
        SubscriptionManagerRestClient client = getOrCreate(provider);
        SubscriptionRequest request = toRequest(command);
        SubscriptionResponse response = executeWithRetry(provider, "createSubscription",
                () -> client.createSubscription(request));
        return fromResponse(response, command.provider());
    }

    @Override
    public String updateSubscriptionStatus(String subscriptionId, String newStatus, ProviderConfiguration provider) {
        SubscriptionManagerRestClient client = getOrCreate(provider);
        SubscriptionStatusUpdate update = new SubscriptionStatusUpdate(newStatus);
        SubscriptionResponse response = client.updateSubscriptionStatus(subscriptionId, update);
        return response != null && response.subscriptionStatus() != null
                ? response.subscriptionStatus()
                : newStatus;
    }

    @Override
    public void deleteSubscription(String subscriptionId, ProviderConfiguration provider) {
        SubscriptionManagerRestClient client = getOrCreate(provider);
        client.deleteSubscription(subscriptionId);
    }

    @Override
    public String queryFeatures(String typeName, String filter, String validTime, ProviderConfiguration provider) {
        SubscriptionManagerRestClient client = getOrCreate(provider);
        return executeWithRetry(provider, "getFeatures",
                () -> client.getFeatures(typeName, filter, validTime));
    }

    private static SubscriptionRequest toRequest(SubscriptionCommand command) {
        return new SubscriptionRequest(
                command.topic(),
                command.queueName(),
                command.eventScenario(),
                command.airportHeliport(),
                command.airspace(),
                command.eventSeries(),
                command.publisher(),
                command.provider(),
                command.description(),
                command.comment()
        );
    }

    @Override
    public String querySubscriptionStatus(String subscriptionId, ProviderConfiguration provider) {
        SubscriptionManagerRestClient client = getOrCreate(provider);
        SubscriptionResponse response = client.getSubscriptionDetails(subscriptionId);
        return response != null && response.subscriptionStatus() != null
                ? response.subscriptionStatus()
                : "UNKNOWN";
    }

    private static Subscription fromResponse(SubscriptionResponse response, String providerId) {
        Subscription subscription = new Subscription();
        subscription.setSubscriptionId(response.subscriptionId());
        subscription.setQueueName(response.queueName());
        subscription.setSubscriptionStatus(response.subscriptionStatus());
        subscription.setTopic(response.topic());
        subscription.setEventScenario(response.eventScenario());
        subscription.setAirportHeliport(response.airportHeliport());
        subscription.setAirspace(response.airspace());
        subscription.setEventSeries(response.eventSeries());
        subscription.setPublisher(response.publisher());
        subscription.setComment(response.comment());
        subscription.setDescription(response.description());
        subscription.setSubscriptionEnd(response.subscriptionEnd());
        subscription.setProviderName(response.providerName());
        subscription.setProviderId(providerId);
        subscription.setHeartbeatQueue(response.heartbeatQueue());
        return subscription;
    }
}
