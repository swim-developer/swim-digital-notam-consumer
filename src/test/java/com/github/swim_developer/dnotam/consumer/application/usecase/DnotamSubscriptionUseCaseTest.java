package com.github.swim_developer.dnotam.consumer.application.usecase;

import com.github.swim_developer.dnotam.consumer.application.port.out.RemoteSubscriptionManagerPort;
import com.github.swim_developer.dnotam.consumer.application.port.out.SubscriptionStore;
import com.github.swim_developer.dnotam.consumer.domain.model.FilterDimension;
import com.github.swim_developer.dnotam.consumer.domain.model.Subscription;
import com.github.swim_developer.dnotam.consumer.domain.model.command.SubscriptionCommand;
import com.github.swim_developer.framework.application.model.ProviderConfiguration;
import com.github.swim_developer.framework.application.port.out.SwimConsumerManagerPort;
import com.github.swim_developer.framework.application.port.out.SwimProviderConfigPort;
import com.github.swim_developer.framework.application.port.out.SwimSubscriptionFilterPort;
import com.github.swim_developer.framework.domain.model.SubscriptionStatus;
import com.github.swim_developer.framework.infrastructure.testing.TestNameLoggerExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(TestNameLoggerExtension.class)
class DnotamSubscriptionUseCaseTest {

    private SubscriptionStore repository;
    private RemoteSubscriptionManagerPort smPort;
    private SwimProviderConfigPort providerConfigParser;
    private SwimConsumerManagerPort consumerManager;
    private SwimSubscriptionFilterPort filterCache;
    private DnotamSubscriptionUseCase useCase;

    private static final ProviderConfiguration PROVIDER = ProviderConfiguration.builder()
            .providerId("default")
            .build();

    @BeforeEach
    void setUp() {
        repository = mock(SubscriptionStore.class);
        smPort = mock(RemoteSubscriptionManagerPort.class);
        providerConfigParser = mock(SwimProviderConfigPort.class);
        consumerManager = mock(SwimConsumerManagerPort.class);
        filterCache = mock(SwimSubscriptionFilterPort.class);
        useCase = new DnotamSubscriptionUseCase(repository, smPort, providerConfigParser, consumerManager, filterCache);
    }

    @Test
    void resetAllSubscriptions_deleteAndRecreate_deletesAndClearsCache() {
        useCase.resetAllSubscriptions(true);

        verify(repository).deleteAllSubscriptions();
        verify(filterCache).clear();
    }

    @Test
    void resetAllSubscriptions_noDeleteAndRecreate_doesNothing() {
        useCase.resetAllSubscriptions(false);

        verify(repository, never()).deleteAllSubscriptions();
        verify(filterCache, never()).clear();
    }

    @Test
    void populateFilterCache_updatesFiltersForAllActiveSubscriptions() {
        Subscription sub = subscriptionWith("sub-1", "queue-1");
        sub.setEventScenario(List.of("RWY.CLS"));
        sub.setAirportHeliport(List.of("LPPT"));
        sub.setAirspace(List.of("EGXX"));
        when(repository.findActiveSubscriptions()).thenReturn(List.of(sub));

        useCase.populateFilterCache();

        verify(filterCache).updateFilters("sub-1", FilterDimension.EVENT_SCENARIO, List.of("RWY.CLS"));
        verify(filterCache).updateFilters("sub-1", FilterDimension.AIRPORT_HELIPORT, List.of("LPPT"));
        verify(filterCache).updateFilters("sub-1", FilterDimension.AIRSPACE, List.of("EGXX"));
    }

    @Test
    void createSubscription_returnsExisting_whenConfigHashMatches() {
        SubscriptionCommand cmd = commandWith("provider-1");
        String configHash = cmd.generateConfigHash();
        Subscription existing = subscriptionWith("sub-existing", "queue-existing");
        when(repository.findByConfigHash(configHash)).thenReturn(Optional.of(existing));

        Subscription result = useCase.createSubscription(cmd);

        assertThat(result).isSameAs(existing);
        verify(smPort, never()).createSubscription(any(), any());
    }

