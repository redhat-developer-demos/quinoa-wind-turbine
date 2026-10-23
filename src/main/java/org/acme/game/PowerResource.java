package org.acme.game;

import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import org.acme.race.RaceTopology;

import static org.acme.race.RaceTopology.raceMessage;

@ApplicationScoped
@Path("/power")
public class PowerResource {

    @Channel("power-out") Emitter<Power> powerOut;
    @Inject GameResource gameResource;

    @Path("/")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void generate(Power power) {
        if (power.destination() < 1 || power.destination() > GameConfig.TEAMS) {
            throw new BadRequestException("We only have " + GameConfig.TEAMS + " teams for now");
        }
        if (power.quantity() > GameConfig.MAX_QUANTITY) {
            throw new BadRequestException("Ouch this is too much for me to handle!");
        }
        powerOut.send(raceMessage(power.forRace(gameResource.currentRaceId())));
    }

    /**
     * @param raceId set by the server when it receives the event, replacing any value the client sent, so power
     *               sent for an old race doesn't count
     * @param modes  input methods used to generate power (e.g. tap, shake, swipe, blow)
     */
    public static record Power(int quantity, String source, int destination, String raceId, java.util.Set<String> modes) {
        public Power {
            modes = modes != null ? java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(modes)) : java.util.Set.of();
        }

        public Power(int quantity, String source, int destination) {
            this(quantity, source, destination, null, java.util.Set.of());
        }

        public Power(int quantity, String source, int destination, String raceId) {
            this(quantity, source, destination, raceId, java.util.Set.of());
        }

        Power forRace(String raceId) {
            return new Power(quantity, source, destination, raceId, modes);
        }
    }

}