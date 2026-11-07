package org.raceops.director;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.raceops.RaceOpsConfig;
import org.raceops.analytics.EngagementReport;
import org.raceops.analytics.EngagementService;
import org.raceops.mcp.AdminRaceClient;
import org.raceops.mesh.MeshClient;
import org.raceops.mesh.MeshClient.Intervention;
import org.raceops.mesh.MeshExecutor;
import org.raceops.metrics.DirectorMetrics;
import org.raceops.model.RaceSnapshot;
import org.raceops.monitor.RaceSnapshotEvent;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The director runs the event: it starts the race when the join plateau is reached, and
 * during the race it proposes boosts for a team that falls far enough behind, with the
 * diagnosis of why. Proposals wait for the operator on the console; only the start rule
 * acts on its own, because a start is reversible and a boost changes the outcome.
 *
 * <p>With the mesh on, the analyst always runs first and the supervisor's model decides the
 * intervention from its diagnosis: soft tapping means the director boosts the trailing team,
 * players who stopped means a look for the next heat. The route is read from the specialists
 * that ran, never from the model's wording; the policy still owns the trigger and the
 * numbers. With the mesh off, or when a specialist is down, the same decisions fall back to
 * the in-process report and a deterministic proposal. The stage never waits on another app
 * to keep the race running: every mesh and MCP call in the proposal path runs on
 * {@link MeshExecutor}, so a hung specialist cannot freeze the snapshot observer.
 *
 * <p>Snapshots only arrive while something changes, so the plateau is also checked on a
 * timer: a quiet room is exactly the condition to act on.
 */
@ApplicationScoped
public class RaceDirector {

    /** How long to leave the game alone after a failed start, so an outage is not hammered every tick. */
    private static final long START_RETRY_BACKOFF_MS = 15_000;

    private final AdminRaceClient admin;
    private final RaceOpsConfig config;
    private final ProposalService proposals;
    private final DirectorHealth health;
    private final DirectorMetrics metrics;
    private final EngagementService engagementService;
    private final MeshClient mesh;
    private final MeshExecutor executor;
    private volatile long retryStartAfter;

    private volatile RaceSnapshotEvent latest;
    /** The race this director already started; the game keeps serving WAITING snapshots until the start lands. */
    private final AtomicReference<String> startedRaceId = new AtomicReference<>();
    /** Races this director already proposed a boost for; one proposal per race keeps the console calm. */
    private final Set<String> boostedRaceIds = ConcurrentHashMap.newKeySet();
    /** Races the director already put a next-heat look on the console for. */
    private final Set<String> themedRaceIds = ConcurrentHashMap.newKeySet();
    /** One boost advice at a time; a second claim would only queue work behind a slow model. */
    private final AtomicBoolean boostBusy = new AtomicBoolean();
    /** One theme proposal at a time, same reason. */
    private final AtomicBoolean themeBusy = new AtomicBoolean();
    /**
     * The theme to assume when the game cannot be asked. The real answer is read from the game
     * over MCP before every proposal; this is only the last resort, and it is the game's own
     * boot default.
     */
    private volatile String currentTheme = "default";

    @Inject
    public RaceDirector(AdminRaceClient admin, RaceOpsConfig config, ProposalService proposals,
                        DirectorHealth health, DirectorMetrics metrics, EngagementService engagementService,
                        MeshClient mesh, MeshExecutor executor) {
        this.admin = admin;
        this.config = config;
        this.proposals = proposals;
        this.health = health;
        this.metrics = metrics;
        this.engagementService = engagementService;
        this.mesh = mesh;
        this.executor = executor;
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
        // Refresh the console's numbers first: the boost rule reads the report. This only
        // composes in process and may kick off an analyst call off-thread.
        engagementService.update(windowBase(event), event.snapshot());
        evaluate();
        releaseDeferred(event);
        claimBoost(event);
        claimTheme(event);
    }

    /**
     * A look approved mid-race waits for the heat to end, because the game refuses a restyle
     * while one runs. The first snapshot that is no longer running releases it: the finish
     * normally, and a reset so a queued card is not stranded when the operator starts over.
     * The MCP call runs off the snapshot observer like every other call in the proposal path.
     */
    private void releaseDeferred(RaceSnapshotEvent event) {
        if (!config.directorEnabled() || !proposals.hasDeferred() || running(event.snapshot())) {
            return;
        }
        executor.execute(proposals::applyDeferred);
    }

    private static boolean running(RaceSnapshot snapshot) {
        return "STARTED".equals(snapshot.status()) || "PAUSED".equals(snapshot.status());
    }

