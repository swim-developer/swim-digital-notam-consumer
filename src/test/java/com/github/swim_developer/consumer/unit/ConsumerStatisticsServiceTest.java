package com.github.swim_developer.consumer.unit;

import com.github.swim_developer.framework.consumer.application.port.in.ConsumerStats;
import com.github.swim_developer.framework.consumer.application.port.in.ConsumerStatisticsPort;
import com.github.swim_developer.framework.consumer.application.port.out.DeadLetterStore;
import com.github.swim_developer.framework.consumer.application.port.out.SwimEventCountPort;
import com.github.swim_developer.framework.consumer.application.port.out.SwimSubscriptionCountPort;
import com.github.swim_developer.framework.consumer.application.usecase.DefaultConsumerStatisticsService;
import com.github.swim_developer.framework.infrastructure.testing.TestNameLoggerExtension;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(TestNameLoggerExtension.class)
class ConsumerStatisticsServiceTest {

    private SwimEventCountPort eventCountPort;
    private DeadLetterStore deadLetterStore;
    private SwimSubscriptionCountPort subscriptionCountPort;
    private ConsumerStatisticsPort service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        eventCountPort = mock(SwimEventCountPort.class);
        deadLetterStore = mock(DeadLetterStore.class);
        subscriptionCountPort = mock(SwimSubscriptionCountPort.class);

        Instance<SwimEventCountPort> eventInstance = mock(Instance.class);
        when(eventInstance.isResolvable()).thenReturn(true);
        when(eventInstance.get()).thenReturn(eventCountPort);

        Instance<SwimSubscriptionCountPort> subInstance = mock(Instance.class);
        when(subInstance.isResolvable()).thenReturn(true);
        when(subInstance.get()).thenReturn(subscriptionCountPort);

        service = new DefaultConsumerStatisticsService(eventInstance, deadLetterStore, subInstance);
    }

    @Test
    void getStatistics_returnsAggregatedCounts() {
        when(eventCountPort.countEvents()).thenReturn(42L);
        when(deadLetterStore.countAll()).thenReturn(3L);
        when(subscriptionCountPort.countActiveSubscriptions()).thenReturn(2L);
        when(subscriptionCountPort.countTotalSubscriptions()).thenReturn(5L);

        ConsumerStats stats = service.getStatistics();

        assertThat(stats.totalEvents()).isEqualTo(42L);
        assertThat(stats.totalDlq()).isEqualTo(3L);
        assertThat(stats.activeSubscriptions()).isEqualTo(2L);
        assertThat(stats.totalSubscriptions()).isEqualTo(5L);
    }

    @Test
    void getStatistics_returnsZeros_whenStoresAreEmpty() {
        when(eventCountPort.countEvents()).thenReturn(0L);
        when(deadLetterStore.countAll()).thenReturn(0L);
        when(subscriptionCountPort.countActiveSubscriptions()).thenReturn(0L);
        when(subscriptionCountPort.countTotalSubscriptions()).thenReturn(0L);

        ConsumerStats stats = service.getStatistics();

        assertThat(stats.totalEvents()).isZero();
        assertThat(stats.totalDlq()).isZero();
        assertThat(stats.activeSubscriptions()).isZero();
        assertThat(stats.totalSubscriptions()).isZero();
    }
}
