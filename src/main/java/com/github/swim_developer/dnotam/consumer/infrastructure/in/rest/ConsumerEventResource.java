package com.github.swim_developer.dnotam.consumer.infrastructure.in.rest;

import com.github.swim_developer.dnotam.consumer.domain.model.Event;
import com.github.swim_developer.dnotam.consumer.domain.model.Subscription;
import com.github.swim_developer.dnotam.consumer.application.port.out.EventStore;
import com.github.swim_developer.dnotam.consumer.application.port.out.SubscriptionStore;
import com.github.swim_developer.dnotam.consumer.infrastructure.out.mapper.DnotamSubscriptionMapper;
import com.github.swim_developer.dnotam.consumer.infrastructure.in.rest.dto.MessageDTO;
import static com.github.swim_developer.framework.consumer.infrastructure.in.rest.ConsumerRestResponses.*;
import com.github.swim_developer.framework.infrastructure.in.rest.PageResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "SWIM DNOTAM Consumer API", description = "Event query endpoints")
public class ConsumerEventResource {

    private final EventStore eventRepository;
    private final SubscriptionStore subscriptionRepository;
    private final DnotamSubscriptionMapper mapper;

    @Inject
    public ConsumerEventResource(EventStore eventRepository,
                                 SubscriptionStore subscriptionRepository,
                                 DnotamSubscriptionMapper mapper) {
        this.eventRepository = eventRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.mapper = mapper;
    }

    @GET
    @Path("/subscriptions/{subscriptionId}/events")
    @Operation(summary = "List events by subscription", description = "Retrieves DNOTAM events for a specific subscription with pagination")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Paginated list of events",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = PageResponse.class))),
            @APIResponse(responseCode = "404", description = "Subscription not found")
    })
    public Response listEventsBySubscription(
            @Parameter(description = "Subscription ID", required = true, example = "sub-12345")
            @PathParam("subscriptionId") String subscriptionId,
            @Parameter(description = "Page number (0-indexed)", example = "0")
            @QueryParam("page") @DefaultValue("0") int page,
            @Parameter(description = "Page size", example = "20")
            @QueryParam("size") @DefaultValue("20") int size) {

        Optional<Subscription> subscription = subscriptionRepository.findBySubscriptionId(subscriptionId);
        if (subscription.isEmpty()) {
            return notFound(SUBSCRIPTION_NOT_FOUND_PREFIX + subscriptionId);
        }

        List<Event> events = eventRepository.findBySubscriptionIdPaginated(subscriptionId, page, size);
        long totalElements = eventRepository.countBySubscriptionId(subscriptionId);

        List<MessageDTO> dtos = events.stream().map(mapper::toDTO).toList();
        return ok(PageResponse.of(dtos, page, size, totalElements));
    }

    @GET
    @Path("/subscriptions/{subscriptionId}/events/count")
    @Operation(summary = "Count events by subscription", description = "Returns the total number of events for a specific subscription")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Event count",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = Long.class))),
            @APIResponse(responseCode = "404", description = "Subscription not found")
    })
    public Response countEventsBySubscription(
            @Parameter(description = "Subscription ID", required = true, example = "sub-12345")
            @PathParam("subscriptionId") String subscriptionId) {

        Optional<Subscription> subscription = subscriptionRepository.findBySubscriptionId(subscriptionId);
        if (subscription.isEmpty()) {
            return notFound(SUBSCRIPTION_NOT_FOUND_PREFIX + subscriptionId);
        }

        long count = eventRepository.countBySubscriptionId(subscriptionId);
        return ok(Map.of("subscriptionId", subscriptionId, "count", count));
    }

    @GET
    @Path("/events/{messageId}")
    @Operation(summary = "Get event by ID", description = "Retrieves a specific DNOTAM event by its message ID")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Event found",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = MessageDTO.class))),
            @APIResponse(responseCode = "404", description = "Event not found")
    })
    public Response getEventById(
            @Parameter(description = "Message ID", required = true, example = "ID:abc-123-def#0")
            @PathParam("messageId") String messageId) {

        return eventRepository.findByMessageId(messageId)
                .map(e -> ok(mapper.toDTO(e)))
                .orElse(notFound("Event not found: " + messageId));
    }

    @GET
    @Path("/subscriptions/{subscriptionId}/events/range")
    @Operation(summary = "List events by date range", description = "Retrieves DNOTAM events for a subscription within a specific date range (based on receivedAt)")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Paginated list of events",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = PageResponse.class))),
            @APIResponse(responseCode = "400", description = "Invalid parameters"),
            @APIResponse(responseCode = "404", description = "Subscription not found")
    })
    public Response listEventsByDateRange(
            @Parameter(description = "Subscription ID", required = true, example = "sub-12345")
            @PathParam("subscriptionId") String subscriptionId,
            @Parameter(description = "Start date (ISO-8601 format)", required = true, example = "2025-12-01T00:00:00Z")
            @QueryParam("startDate") String startDateStr,
            @Parameter(description = "End date (ISO-8601 format)", required = true, example = "2025-12-08T23:59:59Z")
            @QueryParam("endDate") String endDateStr,
            @Parameter(description = "Page number (0-indexed)", example = "0")
            @QueryParam("page") @DefaultValue("0") int page,
            @Parameter(description = "Page size", example = "20")
            @QueryParam("size") @DefaultValue("20") int size) {

        if (startDateStr == null || endDateStr == null) {
            return badRequest("startDate and endDate are required parameters");
        }

        Optional<Subscription> subscription = subscriptionRepository.findBySubscriptionId(subscriptionId);
        if (subscription.isEmpty()) {
            return notFound(SUBSCRIPTION_NOT_FOUND_PREFIX + subscriptionId);
        }

        try {
            Instant startDate = parseDateOrThrow(startDateStr);
            Instant endDate = parseDateOrThrow(endDateStr);

            if (!isValidDateRange(startDate, endDate)) {
                return badRequest("startDate must be before endDate");
            }

            List<Event> events = eventRepository.findBySubscriptionIdAndDateRange(
                    subscriptionId, startDate, endDate, page, size);
            long totalElements = eventRepository.countBySubscriptionIdAndDateRange(
                    subscriptionId, startDate, endDate);

            List<MessageDTO> dtos = events.stream().map(mapper::toDTO).toList();
            return ok(PageResponse.of(dtos, page, size, totalElements));

        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }
}
