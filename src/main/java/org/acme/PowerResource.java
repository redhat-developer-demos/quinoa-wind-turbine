package org.acme;

import io.smallrye.mutiny.Multi;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.OnOverflow;
import org.jboss.resteasy.reactive.RestStreamElementType;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.util.List;

@ApplicationScoped
@Path("power")
public class PowerResource {

    @Channel("power") Multi<Power> powerIn;
    @Channel("power")
    @OnOverflow(OnOverflow.Strategy.DROP)
    Emitter<Power> powerOut;

    @Path("stream")
    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @RolesAllowed("admin")
    public Multi<List<Power>> stream() {
                return powerIn.group().intoLists().every(Duration.ofMillis(20)).onOverflow().buffer(5000);
    }

    @Path("")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void generate(Power power) {
        if (power.destination() > 2) {
            throw new IllegalArgumentException("We only have 2 teams for now");
        }
        if (power.quantity > 200) {
           throw new IllegalStateException("Ouch this is too much for me to handle!");
        }
        powerOut.send(power);
    }

    public static record Power(int quantity, String source, int destination) {
        public static final Power PING = new Power(0, "ping", -1);

    }

}