package org.acme;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.kafka.Record;
import org.acme.PowerResource.Power;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.ResponseHeader;
import org.jboss.resteasy.reactive.RestStreamElementType;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.ws.rs.BeanParam;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.MatrixParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.acme.Utils.COMBINED_NAMES;
import static org.acme.Utils.getNameById;
import static org.acme.Utils.withPing;

@ApplicationScoped
@Path("game")
public class GameResource {

    private static final Logger LOG = Logger.getLogger(GameResource.class);
    private final AtomicInteger counter = new AtomicInteger();
    private int instanceId;

    // SSE to admin to start the game, ...
    @Channel("game-events") Emitter<GameEvent> gameEventsEmitter;
    @Channel("game-events") Multi<GameEvent> gameEvents;
    Multi<GameEvent> cachedGameEvents;

    @Channel("power-out") Emitter<Power> powerEmitter;

    void onStart(@Observes StartupEvent ev) {
        final SecureRandom random = new SecureRandom();
        instanceId = random.nextInt(COMBINED_NAMES.size() - 200);
        LOG.infof("InstanceId is %s", instanceId);
        // Thanks to this, we can join a party after the start
        cachedGameEvents = gameEvents.cache();
    }

    @POST
    @Path("assign/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public User assignNameAndTeam(@PathParam("id") Integer id) {
        final User user = new User(id, getNameById(id), (id % 2) + 1);
        powerEmitter.send(new Power(0, user.name(), user.team()));
        return user;
    }

    @POST
    @Path("assign")
    @Produces(MediaType.APPLICATION_JSON)
    public User assignNameAndTeam() {
       return assignNameAndTeam(instanceId + counter.getAndIncrement());
    }

    @GET
    @Path("events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @ResponseHeader(name = "Connection", value = "keep-alive")
    public Multi<GameEvent> events() {
        return withPing(cachedGameEvents, GameEvent.PING);
    }

    @POST
    @Path("event")
    @Consumes(MediaType.APPLICATION_JSON)
    public void sendGameEvent(GameEvent gameEvent) {
        System.out.println("sending: " + gameEvent);
        gameEventsEmitter.send(gameEvent);
    }

    static record User(int id, String name, int team){}

    static record GameEvent(String type){
        static final GameEvent PING = new GameEvent("ping");
    }

    public static record UserScore(String user, Long score) implements Comparable<UserScore> {
        @Override
        public int compareTo(UserScore o) {
            return this.score.compareTo(o.score);
        }
    }


}