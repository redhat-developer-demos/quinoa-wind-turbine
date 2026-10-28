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
    /** Finished races the director already proposed a theme for. */
    private final java.util.Set<String> themedRaceIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * The theme currently on the field, from this director's own proposals; starts at the
     * game's boot default. Proposals avoid it, so approving a card always re-skins.
     */
    private volatile String currentTheme = "classic";

    @Inject
    public RaceDirector(AdminRaceClient admin, RaceOpsConfig config, ProposalService proposals,
                        Instance<DirectorAi> ai) {
        this.admin = admin;
        this.config = config;
        this.proposals = proposals;
        this.ai = ai.isUnsatisfied() ? Optional.empty() : Optional.of(ai.get());
    }

    void onSnapshot(@ObservesAsync RaceSnapshotEvent event) {
        // Async observers can run out of order; never let an older snapshot win.
        RaceSnapshotEvent seen = latest;
        if (seen != null && event.snapshot().updatedAt() < seen.snapshot().updatedAt()) {
            return;
        }
        String raceId = event.snapshot().raceId();
        // A reset brings a new race id; the old start no longer counts. A started race
        // never returns to WAITING within one game instance, but the counter behind race
        // ids resets with a fresh instance, so the same id can come back — clear the
        // guards then, or the director would refuse to run the restarted game.
        if (gameRestarted(event.snapshot(), startedRaceId.get())) {
            startedRaceId.set(null);
            boostedRaceIds.clear();
        }
        startedRaceId.updateAndGet(started -> started != null && !started.equals(raceId) ? null : started);
        latest = event;
        evaluate();
        considerBoost(event);
        considerTheme(event);
    }

    /**
     * @return true when a snapshot shows the race this director already started back in
     *         WAITING, which only happens after a game restart reset the race-id counter
     */
    static boolean gameRestarted(RaceSnapshot snapshot, String startedRaceId) {
        return startedRaceId != null
            && "WAITING".equals(snapshot.status())
            && snapshot.raceId() != null
            && snapshot.raceId().equals(startedRaceId);
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
     * Between heats: when a race finishes, propose the theme for the next one. The game
     * only restyles between races, which is exactly this window; if the operator approves
     * after the next race already started, the game rejects it and the card records the
     * error. The model picks from the presets the game actually offers, with a rotating
     * deterministic fallback when it is off or fails.
     */
    private void considerTheme(RaceSnapshotEvent event) {
        if (!config.directorEnabled() || !proposals.pending().isEmpty()) {
            return;
        }
        RaceSnapshot snapshot = event.snapshot();
        RaceSnapshot prev = event.window().size() > 1
            ? event.window().get(event.window().size() - 2) : null;
        boolean justFinished = "FINISHED".equals(snapshot.status())
            && prev != null && !"FINISHED".equals(prev.status());
        if (!justFinished || !themedRaceIds.add(snapshot.raceId())) {
            return;
        }
        try {
            List<AdminRaceClient.ThemeOption> options = admin.listThemes();
            Proposal fallback = DirectorDecisions.themeFallback(options, snapshot.raceId(),
                Math.floorMod(snapshot.raceId().hashCode(), 97), currentTheme);
            Proposal proposal = fallback;
            if (ai.isPresent() && config.aiEnabled()) {
                try {
                    var engagement = EngagementAnalytics.analyze(
                        event.window().isEmpty() ? null : event.window().get(0), snapshot);
                    DirectorAi.ThemeAdvice advice = ai.get()
                        .suggestTheme(DirectorDecisions.themeFacts(snapshot, engagement, options, currentTheme));
                    proposal = DirectorDecisions.theme(advice, options, snapshot.raceId(), currentTheme, fallback);
                } catch (Exception e) {
                    Log.warnf(e, "Director model failed for the theme suggestion, using the fallback");
                }
            }
            proposals.propose(proposal);
            currentTheme = proposal.presetId();
            Log.infof("Director proposes theme %s for the next heat: %s", proposal.presetId(), proposal.message());
        } catch (Exception e) {
            Log.warnf(e, "Director could not build a theme proposal");
        }
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
                    return DirectorDecisions.advice(boostAdvice, situation, snapshot);
                }
                Log.info("Director model declined to propose a boost");
            } catch (Exception e) {
                Log.warnf(e, "Director model failed, using the fallback proposal");
            }
        }
        return DirectorDecisions.fallbackProposal(situation, snapshot);
    }
}
