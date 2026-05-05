package com.github.swim_developer.dnotam.consumer.infrastructure.out.idempotency;

import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Declares the idempotency cache at build time so Quarkus registers it in native mode.
 * CacheManager.getCache("processed-messages") requires the cache to be declared via annotation.
 */
@ApplicationScoped
class DnotamIdempotencyCacheDeclaration {

    @CacheResult(cacheName = "processed-messages")
    String declare(String key) {
        return null;
    }
}
