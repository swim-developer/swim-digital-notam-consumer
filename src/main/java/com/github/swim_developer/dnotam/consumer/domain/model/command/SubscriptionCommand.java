package com.github.swim_developer.dnotam.consumer.domain.model.command;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@RegisterForReflection
public record SubscriptionCommand(
        String topic,
        String queueName,
        List<String> eventScenario,
        List<String> airportHeliport,
        List<String> airspace,
        String eventSeries,
        String publisher,
        String provider,
        String description,
        String comment
) {
    public String generateConfigHash() {
        String prov = provider != null ? provider : "";
        String scenarios = eventScenario != null ? String.join(",", eventScenario) : "";
        String airports = airportHeliport != null ? String.join(",", airportHeliport) : "";
        String airspaces = airspace != null ? String.join(",", airspace) : "";
        return sha256(prov + topic + scenarios + airports + airspaces);
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
