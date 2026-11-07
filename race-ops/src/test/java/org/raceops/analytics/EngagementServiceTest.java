package org.raceops.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.raceops.mesh.MeshClient;
import org.raceops.mesh.MeshExecutor;
import org.raceops.model.RaceSnapshot;
import org.raceops.monitor.OpsBroadcaster;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console's numbers with and without the analyst. The point of the fallback is that a
 * specialist that is down never blanks the console or blocks the race: the report is always
 * composed from the snapshot, and the analyst only adds the diagnosis and the active/stalled
 * counts when its answer is fresh.
 */
class EngagementServiceTest {

    private static RaceSnapshot snapshot(String raceId, String status, double team1, double team2) {
        return snapshot(raceId, status, team1, team2, 5_000);
    }

    private static RaceSnapshot snapshot(String raceId, String status, double team1, double team2,
                                         long elapsedMs) {
        return new RaceSnapshot(raceId, status, elapsedMs, 1_000, null,
            List.of(new RaceSnapshot.Team(1, 2, 3, 120, team1, null),
                new RaceSnapshot.Team(2, 1, 4, 260, team2, null)),
            List.of(new RaceSnapshot.Player("ada", 1, 40),
                new RaceSnapshot.Player("bo", 1, 40),
                new RaceSnapshot.Player("cy", 1, 40),
                new RaceSnapshot.Player("di", 2, 65),
                new RaceSnapshot.Player("ed", 2, 65),
                new RaceSnapshot.Player("fi", 2, 65),
                new RaceSnapshot.Player("gi", 2, 65)));
    }

    /** Off the mesh, no call must be attempted. */
    private static EngagementService withoutMesh() {
        return new EngagementService(null, Runnable::run, new OpsBroadcaster(), new ObjectMapper(), false);
    }

    private static EngagementService withMesh(MeshClient mesh, ObjectMapper mapper) {
        return withMesh(mesh, mapper, Runnable::run);
    }

    private static EngagementService withMesh(MeshClient mesh, ObjectMapper mapper, MeshExecutor executor) {
        return new EngagementService(mesh, executor, new OpsBroadcaster(), mapper, true);
    }

    private static MeshClient mesh(ObjectMapper mapper, AtomicInteger calls, String answer) {
        return new MeshClient(null, null, null, null, mapper) {
            @Override
            public String analyze(String requestJson) {
                calls.incrementAndGet();
                if (answer == null) {
                    throw new IllegalStateException("connection refused");
                }
                return answer;
            }
        };
    }

    private static String analystAnswer(String diagnosis, double team1Distance) {
        return """
            {"engagement":{"raceId":"race-1","status":"STARTED","elapsedMs":4100,"teams":[
              {"team":1,"position":1,"tappers":3,"active":1,"stalled":2,"engagementRate":0.33,"power":999,"distance":%s},
              {"team":2,"position":2,"tappers":4,"active":4,"stalled":0,"engagementRate":1.0,"power":999,"distance":99.0}]},
             "diagnosis":"%s"}
            """.formatted(team1Distance, diagnosis);
    }

    @Test
    void withoutTheMeshTheReportIsInProcessAndSaysSo() {
        EngagementService service = withoutMesh();
        service.update(null, snapshot("race-1", "STARTED", 30, 50));

        EngagementReport report = service.latest().orElseThrow();
        assertEquals("in-process", report.source());
        assertEquals(0, report.teams().get(0).active());
        assertEquals(0, report.teams().get(0).stalled());
        assertEquals(3, report.teams().get(0).tappers());
        assertTrue(report.diagnosis().contains("Team 1"), report.diagnosis());
        assertTrue(report.diagnosis().contains("20.0 points behind"), report.diagnosis());
    }

    @Test
    void withTheAnalystTheReportIsItsAnswer() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        EngagementService service = withMesh(mesh(mapper, calls,
            analystAnswer("Team 1 is 20.0 points behind because 2 of its 3 players stopped tapping.", 30.0)),
            mapper);

        service.update(null, snapshot("race-1", "STARTED", 30, 50));

