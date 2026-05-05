package com.github.swim_developer.dnotam.consumer.infrastructure.out.persistence.repository;

import com.github.swim_developer.dnotam.consumer.infrastructure.out.persistence.document.SubscriptionDocument;
import com.github.swim_developer.framework.persistence.mongodb.AbstractMongoSubscriptionRepository;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SubscriptionDocumentRepository extends AbstractMongoSubscriptionRepository<SubscriptionDocument> {
}