    /**
     * @return the oldest snapshot of the analytics window, or null when only one has been
     *         seen; the analyst then reports active and stalled as unknown
     */
    private static RaceSnapshot windowBase(RaceSnapshotEvent event) {
        return event.window().size() > 1 ? event.window().get(0) : null;
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

    /**
     * @return true when this snapshot is the one that carried the race from running to
     *         finished, which is the only moment the next heat's theme may be proposed
     */
    static boolean justFinished(RaceSnapshotEvent event) {
        RaceSnapshot snapshot = event.snapshot();
        RaceSnapshot previous = event.window().size() > 1
            ? event.window().get(event.window().size() - 2) : null;
        return "FINISHED".equals(snapshot.status())
            && previous != null && !"FINISHED".equals(previous.status());
    }

    @Scheduled(every = "1s")
    void evaluate() {
        if (!config.directorEnabled()) {
            return;
        }
        RaceSnapshotEvent event = latest;
        if (event == null || !"WAITING".equals(event.snapshot().status())) {
            return;
        }
        String raceId = event.snapshot().raceId();
        if (raceId.equals(startedRaceId.get()) || System.currentTimeMillis() < retryStartAfter) {
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
                health.succeeded();
                metrics.raceStarted();
                Log.infof("Director started %s (%s): %s", raceId, decision.reason(), result);
            } catch (Exception e) {
                startedRaceId.compareAndSet(raceId, null);
                retryStartAfter = System.currentTimeMillis() + START_RETRY_BACKOFF_MS;
                health.failed("start_race", e);
            }
        }
    }

    /**
     * The cheap part of the boost decision stays on the snapshot thread: status, no pending
     * card, a report for this race and the deficit rule. Only the analyst call and the model
     * run on the executor.
     */
    private void claimBoost(RaceSnapshotEvent event) {
        RaceSnapshot snapshot = event.snapshot();
        if (!config.directorEnabled() || !"STARTED".equals(snapshot.status())
            || !proposals.pending().isEmpty()) {
            return;
        }
        Optional<EngagementReport> report = engagementService.latest()
            .filter(r -> snapshot.raceId().equals(r.raceId()));
        if (report.isEmpty()) {
            return;
        }
        Optional<BoostPolicy.Situation> situation = BoostPolicy.evaluate(snapshot, report.get(),
            config.boostDeficitPercent());
        if (situation.isEmpty()) {
            return;
        }
        if (!boostedRaceIds.add(snapshot.raceId())) {
            return;
        }
        if (!boostBusy.compareAndSet(false, true)) {
            boostedRaceIds.remove(snapshot.raceId()); // another advice is already being prepared
            return;
        }
        executor.execute(() -> {
            try {
                proposeIntervention(event, report.get(), situation.get());
            } finally {
                boostBusy.set(false);
            }
        });
    }