    @Test
    void createSubscription_createsNew_whenNoExistingHash() {
        SubscriptionCommand cmd = commandWith("default");
        String configHash = cmd.generateConfigHash();

        Subscription remote = subscriptionWith("sub-new", "DNOTAM-user1-550e8400-e29b-41d4");
        remote.setSubscriptionStatus(SubscriptionStatus.PAUSED.name());
        remote.setProviderId("default");

        when(repository.findByConfigHash(configHash)).thenReturn(Optional.empty());
        when(providerConfigParser.findByProviderIdOrDefault("default")).thenReturn(Optional.of(PROVIDER));
        when(smPort.createSubscription(cmd, PROVIDER)).thenReturn(remote);
        when(smPort.updateSubscriptionStatus("sub-new", "ACTIVE", PROVIDER)).thenReturn("ACTIVE");
        when(repository.findBySubscriptionId("sub-new")).thenReturn(Optional.of(remote));

        Subscription result = useCase.createSubscription(cmd);

        assertThat(result).isNotNull();
        verify(repository).persistSubscription(remote);
    }

    @Test
    void findBySubscriptionId_delegatesToRepository() {
        Subscription sub = subscriptionWith("sub-1", "queue-1");
        when(repository.findBySubscriptionId("sub-1")).thenReturn(Optional.of(sub));

        Optional<Subscription> result = useCase.findBySubscriptionId("sub-1");

        assertThat(result).contains(sub);
    }

    @Test
    void findBySubscriptionId_returnsEmpty_whenNotFound() {
        when(repository.findBySubscriptionId("sub-unknown")).thenReturn(Optional.empty());

        Optional<Subscription> result = useCase.findBySubscriptionId("sub-unknown");

        assertThat(result).isEmpty();
    }

    @Test
    void resolveProvider_returnsProvider_whenConfigured() {
        when(providerConfigParser.findByProviderIdOrDefault("default")).thenReturn(Optional.of(PROVIDER));

        ProviderConfiguration result = useCase.resolveProvider("default");

        assertThat(result).isEqualTo(PROVIDER);
    }

    @Test
    void resolveProvider_throwsIllegalState_whenProviderNotFound() {
        when(providerConfigParser.findByProviderIdOrDefault("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.resolveProvider("unknown"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void toDesiredConfig_mapsSubscriptionToCommand() {
        Subscription sub = new Subscription();
        sub.setTopic("DNOTAM.v1.all");
        sub.setEventScenario(List.of("RWY.CLS"));
        sub.setAirportHeliport(List.of("LPPT"));
        sub.setAirspace(null);
        sub.setEventSeries("EVT-001");
        sub.setPublisher("EAD");
        sub.setProviderId("provider-x");
        sub.setDescription("test sub");
        sub.setComment("comment");

        Optional<SubscriptionCommand> cmd = useCase.toDesiredConfig(sub);

        assertThat(cmd).isPresent();
        assertThat(cmd.get().topic()).isEqualTo("DNOTAM.v1.all");
        assertThat(cmd.get().eventScenario()).containsExactly("RWY.CLS");
        assertThat(cmd.get().provider()).isEqualTo("provider-x");
        assertThat(cmd.get().description()).isEqualTo("test sub");
    }

    @Test
    void describeDesired_returnsCommandDescription() {
        SubscriptionCommand cmd = commandWith("default");

        String description = useCase.describeDesired(cmd);

        assertThat(description).isEqualTo("my test subscription");
    }

    @Test
    void isStillDesired_returnsTrue_whenHashMatches() {
        SubscriptionCommand desired = commandWith("default");
        Subscription current = subscriptionWith("sub-1", "queue-1");
        current.setConfigHash(desired.generateConfigHash());

        boolean result = useCase.isStillDesired(current, List.of(desired));

        assertThat(result).isTrue();
    }

    @Test
    void isStillDesired_returnsFalse_whenHashNotInList() {
        SubscriptionCommand desired = commandWith("default");
        Subscription current = subscriptionWith("sub-1", "queue-1");
        current.setConfigHash("some-other-hash");

        boolean result = useCase.isStillDesired(current, List.of(desired));

        assertThat(result).isFalse();
    }

    private Subscription subscriptionWith(String subscriptionId, String queueName) {
        Subscription sub = new Subscription();
        sub.setSubscriptionId(subscriptionId);
        sub.setQueueName(queueName);
        return sub;
    }

    private SubscriptionCommand commandWith(String providerId) {
        return new SubscriptionCommand(
                "DNOTAM.v1.all",
                null,
                List.of("RWY.CLS"),
                List.of("LPPT"),
                List.of("EGXX"),
                null,
                "EAD",
                providerId,
                "my test subscription",
                null
        );
    }
}
