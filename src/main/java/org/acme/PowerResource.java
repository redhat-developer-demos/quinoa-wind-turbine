package org.acme;

import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.kafka.Record;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.resteasy.reactive.ResponseHeader;
import org.jboss.resteasy.reactive.RestStreamElementType;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import static org.acme.Utils.withPing;

@ApplicationScoped
@Path("power")
public class PowerResource {

    @Channel("power-in") Multi<Power> power;
    @Channel("power-out") Emitter<Power> powerEmitter;

    // For statistics/leader boards to Kafka
    @Channel("user-actions-out") Emitter<Record<String, Integer>> userActionsEmitter;

    @Path("stream")
    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @ResponseHeader(name = "Connection", value = "keep-alive")
    public Multi<Power> stream() {
                return withPing(power, Power.PING);
    }

    @Path("")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void generate(Power power) {
        if (power.destination() > 2) {
            throw new IllegalArgumentException("We only have 2 teams for now");
        }
        powerEmitter.send(power);
        
        // Sends action to leader board topic
        userActionsEmitter.send(Record.of(power.source(), power.quantity()));
    }

    public static record Power(int quantity, String source, int destination) {
        public static final Power PING = new Power(0, "ping", -1);

    }

}