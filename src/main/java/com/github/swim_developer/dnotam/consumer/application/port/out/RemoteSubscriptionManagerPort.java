package com.github.swim_developer.dnotam.consumer.application.port.out;

import com.github.swim_developer.dnotam.consumer.domain.model.Subscription;
import com.github.swim_developer.dnotam.consumer.domain.model.command.SubscriptionCommand;
import com.github.swim_developer.framework.application.model.ProviderConfiguration;

public interface RemoteSubscriptionManagerPort {

    Subscription createSubscription(SubscriptionCommand command, ProviderConfiguration provider);

    String updateSubscriptionStatus(String subscriptionId, String newStatus, ProviderConfiguration provider);

    void deleteSubscription(String subscriptionId, ProviderConfiguration provider);
}
