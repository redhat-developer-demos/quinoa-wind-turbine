package org.acme;

import io.smallrye.mutiny.Multi;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
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

import static org.acme.RaceTopology.raceMessage;

@ApplicationScoped
@Path("/power")
public class PowerResource {

    static final int TEAMS = 2;
    static final int MAX_QUANTITY = 200;

    @Channel("power-in") Multi<Power> powerIn;
    @Channel("power-out") Emitter<Power> powerOut;
    @Inject GameResource gameResource;

    @Path("stream")
    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @RolesAllowed("admin")
    public Multi<List<Power>> stream() {
                return powerIn.group().intoLists().every(Duration.ofMillis(250)).onOverflow().buffer(100000);
    }

    @Path("/")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void generate(Power power) {
        if (power.destination() > TEAMS) {
            throw new IllegalArgumentException("We only have 2 teams for now");
        }
        if (power.quantity > MAX_QUANTITY) {
           throw new IllegalStateException("Ouch this is too much for me to handle!");
        }
        powerOut.send(raceMessage(power.forRace(gameResource.currentRaceId())));
    }

    /**
     * @param raceId set by the server when it receives the event, replacing any value the client sent, so power
     *               sent for an old race doesn't count
     */
    public static record Power(int quantity, String source, int destination, String raceId) {
        public static final Power PING = new Power(0, "ping", -1);

        public Power(int quantity, String source, int destination) {
            this(quantity, source, destination, null);
        }

        Power forRace(String raceId) {
            return new Power(quantity, source, destination, raceId);
        }
    }

}