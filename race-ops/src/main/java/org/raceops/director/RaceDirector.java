package org.raceops.director;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.raceops.RaceOpsConfig;
import org.raceops.analytics.EngagementAnalytics;
import org.raceops.analytics.EngagementReport;
import org.raceops.mcp.AdminRaceClient;
import org.raceops.model.RaceSnapshot;
import org.raceops.monitor.RaceSnapshotEvent;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The director runs the event: it starts the race when the join plateau is reached, and
 * during the race it proposes boosts for a team that falls far enough behind, with the
 * diagnosis of why. Proposals wait for the operator on the console; only the start rule
 * acts on its own, because a start is reversible and a boost changes the outcome.
 *
 * Snapshots only arrive while something changes, so the plateau is also checked on a
 * timer: a quiet room is exactly the condition to act on.
 */
@ApplicationScoped
public class RaceDirector {

    private final AdminRaceClient admin;
    private final RaceOpsConfig config;
    private final ProposalService proposals;
    private final Optional<DirectorAi> ai;

    private volatile RaceSnapshotEvent latest;
    /** The race this director already started; the game keeps serving WAITING snapshots until the start lands. */
    private final AtomicReference<String> startedRaceId = new AtomicReference<>();
    /** Races this director already proposed a boost for; one proposal per race keeps the console calm. */
    private final java.util.Set<String> boostedRaceIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Inject
    public RaceDirector(AdminRaceClient admin, RaceOpsConfig config, ProposalService proposals,
                        Instance<DirectorAi> ai) {
        this.admin = admin;
        this.config = config;
        this.proposals = proposals;
        this.ai = ai.isUnsatisfied() ? Optional.empty() : Optional.of(ai.get());
    }

    void onSnapshot(@ObservesAsync RaceSnapshotEvent event) {
        String raceId = event.snapshot().raceId();
        // A reset brings a new race id; the old start no longer counts.
        startedRaceId.updateAndGet(started -> started != null && !started.equals(raceId) ? null : started);
        latest = event;
        evaluate();
        considerBoost(event);
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

    private void considerBoost(RaceSnapshotEvent event) {
        if (!config.directorEnabled() || !proposals.pending().isEmpty()) {
            return;
        }
        RaceSnapshot snapshot = event.snapshot();
        if (!"STARTED".equals(snapshot.status()) || !boostedRaceIds.add(snapshot.raceId())) {
            return;
        }
        var engagement = EngagementAnalytics.analyze(event.window().isEmpty() ? null
            : event.window().get(0), snapshot);
        Optional<BoostPolicy.Situation> situation = BoostPolicy.evaluate(snapshot, engagement,
            config.boostDeficitPercent());
        if (situation.isEmpty()) {
            boostedRaceIds.remove(snapshot.raceId()); // the race is still close; look again later
            return;
        }
        Proposal proposal = advice(snapshot, engagement, situation.get());
        proposals.propose(proposal);
        Log.infof("Director proposes boosting team %d (%.1f%% behind): %s",
            proposal.team(), situation.get().deficit(), proposal.message());
    }

    /**
     * The model decides when it is on; the fallback is deterministic. Either way the
     * answer is clamped to the trailing team and sane parameters before it becomes a
     * proposal.
     */
    private Proposal advice(RaceSnapshot snapshot, EngagementReport engagement, BoostPolicy.Situation situation) {
        if (ai.isPresent() && config.aiEnabled()) {
            try {
                DirectorAi.BoostAdvice boostAdvice = ai.get()
                    .advise(DirectorDecisions.raceFacts(snapshot, engagement, situation));
                if (boostAdvice != null && boostAdvice.propose()) {
                    return DirectorDecisions.sanitize(boostAdvice, situation, snapshot);
                }
                Log.info("Director model declined to propose a boost");
            } catch (Exception e) {
                Log.warnf(e, "Director model failed, using the fallback proposal");
            }
        }
        return DirectorDecisions.fallbackProposal(situation, snapshot);
    }
}
