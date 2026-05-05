package com.github.swim_developer.dnotam.consumer.infrastructure.in.rest;

import com.github.swim_developer.framework.consumer.application.port.in.SwimQueryFeaturesPort;
import com.github.swim_developer.framework.consumer.infrastructure.in.rest.AbstractFeatureResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@ApplicationScoped
@Tag(name = "SWIM DNOTAM Feature Query (WFS)", description = "WS-Light Request/Reply proxy to provider's WFS GetFeature endpoint")
public class FeatureResource extends AbstractFeatureResource {

    public FeatureResource() {
        this(null);
    }

    @Inject
    public FeatureResource(SwimQueryFeaturesPort queryFeaturesPort) {
        super(queryFeaturesPort);
    }
}
