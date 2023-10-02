package org.acme;

import io.micrometer.core.annotation.Counted;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import org.acme.PowerResource.Power;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.counter.api.CounterConfiguration;
import org.infinispan.counter.api.CounterManager;
import org.infinispan.counter.api.CounterType;
import org.infinispan.counter.api.Storage;
import org.infinispan.counter.api.StrongCounter;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.ResponseHeader;
import org.jboss.resteasy.reactive.RestStreamElementType;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.acme.Utils.NAMES;
import static org.acme.Utils.getNameById;
import static org.acme.Utils.withPing;

@ApplicationScoped
@Path("game")
@Startup
public class GameResource {

    private static final Logger LOG = Logger.getLogger(GameResource.class);

    private final AtomicReference<GameEvent> lastGameEvent = new AtomicReference<>();
    private final Emitter<GameEvent> gameEventsOut;
    private final Multi<GameEvent> gameEventsIn;
    private final StrongCounter usersCounter;
    private final Emitter<Power> powerOut;

    static {
        LOG.info("List of names initialized with " + NAMES.size() + " items");
    }

    public GameResource(RemoteCacheManager remoteCacheManager,
                        CounterManager counterManager,
                        @Channel("game-events-in") Multi<GameEvent> gameEventsIn,
                        @Channel("game-events-out") Emitter<GameEvent> gameEventsOut,
                        @Channel("power-out") Emitter<Power> powerOut) {
        remoteCacheManager.getCache();
        counterManager.defineCounter("users",
            CounterConfiguration.builder(CounterType.UNBOUNDED_STRONG).storage(Storage.PERSISTENT).build());
        this.usersCounter = counterManager.getStrongCounter("users");
        // Thanks to this, we can join a party after the start
        this.gameEventsIn = gameEventsIn;
        this.gameEventsOut = gameEventsOut;
        this.powerOut = powerOut;
        this.gameEventsIn.subscribe().with(s -> {
            lastGameEvent.set(s);
            System.out.println("Set last game event: " + s.type);
        });
    }

    @POST
    @Path("assign/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public User assignNameAndTeam(@PathParam("id") Integer id) {
        final User user = new User(id, getNameById(id), (id % 2) + 1);
        powerOut.send(new Power(0, user.name(), user.team()));
        return user;
    }

    @POST
    @Counted(value = "counted.user_assigned")
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
        return withPing(gameEventsIn.onOverflow().buffer(5000), GameEvent.PING, 30);
    }

    @POST
    @Path("event")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("admin")
    public void sendGameEvent(GameEvent gameEvent) {
        System.out.println("sending: " + gameEvent);
        gameEventsOut.send(gameEvent);
    }

    public static record User(int id, String name, int team) {
    }

    static record CounterEvent(String source, Integer team) {
    }

    static record GameEvent(String type, Map<String, Object> data) {
        public GameEvent(String type) {
            this(type, Collections.emptyMap());
        }
        static final GameEvent PING = new GameEvent("ping");
    }

    static record InstanceMsg(String type, Integer id) {
    }

    public static record UserScore(String user, Long score) implements Comparable<UserScore> {
        @Override
        public int compareTo(UserScore o) {
            return this.score.compareTo(o.score);
        }
    }
}
