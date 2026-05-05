package com.github.swim_developer.dnotam.consumer.infrastructure.in.rest;

import com.github.swim_developer.dnotam.consumer.domain.model.Subscription;
import com.github.swim_developer.dnotam.consumer.domain.model.command.SubscriptionCommand;
import com.github.swim_developer.dnotam.consumer.application.port.in.ManageSubscriptionPort;
import com.github.swim_developer.dnotam.consumer.application.port.out.SubscriptionStore;
import com.github.swim_developer.dnotam.consumer.infrastructure.in.rest.dto.SubscriptionRequest;
import com.github.swim_developer.framework.consumer.infrastructure.out.config.subscription.AbstractSubscriptionConfigParser;
import static com.github.swim_developer.framework.consumer.infrastructure.in.rest.ConsumerRestResponses.*;
import com.github.swim_developer.framework.infrastructure.util.StringUtil;
import com.github.swim_developer.framework.application.model.SubscriptionStatusUpdate;
import com.github.swim_developer.framework.domain.model.SubscriptionStatus;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

@Slf4j
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "SWIM DNOTAM Consumer API", description = "Subscription management endpoints")
public class ConsumerSubscriptionResource {

    private final SubscriptionStore subscriptionRepository;
    private final AbstractSubscriptionConfigParser<?> configParser;
    private final ManageSubscriptionPort subscriptionService;

    @Inject
    public ConsumerSubscriptionResource(SubscriptionStore subscriptionRepository,
                                        AbstractSubscriptionConfigParser<?> configParser,
                                        ManageSubscriptionPort subscriptionService) {
        this.subscriptionRepository = subscriptionRepository;
        this.configParser = configParser;
        this.subscriptionService = subscriptionService;
    }

    @GET
    @Path("/subscriptions")
    @Operation(summary = "List all subscriptions", description = "Retrieves all subscriptions from MongoDB regardless of status")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "List of subscriptions",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = Subscription.class, type = SchemaType.ARRAY)))
    })
    public Response listAllSubscriptions() {
        return Response.ok(subscriptionRepository.findAllSubscriptions()).build();
    }

    @GET
    @Path("/subscriptions/active")
    @Operation(summary = "List active subscriptions", description = "Retrieves only subscriptions with status ACTIVE")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "List of active subscriptions",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = Subscription.class, type = SchemaType.ARRAY)))
    })
    public Response listActiveSubscriptions() {
        return Response.ok(subscriptionRepository.findActiveSubscriptions()).build();
    }

    @POST
    @Path("/subscriptions")
    @Operation(summary = "Create subscription", description = "Creates a new manual subscription and registers AMQP consumer")
    @APIResponses(value = {
            @APIResponse(responseCode = "201", description = "Subscription created",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = Subscription.class))),
            @APIResponse(responseCode = "400", description = "Invalid request"),
            @APIResponse(responseCode = "409", description = "Subscription already exists"),
            @APIResponse(responseCode = "503", description = "Subscription Manager unavailable")
    })
    public Response createSubscription(
            @RequestBody(description = "Subscription details", required = true,
                    content = @Content(schema = @Schema(implementation = SubscriptionRequest.class)))
            SubscriptionRequest request) {

        if (StringUtil.isNullOrBlank(request.topic())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(ERROR_KEY, "topic is required"))
                    .build();
        }

        try {
            SubscriptionCommand command = toCommand(request);
            Subscription subscription = subscriptionService.createSubscription(command);
            log.info("Created subscription: {}", subscription.getSubscriptionId());
            return created(subscription);

        } catch (Exception e) {
            log.error("Failed to create subscription", e);
            return serviceUnavailable("Failed to create subscription: " + e.getMessage());
        }
    }

    @DELETE
    @Path("/subscriptions/{subscriptionId}")
    @Operation(summary = "Delete subscription", description = "Deletes a subscription and unregisters AMQP consumer")
    @APIResponses(value = {
            @APIResponse(responseCode = "204", description = "Subscription deleted"),
            @APIResponse(responseCode = "404", description = "Subscription not found"),
            @APIResponse(responseCode = "503", description = "Subscription Manager unavailable")
    })
    public Response deleteSubscription(
            @Parameter(description = "Subscription ID", required = true, example = "sub-12345")
            @PathParam("subscriptionId") String subscriptionId) {

        try {
            subscriptionService.deleteSubscriptionById(subscriptionId);
            log.info("Deleted subscription: {}", subscriptionId);
            return noContent();

        } catch (IllegalArgumentException e) {
            return notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete subscription: {}", subscriptionId, e);
            return serviceUnavailable("Failed to delete subscription: " + e.getMessage());
        }
    }

    @PUT
    @Path("/subscriptions/{subscriptionId}")
    @Operation(summary = "Update subscription status", description = "Pauses or resumes a subscription (ACTIVE/PAUSED)")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Subscription updated",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = Subscription.class))),
            @APIResponse(responseCode = "400", description = "Invalid status"),
            @APIResponse(responseCode = "404", description = "Subscription not found"),
            @APIResponse(responseCode = "503", description = "Subscription Manager unavailable")
    })
    public Response updateSubscriptionStatus(
            @Parameter(description = "Subscription ID", required = true, example = "sub-12345")
            @PathParam("subscriptionId") String subscriptionId,
            @RequestBody(description = "New status (ACTIVE or PAUSED)", required = true,
                    content = @Content(schema = @Schema(implementation = SubscriptionStatusUpdate.class)))
            SubscriptionStatusUpdate statusUpdate) {

        if (statusUpdate == null || statusUpdate.subscriptionStatus() == null) {
            return badRequest("subscriptionStatus is required");
        }

        String newStatus = statusUpdate.subscriptionStatus().toUpperCase();
        if (!isValidSubscriptionStatus(newStatus)) {
            return badRequest("subscriptionStatus must be ACTIVE, PAUSED, or DELETED");
        }

        try {
            Subscription subscription;
            if (newStatus.equals(SubscriptionStatus.DELETED.name())) {
                subscriptionService.deleteSubscriptionById(subscriptionId);
                log.info("Deleted subscription {} via status update", subscriptionId);
                return noContent();
            } else if (newStatus.equals(SubscriptionStatus.PAUSED.name())) {
                subscription = subscriptionService.pauseSubscription(subscriptionId);
            } else {
                subscription = subscriptionService.resumeSubscription(subscriptionId);
            }

            log.info("Updated subscription {} status to {}", subscriptionId, newStatus);
            return ok(subscription);

        } catch (IllegalArgumentException e) {
            return notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update subscription: {}", subscriptionId, e);
            return serviceUnavailable("Failed to update subscription: " + e.getMessage());
        }
    }

    @GET
    @Path("/topics")
    @Operation(summary = "List configured topics", description = "Retrieves topics configured via ConfigMap (dnotam.subscriptions)")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "List of configured topics",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = SubscriptionRequest.class, type = SchemaType.ARRAY)))
    })
    public Response listConfiguredTopics() {
        List<?> commands = configParser.parseDesiredSubscriptions();
        return Response.ok(commands).build();
    }

    private static SubscriptionCommand toCommand(SubscriptionRequest request) {
        return new SubscriptionCommand(
                request.topic(),
                request.queueName(),
                request.eventScenario(),
                request.airportHeliport(),
                request.airspace(),
                request.eventSeries(),
                request.publisher(),
                request.provider(),
                request.description(),
                request.comment()
        );
    }
}
