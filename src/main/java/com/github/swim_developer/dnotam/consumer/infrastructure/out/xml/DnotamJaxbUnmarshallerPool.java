package com.github.swim_developer.dnotam.consumer.infrastructure.out.xml;

import aero.aixm.message.AIXMBasicMessageType;
import com.github.swim_developer.framework.application.port.out.SwimXmlUnmarshallerPort;
import com.github.swim_developer.framework.domain.exception.XmlValidationException;
import com.github.swim_developer.validation.AixmUnmarshallerPool;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class DnotamJaxbUnmarshallerPool implements SwimXmlUnmarshallerPort<AIXMBasicMessageType> {

    private final AixmUnmarshallerPool pool;

    @Inject
    public DnotamJaxbUnmarshallerPool() {
        this.pool = new AixmUnmarshallerPool();
    }

    @PostConstruct
    void logInitialization() {
        log.info("AIXM JAXB unmarshaller pool initialized from aixm-model");
    }

    @Override
    public AIXMBasicMessageType unmarshalAndValidate(String xml) throws XmlValidationException {
        try {
            return pool.unmarshalAndValidate(xml);
        } catch (AixmUnmarshallerPool.AixmUnmarshalException e) {
            throw new XmlValidationException(e.getMessage(), e);
        }
    }
}
