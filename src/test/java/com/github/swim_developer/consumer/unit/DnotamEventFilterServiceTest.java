package com.github.swim_developer.consumer.unit;

import com.github.swim_developer.dnotam.consumer.application.service.DnotamEventFilterService;
import com.github.swim_developer.dnotam.consumer.domain.model.EventData;
import com.github.swim_developer.dnotam.consumer.domain.model.EventScenario;
import com.github.swim_developer.dnotam.consumer.domain.model.FilterDimension;
import com.github.swim_developer.framework.application.model.ProcessingContext;
import com.github.swim_developer.framework.consumer.infrastructure.out.dlq.AbstractDeadLetterService;
import com.github.swim_developer.framework.consumer.infrastructure.out.filter.SubscriptionFilterCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DnotamEventFilterServiceTest {

    private static final String SUB = "sub-1";

    private SubscriptionFilterCache filterCache;
    private AbstractDeadLetterService deadLetterService;
    private DnotamEventFilterService filterService;

    @BeforeEach
    void setup() {
        filterCache = new SubscriptionFilterCache();
        deadLetterService = mock(AbstractDeadLetterService.class);
        filterService = new DnotamEventFilterService(filterCache, deadLetterService);
    }

    private EventData event(String scenario, String location, String fir) {
        return new EventData(new EventScenario(scenario), location, fir);
    }

    @Test
    void allThreeDimensionsMatch_passes() {
        filterCache.updateFilters(SUB, FilterDimension.EVENT_SCENARIO, List.of("AD.CLS"));
        filterCache.updateFilters(SUB, FilterDimension.AIRPORT_HELIPORT, List.of("EADD"));
        filterCache.updateFilters(SUB, FilterDimension.AIRSPACE, List.of("EAAD"));

        assertThat(filterService.passesSubscriptionFilter(SUB, event("AD.CLS", "EADD", "EAAD")))
                .isTrue();
    }

    @ParameterizedTest
    @MethodSource("dimensionMismatchCases")
    void dimensionMismatch_rejectsWhenOneFilteredDimensionWrong(
            String scenarioFilter,
            String airportFilter,
            String airspaceFilter,
            String eventScenario,
            String eventLocation,
            String eventFir) {
        filterCache.updateFilters(SUB, FilterDimension.EVENT_SCENARIO, List.of(scenarioFilter));
        filterCache.updateFilters(SUB, FilterDimension.AIRPORT_HELIPORT, List.of(airportFilter));
        filterCache.updateFilters(SUB, FilterDimension.AIRSPACE, List.of(airspaceFilter));

        assertThat(filterService.passesSubscriptionFilter(SUB, event(eventScenario, eventLocation, eventFir)))
                .isFalse();
    }

    static Stream<Arguments> dimensionMismatchCases() {
        return Stream.of(
                Arguments.of("RWY.CLS", "EADD", "EAAD", "AD.CLS", "EADD", "EAAD"),
                Arguments.of("AD.CLS", "LPPT", "EAAD", "AD.CLS", "EADD", "EAAD"),
                Arguments.of("AD.CLS", "EADD", "LPPC", "AD.CLS", "EADD", "EAAD"));
    }

    @Test
    void noFiltersRegistered_allowsEverything() {
        assertThat(filterService.passesSubscriptionFilter(SUB, event("SAA.ACT", "EHAM", "EHAA")))
                .isTrue();
    }

    @Test
    void onlyScenarioRegistered_airportAndAirspaceIgnored() {
        filterCache.updateFilters(SUB, FilterDimension.EVENT_SCENARIO, List.of("RWY.CLS"));

        assertThat(filterService.passesSubscriptionFilter(SUB, event("RWY.CLS", "ANYTHING", "ANYFIR")))
                .isTrue();
    }

    @Test
    void nullLocationBypassesAirportFilter() {
        filterCache.updateFilters(SUB, FilterDimension.EVENT_SCENARIO, List.of("AD.CLS"));
        filterCache.updateFilters(SUB, FilterDimension.AIRPORT_HELIPORT, List.of("LPPT"));

        assertThat(filterService.passesSubscriptionFilter(SUB, event("AD.CLS", null, null)))
                .isTrue();
    }

    @Test
    void nullFirBypassesAirspaceFilter() {
        filterCache.updateFilters(SUB, FilterDimension.EVENT_SCENARIO, List.of("AD.CLS"));
        filterCache.updateFilters(SUB, FilterDimension.AIRSPACE, List.of("LPPC"));

        assertThat(filterService.passesSubscriptionFilter(SUB, event("AD.CLS", "EADD", null)))
                .isTrue();
    }

    @Test
    void multipleAllowedValues_matchesAnyInSet() {
        filterCache.updateFilters(SUB, FilterDimension.EVENT_SCENARIO, List.of("AD.CLS", "RWY.CLS", "SAA.ACT"));
        filterCache.updateFilters(SUB, FilterDimension.AIRPORT_HELIPORT, List.of("EADD", "LPPT", "EHAM"));
        filterCache.updateFilters(SUB, FilterDimension.AIRSPACE, List.of("EAAD", "LPPC", "EHAA"));

        assertThat(filterService.passesSubscriptionFilter(SUB, event("SAA.ACT", "LPPT", "EHAA")))
                .isTrue();
    }

    @Test
    void onFilterMismatch_delegatesToDeadLetterService() {
        ProcessingContext ctx = new ProcessingContext(SUB, "q-1", "msg-1", "<xml/>", 0, null);
        EventData rejected = event("AD.CLS", "EADD", "EAAD");

        filterService.onFilterMismatch(ctx, rejected);

        verify(deadLetterService).sendToDeadLetterQueue(
                eq(SUB), eq("q-1"), eq("msg-1"), eq(0), eq("<xml/>"),
                eq("SUBSCRIPTION_FILTER_MISMATCH"), any(IllegalArgumentException.class));
    }
}
