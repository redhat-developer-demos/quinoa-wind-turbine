package org.acme;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("features")
public class FeaturesResource {
    private final GameConfig config;

    public FeaturesResource(GameConfig config) {
        this.config = config;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Features features() {
        return new Features(config.ai().enabled());
    }

    public record Features(boolean aiEnabled) {
    }
}