        EngagementReport report = service.latest().orElseThrow();
        assertEquals("analyst", report.source());
        assertEquals(2, report.teams().get(0).stalled());
        assertTrue(report.diagnosis().contains("stopped tapping"), report.diagnosis());
        assertEquals(1, calls.get());
    }

    @Test
    void theAnalystFactsAreMergedIntoTheSnapshotTheConsoleShows() {
        ObjectMapper mapper = new ObjectMapper();
        EngagementService service = withMesh(mesh(mapper, new AtomicInteger(),
            analystAnswer("Team 1 stopped tapping.", 99.0)), mapper);

        service.update(null, snapshot("race-1", "STARTED", 30, 50));

        EngagementReport report = service.latest().orElseThrow();
        assertEquals("analyst", report.source());
        assertEquals(2, report.teams().get(0).stalled(), "the analyst owns active/stalled");
        assertEquals(5_000, report.elapsedMs(), "elapsed comes from the snapshot, not the answer");
        assertEquals(30.0, report.teams().get(0).distance(), 0.001);
        assertEquals(120, report.teams().get(0).power(), "power comes from the snapshot, not the answer");
        assertEquals(2, report.teams().get(0).position());
    }

    @Test
    void aFailingAnalystFallsBackWithoutBlankingTheConsole() {
        ObjectMapper mapper = new ObjectMapper();
        EngagementService service = withMesh(mesh(mapper, new AtomicInteger(), null), mapper);

        service.update(null, snapshot("race-1", "STARTED", 30, 50));

        EngagementReport report = service.latest().orElseThrow();
        assertEquals("in-process", report.source());
        assertTrue(report.diagnosis().contains("analyst is offline"), report.diagnosis());
    }

    @Test
    void theAnalystIsNotAskedOnEverySnapshot() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        EngagementService service = withMesh(mesh(mapper, calls,
            analystAnswer("Team 1 stopped tapping.", 30.0)), mapper);

        service.update(null, snapshot("race-1", "STARTED", 30, 50));
        service.update(null, snapshot("race-1", "STARTED", 31, 51));

        assertEquals(1, calls.get(), "the second snapshot inside the window reuses the answer");
    }

    @Test
    void aNewRaceAsksTheAnalystAgain() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        EngagementService service = withMesh(mesh(mapper, calls, null), mapper);

        service.update(null, snapshot("race-1", "STARTED", 30, 50));
        service.update(null, snapshot("race-2", "STARTED", 30, 50));

        assertEquals(2, calls.get());
    }

    @Test
    void theAnalystRunsOffTheSnapshotThread() {
        ObjectMapper mapper = new ObjectMapper();
        Queue<Runnable> queued = new ArrayDeque<>();
        EngagementService service = withMesh(mesh(mapper, new AtomicInteger(),
            analystAnswer("Team 1 stopped tapping.", 30.0)), mapper, queued::add);

        service.update(null, snapshot("race-1", "STARTED", 30, 50));

        assertEquals("in-process", service.latest().orElseThrow().source(),
            "the snapshot must not wait for the analyst");
        assertEquals(1, queued.size());

        queued.remove().run();
        assertEquals("analyst", service.latest().orElseThrow().source());
    }

    @Test
    void aLateAnswerIsMergedIntoTheNewestSnapshot() {
        ObjectMapper mapper = new ObjectMapper();
        Queue<Runnable> queued = new ArrayDeque<>();
        EngagementService service = withMesh(mesh(mapper, new AtomicInteger(),
            analystAnswer("Team 1 stopped tapping.", 99.0)), mapper, queued::add);

        service.update(null, snapshot("race-1", "STARTED", 30, 50, 5_000));
        service.update(null, snapshot("race-1", "STARTED", 31, 51, 6_000));
        assertEquals("in-process", service.latest().orElseThrow().source());

        queued.remove().run();

        EngagementReport report = service.latest().orElseThrow();
        assertEquals("analyst", report.source());
        assertEquals(6_000, report.elapsedMs(), "the answer was merged against the newer snapshot");
        assertEquals(31.0, report.teams().get(0).distance(), 0.001);
        assertTrue(report.diagnosis().contains("stopped tapping"), report.diagnosis());
    }
}
