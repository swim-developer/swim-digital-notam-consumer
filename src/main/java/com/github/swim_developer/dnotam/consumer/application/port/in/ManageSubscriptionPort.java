package com.github.swim_developer.dnotam.consumer.application.port.in;

import com.github.swim_developer.dnotam.consumer.domain.model.Subscription;
import com.github.swim_developer.dnotam.consumer.domain.model.command.SubscriptionCommand;

import java.util.Optional;

public interface ManageSubscriptionPort {

    Subscription createSubscription(SubscriptionCommand command);

    Subscription pauseSubscription(String subscriptionId);

    Subscription resumeSubscription(String subscriptionId);

    void deleteSubscriptionById(String subscriptionId);

    Optional<Subscription> findBySubscriptionId(String subscriptionId);
}
