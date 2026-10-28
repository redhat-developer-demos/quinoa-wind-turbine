package org.raceops.director;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.raceops.RaceOpsConfig;
import org.raceops.mcp.AdminRaceClient;
import org.raceops.model.RaceSnapshot;
import org.raceops.monitor.RaceSnapshotEvent;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The director runs the event between races: it starts the race when the join plateau is
 * reached, acting through the game's admin MCP tools under its own OIDC token. Later
 * slices add boost and theme proposals, approval-gated on the console.
 *
 * Snapshots only arrive while something changes, so the plateau is also checked on a
 * timer: a quiet room is exactly the condition to act on.
 */
@ApplicationScoped
public class RaceDirector {

    private final AdminRaceClient admin;
    private final RaceOpsConfig config;

    private volatile RaceSnapshotEvent latest;
    /** The race this director already started; the game keeps serving WAITING snapshots until the start lands. */
    private final AtomicReference<String> startedRaceId = new AtomicReference<>();

    @Inject
    public RaceDirector(AdminRaceClient admin, RaceOpsConfig config) {
        this.admin = admin;
        this.config = config;
    }

    void onSnapshot(@ObservesAsync RaceSnapshotEvent event) {
        String raceId = event.snapshot().raceId();
        // A reset brings a new race id; the old start no longer counts.
        startedRaceId.updateAndGet(started -> started != null && !started.equals(raceId) ? null : started);
        latest = event;
        evaluate();
    }

    @Scheduled(every = "3s")
    void evaluate() {
        if (!config.directorEnabled()) {
            return;
        }
        RaceSnapshotEvent event = latest;
        if (event == null || !"WAITING".equals(event.snapshot().status())) {
            return;
        }
        String raceId = event.snapshot().raceId();
        if (raceId.equals(startedRaceId.get())) {
            return;
        }
        List<RaceSnapshot> waitingWindow = event.window().stream()
            .filter(s -> "WAITING".equals(s.status()))
            .toList();
        StartPolicy.Decision decision = StartPolicy.shouldStart(waitingWindow,
            config.minPlayersPerTeam(), config.plateauSeconds(), System.currentTimeMillis());
        if (!decision.start()) {
            Log.debugf("Director waiting: %s", decision.reason());
            return;
        }
        if (startedRaceId.compareAndSet(null, raceId)) {
            try {
                String result = admin.start();
                Log.infof("Director started %s (%s): %s", raceId, decision.reason(), result);
            } catch (Exception e) {
                startedRaceId.compareAndSet(raceId, null);
                Log.warnf(e, "Director could not start %s, will retry on the next evaluation", raceId);
            }
        }
    }
}
