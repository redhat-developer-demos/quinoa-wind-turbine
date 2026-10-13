package org.acme;

import io.micrometer.core.annotation.Counted;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.acme.RaceTopology.raceMessage;
import static org.acme.Utils.NAMES;
import static org.acme.Utils.getNameById;
import static org.acme.Utils.withPing;

@ApplicationScoped
@Path("game")
@Startup
public class GameResource {

    private static final Logger LOG = Logger.getLogger(GameResource.class);

    private final AtomicReference<GameEvent> lastGameEvent = new AtomicReference<>();
    private final AtomicReference<String> raceId = new AtomicReference<>();
    private final BroadcastProcessor<GameEvent> updates = BroadcastProcessor.create();
    private final Emitter<GameEvent> gameEventsOut;
    private final Multi<GameEvent> gameEventsIn;
    private final StrongCounter usersCounter;
    private final StrongCounter racesCounter;
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
        // Every replica stamps power events with the same race ID, taken from this shared counter
        counterManager.defineCounter("races",
            CounterConfiguration.builder(CounterType.UNBOUNDED_STRONG).storage(Storage.PERSISTENT).build());
        this.racesCounter = counterManager.getStrongCounter("races");
        this.raceId.set(raceIdFor(racesCounter.getValue().join()));
        // Thanks to this, we can join a party after the start
        this.gameEventsIn = gameEventsIn;
        this.gameEventsOut = gameEventsOut;
        this.powerOut = powerOut;
        this.gameEventsIn.subscribe().with(s -> {
            lastGameEvent.set(s);
            if (GameEvent.RESET.equals(s.type()) && s.raceId() != null) {
                raceId.set(s.raceId());
            }
            LOG.infof("Set last game event: %s", s.type());
            updates.onNext(s);
        });
    }

    /**
     * Every game event received from now on.
     */
    public Multi<GameEvent> updates() {
        return updates.onOverflow().buffer(5000);
    }

    @POST
    @Path("assign/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public User assignNameAndTeam(@PathParam("id") Integer id, @QueryParam("team") int team) {
        final User user = new User(id, getNameById(id), team);
        powerOut.send(raceMessage(new Power(0, user.name(), user.team(), currentRaceId())));
        return user;
    }

    @POST
    @Counted(value = "counted.user_assigned")
    @Path("assign")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<User> assignNameAndTeam(@QueryParam("team") int team) {
        return Uni.createFrom().future(usersCounter.incrementAndGet())
            .map(c -> assignNameAndTeam(c.intValue(), team));

    }

    /**
     * The race that power events count for. It changes on every reset.
     */
    public String currentRaceId() {
        return raceId.get();
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
        return withPing(updates(), GameEvent.PING, 30);
    }

    @POST
    @Path("event")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("admin")
    public Uni<Void> sendGameEvent(GameEvent gameEvent) {
        if (GameEvent.FINISH.equals(gameEvent.type())) {
            throw new BadRequestException("The race topology decides when a race finishes");
        }
        Uni<String> race = GameEvent.RESET.equals(gameEvent.type())
            ? Uni.createFrom().completionStage(racesCounter.incrementAndGet()).map(GameResource::raceIdFor)
                .invoke(raceId::set)
            : Uni.createFrom().item(raceId::get);
        return race.map(gameEvent::withRaceId)
            .invoke(event -> {
                LOG.infof("Sending game event: %s", event);
                gameEventsOut.send(raceMessage(event));
            })
            .replaceWithVoid();
    }

    private static String raceIdFor(long counter) {
        return "race-" + counter;
    }

    public static record User(int id, String name, int team) {
    }

    static record CounterEvent(String source, Integer team) {
    }

    static record GameEvent(String type, Map<String, Object> data) {
        static final String RESET = "reset";
        static final String START = "start";
        static final String PAUSE = "pause";
        static final String FINISH = "finish";
        static final GameEvent PING = new GameEvent("ping");

        public GameEvent(String type) {
            this(type, Collections.emptyMap());
        }

        static GameEvent finish(RaceState race) {
            return new GameEvent(FINISH, Map.of(
                "raceId", race.raceId(),
                "winner", race.winner(),
                "teams", race.teams(),
                "overall", race.players()));
        }

        String raceId() {
            return data != null && data.get("raceId") instanceof String id ? id : null;
        }

        GameEvent withRaceId(String raceId) {
            Map<String, Object> withRace = new HashMap<>(data == null ? Map.of() : data);
            withRace.put("raceId", raceId);
            return new GameEvent(type, withRace);
        }
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