    /**
     * One supervised decision. The analyst always runs first mid-race; the model reads its
     * diagnosis and either lets the director boost (returning done) or calls the creative
     * director for a next-heat look. The route is read from the scope, and whatever card the
     * model picked is still clamped before it reaches the console.
     *
     * <p>Two guardrails sit in front of the model's choice. A next-heat look needs a real
     * engagement collapse, because a card that replaces the boost the race needed helps
     * nobody; the same share opens the creative agent's activation condition, so on a
     * soft-tapping diagnosis the planner is never even offered that hop. And a decision that
     * lands after the heat ended is dropped: the cards exist to change a race that is still
     * running.
     */
    private void proposeIntervention(RaceSnapshotEvent event, EngagementReport report,
                                     BoostPolicy.Situation situation) {
        RaceSnapshot snapshot = event.snapshot();
        String raceId = snapshot.raceId();
        if (!proposals.pending().isEmpty()) {
            boostedRaceIds.remove(raceId); // the console is busy; look again on a later snapshot
            return;
        }
        if (!raceStillRunning(raceId)) {
            boostedRaceIds.remove(raceId);
            Log.infof("Dropping the intervention for %s before it starts: the race is not running", raceId);
            return;
        }
        String analysis = DirectorDecisions.analysisRequest(windowBase(event), snapshot);
        if (meshEnabled() && config.aiEnabled()) {
            String phase = DirectorDecisions.racePhase(snapshot);
            String theme = activeTheme();
            try {
                Intervention intervention = mesh.intervene(
                    DirectorDecisions.interventionRequest(phase,
                        DirectorDecisions.raceFacts(snapshot, report, situation)),
                    DirectorDecisions.supervisorContext(phase),
                    analysis,
                    DirectorDecisions.themeRequest(raceId, theme, config.eventName(), config.audience()),
                    situation.stalledShare() >= BoostPolicy.QUITTER_SHARE,
                    MeshClient.INTERVENTION_TIMEOUT_SECONDS);
                if (!raceStillRunning(raceId)) {
                    boostedRaceIds.remove(raceId);
                    Log.infof("Dropping the intervention for %s: the race ended while the model decided", raceId);
                    return;
                }
                DirectorDecisions.Route route = DirectorDecisions.route(
                    intervention.diagnosed(), intervention.themed());
                Log.infof("Supervisor route: %s (analyst ran: %s, creative ran: %s)",
                    route, intervention.diagnosed(), intervention.themed());
                if (route == DirectorDecisions.Route.THEME) {
                    if (DirectorDecisions.guardRoute(route, situation) == DirectorDecisions.Route.THEME) {
                        proposeNextHeat(snapshot, theme, intervention.answer(), true);
                        return;
                    }
                    // The model overshot: players still tap, so the race needs the boost.
                    Log.infof("Model proposed a next-heat look, but only %.0f%% of team %d stopped "
                            + "tapping; proposing the boost instead",
                        situation.stalledShare() * 100, situation.team());
                }
                if (intervention.diagnosed()
                    && proposeDiagnosedBoost(intervention.diagnosis(), situation, snapshot)) {
                    return;
                }
            } catch (Exception e) {
                Log.warnf(e, "The supervisor failed, asking the analyst directly");
                try {
                    if (proposeDiagnosedBoost(mesh.analyze(analysis), situation, snapshot)) {
                        return;
                    }
                } catch (Exception direct) {
                    Log.warnf(direct, "The analyst failed too, using the template proposal");
                }
            }
        }
        Proposal proposal = DirectorDecisions.fallbackProposal(situation, snapshot);
        if (!proposals.propose(proposal)) {
            boostedRaceIds.remove(raceId);
            return;
        }
        Log.infof("Director proposes boosting team %d (%.1f%% behind): %s",
            proposal.team(), situation.deficit(), proposal.message());
    }

    /**
     * @return true while the race that started this decision is still the one running, so a
     *         card that took too long is dropped instead of landing on a finished heat
     */
    private boolean raceStillRunning(String raceId) {
        RaceSnapshotEvent event = latest;
        return event != null && stillRunning(event.snapshot(), raceId);
    }

    /**
     * @return true when this snapshot belongs to the race and still shows it running
     */
    static boolean stillRunning(RaceSnapshot snapshot, String raceId) {
        return snapshot != null
            && raceId.equals(snapshot.raceId())
            && "STARTED".equals(snapshot.status());
    }

    /**
     * The diagnosis route: the analyst's sentence becomes the card's reason, the policy keeps
     * the numbers. @return false when the answer was not a diagnosis, so the caller can fall
     * back instead of showing an empty card.
     */
    private boolean proposeDiagnosedBoost(String answer, BoostPolicy.Situation situation,
                                          RaceSnapshot snapshot) {
        DirectorDecisions.BoostAdvice advice = DirectorDecisions.parseBoostAdvice(answer);
        if (!advice.propose()) {
            Log.info("The analyst's answer carried no diagnosis to propose a boost on");
            return false;
        }
        Proposal proposal = DirectorDecisions.advice(advice, situation, snapshot);
        if (!proposals.propose(proposal)) {
            boostedRaceIds.remove(snapshot.raceId());
            return false;
        }
        Log.infof("Director proposes boosting team %d (%.1f%% behind): %s",
            proposal.team(), situation.deficit(), proposal.message());
        return true;
    }

    /**
     * Between heats: when a race finishes, propose the theme for the next one. The game
     * only restyles between races, which is exactly this window; if the operator approves
     * after the next race already started, the game rejects it and the card records the
     * error. The same entry point as mid-race, with the phase in the facts and a standing
     * order in the supervisor context, so the scheduled moment stays reliable. The creative
     * agent picks from the presets the game actually offers or shapes an AI brief, with a
     * rotating deterministic fallback when the mesh or model is off.
     */
    private void claimTheme(RaceSnapshotEvent event) {
        if (!config.directorEnabled() || !proposals.pending().isEmpty() || !justFinished(event)) {
            return;
        }
        RaceSnapshot snapshot = event.snapshot();
        if (!themedRaceIds.add(snapshot.raceId())) {
            return;
        }
        if (!themeBusy.compareAndSet(false, true)) {
            themedRaceIds.remove(snapshot.raceId());
            return;
        }
        executor.execute(() -> {
            try {
                proposeTheme(event);
            } finally {
                themeBusy.set(false);
            }
        });
    }

