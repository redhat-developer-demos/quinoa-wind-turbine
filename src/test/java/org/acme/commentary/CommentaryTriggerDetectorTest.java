package org.acme.commentary;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import org.acme.race.RaceState;

import static org.acme.race.RaceState.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class CommentaryTriggerDetectorTest {
    private final CommentaryTriggerDetector detector =
        new CommentaryTriggerDetector(Duration.ofSeconds(5), 2, 150);

    @Test
    void zeroAndTiesDoNotChangeTheLastUniqueLeader() {
        detector.accept(state("a", WAITING, 0, 0, 0, 0));
        assertTrue(detector.accept(state("a", STARTED, 1, 1, 0, 0)).isEmpty());
        assertEquals(List.of("lead"), types(detector.accept(state("a", STARTED, 2, 2, 10, 0))));
        assertTrue(detector.accept(state("a", STARTED, 3, 3, 10, 10)).isEmpty());
        assertTrue(detector.accept(state("a", STARTED, 4, 4, 12, 10)).isEmpty());
        var changes = detector.accept(state("a", STARTED, 5, 5, 12, 13));
        assertEquals(2, changes.getFirst().team());
        assertEquals("lead", changes.getFirst().type());
    }

    @Test
    void skippedMilestonesAnnounceOnlyTheHighestAndDoNotRepeat() {
        detector.accept(state("a", WAITING, 0, 0, 0, 0));
        var triggers = detector.accept(state("a", STARTED, 1, 1, 80, 50));
        assertEquals(List.of(75, 50), triggers.stream().filter(t -> t.type().equals("milestone"))
            .map(CommentaryTrigger::milestone).toList());
        assertTrue(detector.accept(state("a", STARTED, 2, 2, 85, 55)).isEmpty());
    }

    @Test
    void baselineSuppressesHistoryAndIgnoresOlderAndRepeatedSnapshots() {
        detector.accept(state("a", STARTED, 12_000, 10, 70, 30));
        assertTrue(detector.accept(state("a", STARTED, 13_000, 10, 80, 40)).isEmpty());
        assertTrue(detector.accept(state("a", STARTED, 11_000, 9, 80, 40)).isEmpty());
        var triggers = detector.accept(state("a", STARTED, 14_000, 11, 80, 40));
        assertEquals(List.of("milestone"), types(triggers));
        assertEquals(75, triggers.getFirst().milestone());
    }

    @Test
    void newRaceClearsMilestonesLeaderAndFinish() {
        detector.accept(state("a", STARTED, 1, 20, 90, 10));
        detector.accept(state("a", FINISHED, 2, 21, 100, 10));
        detector.accept(state("b", WAITING, 0, 0, 0, 0));
        assertEquals(List.of("lead", "milestone"),
            types(detector.accept(state("b", STARTED, 1, 1, 30, 0))));
    }

    @Test
    void finishEmitsOnceAndSuppressesRoutineTriggers() {
        detector.accept(state("a", WAITING, 0, 0, 0, 0));
        var triggers = detector.accept(state("a", FINISHED, 20_000, 1, 100, 70));
        assertEquals(List.of("finish"), types(triggers));
        assertEquals(1, triggers.getFirst().team());
        assertEquals(4, triggers.getFirst().priority());
        assertTrue(detector.accept(state("a", FINISHED, 20_000, 2, 100, 80)).isEmpty());
        var attached = new CommentaryTriggerDetector(Duration.ofSeconds(5), 2, 150);
        assertTrue(attached.accept(state("a", FINISHED, 20_000, 1, 100, 70)).isEmpty());
        assertTrue(attached.accept(state("a", FINISHED, 20_000, 2, 100, 80)).isEmpty());
    }

    @Test
    void surgeRequiresTwoCompleteWindowsAndSelectsLargestQualifyingIncrease() {
        detector.accept(players(0, 0, 0, 0));
        assertTrue(detector.accept(players(4_999, 1, 50, 100)).isEmpty());
        assertTrue(detector.accept(players(5_000, 2, 60, 100)).isEmpty());
        assertTrue(detector.accept(players(9_999, 3, 200, 350)).isEmpty());
        var triggers = detector.accept(players(10_000, 4, 210, 400));
        assertEquals(List.of("surge"), types(triggers));
        assertEquals("bob", triggers.getFirst().player());
        assertEquals(2, triggers.getFirst().team());
        assertTrue(detector.accept(players(15_000, 5, 350, 600)).isEmpty());
    }

    @Test
    void waitingRegistrationProvidesTheFirstRunningWindowBaseline() {
        detector.accept(RaceState.waiting("a"));
        detector.accept(new RaceState("a", WAITING, 0, 1, null, List.of(),
            List.of(new RaceState.PlayerState("ada", 1, 0), new RaceState.PlayerState("bob", 2, 0))));
        assertTrue(detector.accept(players(5_000, 2, 50, 50)).isEmpty());
        var trigger = detector.accept(players(10_000, 3, 200, 200)).getFirst();
        assertEquals("ada", trigger.player());
    }

    @Test
    void surgeUsesRunningTimeAcrossPauseAndHonorsMinimumIncrease() {
        detector.accept(players(0, 0, 0, 0));
        detector.accept(players(5_000, 1, 50, 10));
        var paused = new RaceState("a", PAUSED, 5_000, 2, null, List.of(),
            List.of(new RaceState.PlayerState("ada", 1, 50), new RaceState.PlayerState("bob", 2, 10)));
        assertTrue(detector.accept(paused).isEmpty());
        assertTrue(detector.accept(players(9_999, 3, 199, 100)).isEmpty());
        assertTrue(detector.accept(players(10_000, 4, 199, 100)).isEmpty());
        assertEquals("ada", detector.accept(players(15_000, 5, 500, 200)).getFirst().player());
    }

    @Test
    void skippedWindowsAndLateJoiningPlayersDoNotProduceSpuriousSurges() {
        detector.accept(players(0, 0, 0, 0));
        detector.accept(players(5_000, 1, 50, 100));
        assertTrue(detector.accept(players(20_000, 2, 1000, 2000)).isEmpty());
        assertTrue(detector.accept(players(25_000, 3, 2000, 4000)).isEmpty());
        var joining = new RaceState("a", STARTED, 30_000, 4, null, List.of(),
            List.of(new RaceState.PlayerState("new", 1, 10_000)));
        assertTrue(detector.accept(joining).isEmpty());
    }

    @Test
    void configurableSurgeThresholdAndWindowAreApplied() {
        var configured = new CommentaryTriggerDetector(Duration.ofSeconds(1), 3, 30);
        configured.accept(players(0, 0, 0, 0));
        configured.accept(players(1_000, 1, 10, 10));
        assertEquals("ada", configured.accept(players(2_000, 2, 40, 39)).getFirst().player());
        assertThrows(IllegalArgumentException.class,
            () -> new CommentaryTriggerDetector(Duration.ZERO, 2, 150));
    }

    private static List<String> types(List<CommentaryTrigger> triggers) {
        return triggers.stream().map(CommentaryTrigger::type).toList();
    }

    private static RaceState players(long elapsed, long updatedAt, long ada, long bob) {
        return new RaceState("a", STARTED, elapsed, updatedAt, null, List.of(), List.of(
            new RaceState.PlayerState("ada", 1, ada), new RaceState.PlayerState("bob", 2, bob)));
    }

    private static RaceState state(String id, RaceState.Status status, long elapsed,
                                    long updatedAt, double one, double two) {
        return new RaceState(id, status, elapsed, updatedAt, status == FINISHED ? 1 : null, List.of(
            new RaceState.TeamState(1, one >= two ? 1 : 2, 1, 0, one, null),
            new RaceState.TeamState(2, two >= one ? 1 : 2, 1, 0, two, null)), List.of());
    }
}
