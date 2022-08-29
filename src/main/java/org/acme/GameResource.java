package org.acme;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.acme.PowerResource.Power;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.ResponseHeader;
import org.jboss.resteasy.reactive.RestStreamElementType;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.acme.Utils.COMBINED_NAMES;
import static org.acme.Utils.getNameById;
import static org.acme.Utils.withPing;

@ApplicationScoped
@Path("game")
public class GameResource {

    private static final Logger LOG = Logger.getLogger(GameResource.class);
    private final AtomicInteger counter = new AtomicInteger();
    private final AtomicReference<GameEvent> lastGameEvent = new AtomicReference<>();
    private int instanceId;

    // SSE to admin to start the game, ...
    @Channel("game-events") Emitter<GameEvent> gameEventsEmitter;
    @Channel("game-events") Multi<GameEvent> gameEvents;
    Multi<GameEvent> replayEvents;

    @Channel("power-out") Emitter<Power> powerEmitter;

    void onStart(@Observes StartupEvent ev) {
        final SecureRandom random = new SecureRandom();
        instanceId = random.nextInt(COMBINED_NAMES.size() - 600);
        LOG.infof("InstanceId is %s", instanceId);
        // Thanks to this, we can join a party after the start
        replayEvents = Multi.createBy().replaying().upTo(1).ofMulti(gameEvents);
        replayEvents.subscribe().with(lastGameEvent::set);
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
    @Path("status")
    @Produces(MediaType.APPLICATION_JSON)
    public GameEvent status() {
        return Optional.ofNullable(lastGameEvent.get()).orElse(new GameEvent("empty"));
    }

    @GET
    @Path("events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @ResponseHeader(name = "Connection", value = "keep-alive")
    public Multi<GameEvent> events() {
        return withPing(replayEvents, GameEvent.PING);
    }

    @POST
    @Path("event")
    @Consumes(MediaType.APPLICATION_JSON)
    public void sendGameEvent(GameEvent gameEvent) {
        System.out.println("sending: " + gameEvent);
        gameEventsEmitter.send(gameEvent);
    }

    public static record User(int id, String name, int team){}

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