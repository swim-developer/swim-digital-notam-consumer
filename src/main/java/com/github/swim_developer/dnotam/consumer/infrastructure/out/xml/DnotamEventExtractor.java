package com.github.swim_developer.dnotam.consumer.infrastructure.out.xml;

import aero.aixm.AbstractAIXMFeatureType;
import aero.aixm.message.AIXMBasicMessageType;
import aero.aixm.message.BasicMessageMemberAIXMPropertyType;
import aero.aixm.event.AISMessagePropertyType;
import aero.aixm.event.AbstractAISMessageType;
import aero.aixm.event.EventTimeSlicePropertyType;
import aero.aixm.event.EventTimeSliceType;
import aero.aixm.event.EventType;
import aero.aixm.event.NOTAMType;
import com.github.swim_developer.dnotam.consumer.domain.model.EventData;
import com.github.swim_developer.dnotam.consumer.domain.model.EventScenario;
import com.github.swim_developer.framework.application.port.out.SwimEventExtractor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.xml.bind.JAXBElement;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

@Slf4j
@ApplicationScoped
public class DnotamEventExtractor implements SwimEventExtractor<EventData, AIXMBasicMessageType> {

    @Override
    public String getTypeLabel(EventData event) {
        return event.scenario() != null ? event.scenario().value() : "unknown";
    }

    @Override
    public List<Optional<EventData>> extract(AIXMBasicMessageType message) {
        if (message == null) {
            return List.of(Optional.empty());
        }

        EventType eventType = findEvent(message);
        if (eventType == null) {
            return List.of(Optional.empty());
        }

        try {
            EventData eventData = extractFromEvent(eventType);
            return List.of(Optional.of(eventData));
        } catch (RuntimeException e) {
            log.error("Failed to extract DNOTAM event from already-parsed message", e);
            return List.of(Optional.empty());
        }
    }

    private EventType findEvent(AIXMBasicMessageType message) {
        for (BasicMessageMemberAIXMPropertyType member : message.getHasMember()) {
            JAXBElement<? extends AbstractAIXMFeatureType> feature = member.getAbstractAIXMFeature();
            if (feature != null && feature.getValue() instanceof EventType evt) {
                return evt;
            }
        }
        return null;
    }

    private EventData extractFromEvent(EventType eventType) {
        EventTimeSliceType timeSlice = findFirstTimeSlice(eventType);
        if (timeSlice == null) {
            return new EventData(null, null, null);
        }

        EventScenario scenario = toEventScenario(extractJaxbString(timeSlice.getScenario()));

        NOTAMType notam = findFirstNotam(timeSlice);
        String location = null;
        String affectedFir = null;

        if (notam != null) {
            location = extractJaxbString(notam.getLocation());
            affectedFir = extractJaxbString(notam.getAffectedFIR());
        }

        return new EventData(scenario, location, affectedFir);
    }

    private EventTimeSliceType findFirstTimeSlice(EventType eventType) {
        List<EventTimeSlicePropertyType> slices = eventType.getTimeSlice();
        if (slices == null || slices.isEmpty()) {
            return null;
        }
        return slices.getFirst().getEventTimeSlice();
    }

    private NOTAMType findFirstNotam(EventTimeSliceType timeSlice) {
        List<AISMessagePropertyType> notifications = timeSlice.getNotification();
        if (notifications == null || notifications.isEmpty()) {
            return null;
        }
        for (AISMessagePropertyType notif : notifications) {
            JAXBElement<? extends AbstractAISMessageType> msg = notif.getAbstractAISMessage();
            if (msg != null && msg.getValue() instanceof NOTAMType notam) {
                return notam;
            }
        }
        return null;
    }

    private static String extractJaxbString(JAXBElement<?> element) {
        if (element == null || element.getValue() == null) {
            return null;
        }
        Object val = element.getValue();
        return switch (val) {
            case aero.aixm.TextDesignatorType t -> t.getValue();
            case aero.aixm.CodeUpperAlphaType t -> t.getValue();
            case aero.aixm.CodeICAOType t -> t.getValue();
            case aero.aixm.TextNameType t -> t.getValue();
            case aero.aixm.event.CodeNOTAMType t -> t.getValue();
            default -> val.toString();
        };
    }

    private static EventScenario toEventScenario(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return new EventScenario(raw);
    }
}
