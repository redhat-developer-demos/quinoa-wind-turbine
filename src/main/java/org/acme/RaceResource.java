package org.acme;

import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.jboss.resteasy.reactive.ResponseHeader;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Serves the race from the local copy of the race-progress topic. Every replica consumes the whole topic, so any
 * replica can answer without calling the others.
 */
@ApplicationScoped
@Path("race")
@Startup
public class RaceResource {

    private final AtomicReference<RaceState> current = new AtomicReference<>();
    private final BroadcastProcessor<RaceState> updates = BroadcastProcessor.create();
    private final GameResource gameResource;

    public RaceResource(@Channel("race-progress-in") Multi<RaceState> raceProgressIn, GameResource gameResource) {
        this.gameResource = gameResource;
        raceProgressIn.subscribe().with(state -> {
            current.set(state);
            updates.onNext(state);
        });
    }

    @GET
    @Path("state")
    @Produces(MediaType.APPLICATION_JSON)
    public RaceState state() {
        return Optional.ofNullable(current.get()).orElseGet(() -> RaceState.waiting(gameResource.currentRaceId()));
    }

    @GET
    @Path("leaderboard")
    @Produces(MediaType.APPLICATION_JSON)
    public List<RaceState.LeaderboardEntry> leaderboard(@QueryParam("team") Integer team) {
        return state().leaderboard(team);
    }

    /**
     * Sends the current race on connect, every update after that, and the current race again every few seconds
     * to keep the connection open.
     */
    @GET
    @Path("stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @ResponseHeader(name = "Connection", value = "keep-alive")
    public Multi<RaceState> stream() {
        return Multi.createBy().merging().streams(
            Multi.createFrom().item(this::state),
            updates(),
            Multi.createFrom().ticks().every(Duration.ofSeconds(5)).onOverflow().drop().map(tick -> state()));
    }

    /**
     * Every race snapshot received from now on.
     */
    public Multi<RaceState> updates() {
        return updates.onOverflow().buffer(100);
    }
}
