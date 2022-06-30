package org.acme;

import io.smallrye.mutiny.Multi;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.resteasy.reactive.ResponseHeader;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestStreamElementType;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.function.Consumer;

@Path("/power")
@ApplicationScoped
public class PowerResource {

    private final SecureRandom RANDOM = new SecureRandom();


    @Inject
    @Channel("power1-in")
    Multi<Power> power1;

    @Inject
    @Channel("power1-out")
    Emitter<Power> power1Emitter;

    @Inject
    @Channel("power2-in")
    Multi<Power> power2;

    @Inject
    @Channel("power2-out")
    Emitter<Power> power2Emitter;


    @GET
    @Path("stream/{team}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @ResponseHeader(name = "Connection", value = "keep-alive")
    public Multi<Power> stream(@RestPath int team) {
        final Multi<Power> p = switch (team) {
            case 1 -> power1;
            case 2 -> power2;
            default -> throw new IllegalArgumentException("Team not found: " + team);
        };
        return Multi.createBy().merging()
                .streams(
                        p,
                        emitAPeriodicPing()
                );

    }

    private Multi<Power> emitAPeriodicPing() {
        return Multi.createFrom().ticks().every(Duration.ofSeconds(10))
                .onItem().transform(x -> new Power(0, ""));
    }

    @POST
    @Path("{team}")
    @Consumes(MediaType.APPLICATION_JSON)
    public void generate(Power power, @RestPath int team) {
        final Power p = power == null ? new Power() : power;
        if (p.quantity > 1) {
            throw new IllegalArgumentException("We don't support that much energy for now");
        }
        switch (team) {
            case 1 -> power1Emitter.send(p);
            case 2 -> power2Emitter.send(p);
            default -> throw new IllegalArgumentException("Team not found: " + team);
        }
    }
}