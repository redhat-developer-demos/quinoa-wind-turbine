package org.acme;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.acme.PowerResource.Power;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.infinispan.counter.api.CounterConfiguration;
import org.infinispan.counter.api.CounterManager;
import org.infinispan.counter.api.CounterType;
import org.infinispan.counter.api.Storage;
import org.infinispan.counter.api.StrongCounter;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.ResponseHeader;
import org.jboss.resteasy.reactive.RestStreamElementType;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.acme.Utils.NAMES;
import static org.acme.Utils.getNameById;
import static org.acme.Utils.withPing;

@ApplicationScoped
@Path("game")
public class GameResource {

    private static final Logger LOG = Logger.getLogger(GameResource.class);
    private final AtomicReference<GameEvent> lastGameEvent = new AtomicReference<>();
    @Inject CounterManager counterManager;
    @Channel("game-events-out") Emitter<GameEvent> gameEventsOut;
    @Channel("game-events-in") Multi<GameEvent> gameEventsIn;
    private StrongCounter usersCounter;
    private Multi<GameEvent> replayEventsIn;

    @Channel("power-out") Emitter<Power> powerEmitter;

    static {
        LOG.info("List of names initialized with " + NAMES.size() + " items");
    }


    void onStart(@Observes StartupEvent ev) {
        counterManager.defineCounter("users", CounterConfiguration.builder(CounterType.UNBOUNDED_STRONG).storage(Storage.PERSISTENT).build());
        usersCounter = counterManager.getStrongCounter("users");
        // Thanks to this, we can join a party after the start
        replayEventsIn = Multi.createBy().replaying().upTo(5).ofMulti(gameEventsIn);
        gameEventsIn.subscribe().with(s -> {
            lastGameEvent.set(s);
            System.out.println("Set last game event: " + s.type);
        });
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
    public Uni<User> assignNameAndTeam() {
        return Uni.createFrom().future(usersCounter.incrementAndGet())
                .map(c -> assignNameAndTeam(c.intValue()));

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
        return withPing(replayEventsIn, GameEvent.PING);
    }

    @POST
    @Path("event")
    @Consumes(MediaType.APPLICATION_JSON)
    public void sendGameEvent(GameEvent gameEvent) {
        System.out.println("sending: " + gameEvent);
        gameEventsOut.send(gameEvent);
    }

    public static record User(int id, String name, int team){}

    static record CounterEvent(String source, Integer team){ }

    static record GameEvent(String type){
        static final GameEvent PING = new GameEvent("ping");
    }

    static record InstanceMsg(String type, Integer id) { }

    public static record UserScore(String user, Long score) implements Comparable<UserScore> {
        @Override
        public int compareTo(UserScore o) {
            return this.score.compareTo(o.score);
        }
    }


}