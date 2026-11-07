package org.raceops.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.raceops.mesh.MeshClient;
import org.raceops.mesh.MeshExecutor;
import org.raceops.model.RaceSnapshot;
import org.raceops.monitor.OpsBroadcaster;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * The engagement numbers on the console. The race keeps updating several times a second, so
 * the report is always composed in process from the current snapshot and only the diagnosis
 * and the active/stalled counts come from the analyst. Those arrive over A2A, take a few
 * seconds, and can hang: the call runs on {@link MeshExecutor}, never on the snapshot
 * observer, and the console keeps showing the in-process numbers while it is out.
 *
 * <p>A late answer is merged against the newest snapshot, not against the one it was asked
 * about, so its status, elapsed time, positions and power are never stale. An answer older
 * than {@link #ANALYST_ANSWER_TTL_MS} is dropped: the console would rather say it is degraded
 * than show numbers from a race state that has moved on.
 */
@ApplicationScoped
public class EngagementService {

    /** How often the analyst is asked; a call takes a few seconds, so calls overlap the console's refresh. */
    static final long ANALYST_INTERVAL_MS = 2_000;

    /** An answer older than this is dropped rather than merged into a newer snapshot. */
    static final long ANALYST_ANSWER_TTL_MS = 6_000;

    private final MeshClient mesh;
    private final MeshExecutor executor;
    private final OpsBroadcaster broadcaster;
    private final ObjectMapper mapper;
    private final boolean meshEnabled;

    private volatile EngagementReport latest;
    /** The newest snapshot pair, kept so a late analyst answer can be merged against it. */
    private volatile RaceSnapshot base;
    private volatile RaceSnapshot current;
    private volatile AnalystAnswer analystAnswer;
    private final AtomicBoolean analystBusy = new AtomicBoolean();
    private volatile long lastAnalystAttempt;
    private volatile String lastAnalystRaceId;

    @Inject
    public EngagementService(MeshClient mesh, MeshExecutor executor, OpsBroadcaster broadcaster,
                             ObjectMapper mapper,
                             @ConfigProperty(name = "race-ops.mesh-enabled", defaultValue = "false")
                             boolean meshEnabled) {
        this.mesh = mesh;
        this.executor = executor;
        this.broadcaster = broadcaster;
        this.mapper = mapper;
        this.meshEnabled = meshEnabled;
    }

    /**
     * @param base    a snapshot from earlier in the same race, or null when only one has
     *                been seen; the analyst then reports active and stalled as unknown
     * @param current the latest snapshot
     */
    public void update(RaceSnapshot base, RaceSnapshot current) {
        if (current == null || current.raceId() == null) {
            return;
        }
        if (!current.raceId().equals(lastAnalystRaceId)) {
            // A new race: the previous answers describe a race that no longer exists.
            lastAnalystRaceId = current.raceId();
            lastAnalystAttempt = 0;
            analystAnswer = null;
        }
        this.base = base;
        this.current = current;
        publish();
        maybeAskAnalyst();
    }

    public Optional<EngagementReport> latest() {
        return Optional.ofNullable(latest);
    }

    /**
     * Composes the report from the newest snapshot and the freshest analyst answer. Cheap
     * enough to run on every snapshot; serialized so a late answer cannot overwrite a newer
     * composition with one built from an older field read.
     */
    private synchronized void publish() {
        RaceSnapshot snapshot = current;
        if (snapshot == null) {
            return;
        }
        EngagementReport report = merge(inProcess(snapshot), analystAnswer, snapshot);
        latest = report;
        broadcaster.refresh(report);
    }

    /**
     * Asks the analyst off the snapshot thread. One call in flight at a time: a call that
     * hangs must not spawn a new one every interval and exhaust the pool.
     */
    private void maybeAskAnalyst() {
        if (!meshEnabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAnalystAttempt < ANALYST_INTERVAL_MS) {
            return;
        }
        if (!analystBusy.compareAndSet(false, true)) {
            return;
        }
        lastAnalystAttempt = now;
        String raceId = current.raceId();
        String request = request(base, current);
        executor.execute(() -> {
            try {
                analystAnswer = parse(mesh.analyze(request), raceId);
                publish();
            } catch (JsonProcessingException e) {
                Log.warnf("The analyst answered something unreadable (%s); showing the in-process numbers",
                    e.getOriginalMessage());
            } catch (RuntimeException e) {
                Log.warnf("The analyst is unreachable (%s); showing the in-process numbers", e.getMessage());
            } finally {
                analystBusy.set(false);
            }
        });
    }

    private AnalystAnswer parse(String raw, String raceId) throws JsonProcessingException {
        JsonNode json = mapper.readTree(raw);
        JsonNode engagement = json.get("engagement");
        if (engagement == null || engagement.isNull()) {
            throw new IllegalStateException("The analyst answered without an engagement report");
        }
        EngagementReport parsed = mapper.treeToValue(engagement, EngagementReport.class);
        String diagnosis = json.path("diagnosis").asText("");
        return new AnalystAnswer(raceId, new EngagementReport(raceId, parsed.status(),
            parsed.elapsedMs(), parsed.teams(), diagnosis, "analyst"), System.currentTimeMillis());
    }

    /**
     * The in-process report overlaid with the analyst's engagement facts. Only tappers,
     * active, stalled, engagement rate and the diagnosis come from the answer: the rest is
     * read from the snapshot the console is showing.
     */
    private EngagementReport merge(EngagementReport inProcess, AnalystAnswer answer, RaceSnapshot snapshot) {
        if (answer == null || !snapshot.raceId().equals(answer.raceId())
            || System.currentTimeMillis() - answer.answeredAt() > ANALYST_ANSWER_TTL_MS) {
            return inProcess;
        }
        EngagementReport report = answer.report();
        Map<Integer, EngagementReport.TeamEngagement> engagement = report.teams() == null ? Map.of()
            : report.teams().stream().collect(Collectors.toMap(
                EngagementReport.TeamEngagement::team, team -> team, (first, second) -> first));
        List<EngagementReport.TeamEngagement> teams = inProcess.teams().stream()
            .map(team -> {
                EngagementReport.TeamEngagement a = engagement.get(team.team());
                return a == null ? team : new EngagementReport.TeamEngagement(team.team(),
                    team.position(), a.tappers(), a.active(), a.stalled(), a.engagementRate(),
                    team.power(), team.distance());
            }).toList();
        String diagnosis = report.diagnosis() == null || report.diagnosis().isBlank()
            ? inProcess.diagnosis() : report.diagnosis();
        return new EngagementReport(snapshot.raceId(), snapshot.status(), snapshot.elapsedMs(), teams,
            diagnosis, "analyst");
    }

    private String request(RaceSnapshot base, RaceSnapshot current) {
        try {
            return mapper.writeValueAsString(new AnalysisRequest(base, current));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize the analysis request: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * The degraded report: the console keeps the team lines, but active and stalled are
     * reported as zero rather than invented, and the diagnosis only states the gap.
     */
    EngagementReport inProcess(RaceSnapshot current) {
        List<EngagementReport.TeamEngagement> teams = current.teams() == null ? List.of()
            : current.teams().stream().map(team -> new EngagementReport.TeamEngagement(
                team.id(), team.position(), current.teamPlayers(team.id()).size(), 0, 0, 0,
                team.power(), team.distance())).toList();
        return new EngagementReport(current.raceId(), current.status(), current.elapsedMs(), teams,
            degradedDiagnosis(teams), "in-process");
    }

    private static String degradedDiagnosis(List<EngagementReport.TeamEngagement> teams) {
        if (teams.size() < 2) {
            return "Not enough teams to compare.";
        }
        EngagementReport.TeamEngagement ahead = teams.stream()
            .max(Comparator.comparingDouble(EngagementReport.TeamEngagement::distance)).orElseThrow();
        EngagementReport.TeamEngagement behind = teams.stream()
            .min(Comparator.comparingDouble(EngagementReport.TeamEngagement::distance)).orElseThrow();
        double deficit = ahead.distance() - behind.distance();
        if (deficit < 1.0) {
            return "The field is level: %.1f points between the teams.".formatted(deficit);
        }
        return "Team %d is %.1f points behind; the analyst is offline, so this is only the raw gap."
            .formatted(behind.team(), deficit);
    }

    private record AnalystAnswer(String raceId, EngagementReport report, long answeredAt) {
    }

    private record AnalysisRequest(RaceSnapshot base, RaceSnapshot current) {
    }
}