    private void proposeTheme(RaceSnapshotEvent event) {
        RaceSnapshot snapshot = event.snapshot();
        try {
            List<AdminRaceClient.ThemeOption> options = admin.listThemes();
            health.succeeded();
            currentTheme = activeTheme();
            Proposal fallback = DirectorDecisions.themeFallback(options, snapshot.raceId(),
                Math.floorMod(snapshot.raceId().hashCode(), 97), currentTheme);
            Proposal proposal = null;
            if (meshEnabled() && config.aiEnabled()) {
                try {
                    Intervention intervention = mesh.intervene(
                        DirectorDecisions.interventionRequest(DirectorDecisions.BETWEEN_HEATS,
                            DirectorDecisions.themeFacts(snapshot, currentTheme)),
                        DirectorDecisions.supervisorContext(DirectorDecisions.BETWEEN_HEATS),
                        DirectorDecisions.analysisRequest(windowBase(event), snapshot),
                        DirectorDecisions.themeRequest(snapshot.raceId(), currentTheme,
                            config.eventName(), config.audience()),
                        true, MeshClient.THEME_INTERVENTION_TIMEOUT_SECONDS);
                    if (DirectorDecisions.route(intervention.diagnosed(), intervention.themed())
                        == DirectorDecisions.Route.THEME) {
                        proposal = themeCard(intervention.answer(), options, snapshot, currentTheme, fallback);
                    }
                } catch (Exception e) {
                    Log.warnf(e, "The creative agent proposed nothing usable, using the fallback");
                }
            }
            if (proposal == null) {
                proposal = fallback;
            }
            if (!proposals.propose(proposal)) {
                themedRaceIds.remove(snapshot.raceId());
                return;
            }
            Log.infof("Director proposes theme %s for the next heat (on now: %s): %s",
                proposal.presetId() != null ? proposal.presetId() : proposal.kind(), currentTheme,
                proposal.message());
        } catch (Exception e) {
            health.failed("list_preset_themes", e);
        }
    }

    /**
     * A next-heat look shown during a race. It is marked deferred, so an approval waits for
     * the finish: the game refuses a restyle mid-heat. This card also claims the heat's theme
     * slot, so the finish does not propose a second one.
     */
    private void proposeNextHeat(RaceSnapshot snapshot, String currentTheme, String creativeAnswer,
                                 boolean midRace) {
        try {
            List<AdminRaceClient.ThemeOption> options = admin.listThemes();
            health.succeeded();
            Proposal fallback = DirectorDecisions.themeFallback(options, snapshot.raceId(),
                Math.floorMod(snapshot.raceId().hashCode(), 97), currentTheme);
            Proposal proposal = themeCard(creativeAnswer, options, snapshot, currentTheme, fallback);
            if (midRace) {
                proposal = proposal.defer();
            }
            if (!proposals.propose(proposal)) {
                Log.info("The console already has a card; the next-heat look waits");
                return;
            }
            if (midRace) {
                themedRaceIds.add(snapshot.raceId());
            }
            Log.infof("Director proposes theme %s for the next heat (on now: %s): %s",
                proposal.presetId() != null ? proposal.presetId() : proposal.kind(), currentTheme,
                proposal.message());
        } catch (Exception e) {
            health.failed("list_preset_themes", e);
        }
    }

    /**
     * Clamps the creative answer to a card, falling back to the deterministic pick when the
     * answer is unusable.
     */
    private static Proposal themeCard(String creativeAnswer, List<AdminRaceClient.ThemeOption> options,
                                      RaceSnapshot snapshot, String currentTheme, Proposal fallback) {
        if (creativeAnswer == null || creativeAnswer.isBlank()) {
            return fallback;
        }
        try {
            return DirectorDecisions.theme(DirectorDecisions.parseThemeAdvice(creativeAnswer),
                options, snapshot.raceId(), currentTheme, fallback);
        } catch (IllegalArgumentException e) {
            Log.warnf(e, "The creative answer was unusable, using the preset fallback");
            return fallback;
        }
    }

    /**
     * The theme the game is wearing, read from the game itself. The local field is only a
     * fallback: it starts at the configured default and forgets an operator's theme when this
     * app restarts, which is how the director once proposed the theme already on the track.
     */
    private String activeTheme() {
        try {
            return admin.activeTheme();
        } catch (Exception e) {
            Log.warnf("Cannot read the active theme, assuming %s: %s", currentTheme, e.getMessage());
            return currentTheme;
        }
    }

    private boolean meshEnabled() {
        return config.meshEnabled();
    }
}
