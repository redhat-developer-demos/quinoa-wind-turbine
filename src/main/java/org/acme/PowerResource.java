package org.acme;

import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
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

    @Channel("power-in") Multi<Power> powerIn;
    @Channel("power-out") Emitter<Power> powerOut;

    // For statistics/leader boards to Kafka
    @Channel("user-actions-out") Emitter<Record<String, Integer>> userActionsOut;

    @Path("stream")
    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @RolesAllowed("admin")
    public Multi<List<Power>> stream() {
                return powerIn.group().intoLists().every(Duration.ofMillis(500)).onOverflow().buffer(100000);
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
        
        // Sends action to leader board topic
        userActionsOut.send(Record.of(power.source(), power.quantity()));
    }

    public static record Power(int quantity, String source, int destination) {
        public static final Power PING = new Power(0, "ping", -1);

    }

}