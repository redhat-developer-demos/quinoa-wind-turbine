package org.acme.commentary;

import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.acme.game.GameResource;
import org.acme.race.RaceResource;
import org.acme.race.RaceState;

/** Each replica receives the generated lines and keeps one for dashboard reconnects. */
@Startup
@ApplicationScoped
public class CommentaryFeed {
    private final Supplier<RaceState> raceState;
    private final Supplier<String> currentRaceId;
    private final Supplier<String> gameStatus;
    private final Multi<RaceState> raceEvents;
    private final Multi<GameResource.GameEvent> gameEvents;
    private final BroadcastProcessor<Commentary> updates = BroadcastProcessor.create();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private final LinkedHashMap<String, Commentary> latestByRace = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> pausedThrough = new LinkedHashMap<>();
    private String delivered;
    private Cancellable raceUpdates;
    private Cancellable gameUpdates;

    @Inject
    public CommentaryFeed(RaceResource race, GameResource game) {
        this(race::state, game::currentRaceId, () -> game.status().type(), race.updates(), game.updates());
    }

    CommentaryFeed(Supplier<RaceState> raceState, Supplier<String> currentRaceId, Supplier<String> gameStatus,
                   Multi<RaceState> raceEvents, Multi<GameResource.GameEvent> gameEvents) {
        this.raceState = raceState;
        this.currentRaceId = currentRaceId;
        this.gameStatus = gameStatus;
        this.raceEvents = raceEvents;
        this.gameEvents = gameEvents;
    }

    @PostConstruct
    void watchRace() {
        raceUpdates = raceEvents.subscribe().with(ignored -> reconcile());
        gameUpdates = gameEvents.subscribe().with(ignored -> reconcile());
    }

    @Incoming("commentary-in")
    public synchronized void receive(Commentary line) {
        if (seen.contains(line.id())) return;
        Commentary previous = latestByRace.get(line.raceId());
        if (previous != null && line.snapshotAt() < previous.snapshotAt()) return;
        seen.add(line.id());
        if (seen.size() > 256) seen.removeFirst();
        // The three inputs replay independently. Retain candidates until race and game state catch up.
        latestByRace.put(line.raceId(), line);
        if (latestByRace.size() > 4) latestByRace.pollFirstEntry();
        reconcile();
    }

    public synchronized Optional<Commentary> latest() {
        RaceState state = raceState.get();
        if (state.status() == RaceState.Status.PAUSED) {
            pausedThrough.merge(state.raceId(), state.updatedAt(), Math::max);
            if (pausedThrough.size() > 4) pausedThrough.pollFirstEntry();
        }
        return Optional.ofNullable(latestByRace.get(currentRaceId.get()))
            .filter(line -> visibleNow(line, state));
    }

    public Multi<Commentary> updates() {
        return updates.onOverflow().buffer(16);
    }

    private boolean visibleNow(Commentary line, RaceState current) {
        return !GameResource.GameEvent.PAUSE.equals(gameStatus.get())
            && ("finish".equals(line.trigger())
                || line.snapshotAt() > pausedThrough.getOrDefault(line.raceId(), Long.MIN_VALUE))
            && visible(line, current);
    }

    private synchronized void reconcile() {
        latest().filter(line -> !line.id().equals(delivered)).ifPresent(line -> {
            delivered = line.id();
            updates.onNext(line);
        });
    }

    static boolean visible(Commentary line, RaceState current) {
        if (!Objects.equals(line.raceId(), current.raceId())) return false;
        if (line.snapshotAt() > current.updatedAt()) return false;
        return switch (current.status()) {
            case STARTED -> true;
            case FINISHED -> "finish".equals(line.trigger());
            default -> false;
        };
    }

    @PreDestroy
    void stop() {
        if (raceUpdates != null) raceUpdates.cancel();
        if (gameUpdates != null) gameUpdates.cancel();
    }
}
