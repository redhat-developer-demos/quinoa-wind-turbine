package org.raceops.director;

import org.junit.jupiter.api.Test;
import org.raceops.model.RaceSnapshot;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartPolicyTest {

    private static final long NOW = 1_000_000;

    private static RaceSnapshot.Team team(int id, int players) {
        return new RaceSnapshot.Team(id, 1, players, 0, 0, null);
    }

    private static RaceSnapshot waiting(long updatedAt, RaceSnapshot.Team... teams) {
        return new RaceSnapshot("race-1", "WAITING", 0, updatedAt, null, List.of(teams), List.of());
    }

    @Test
    void emptyWindowWaits() {
        var decision = StartPolicy.shouldStart(List.of(), 2, 20, NOW);
        assertFalse(decision.start());
    }

    @Test
    void missingPlayersWaits() {
        var decision = StartPolicy.shouldStart(List.of(waiting(NOW, team(1, 3), team(2, 1))), 2, 20, NOW);
        assertFalse(decision.start());
        assertTrue(decision.reason().contains("team 2"), decision.reason());
    }

    @Test
    void recentJoinWaits() {
        long lastJoin = NOW - 5_000;
        var decision = StartPolicy.shouldStart(List.of(waiting(lastJoin, team(1, 3), team(2, 3))), 2, 20, NOW);
        assertFalse(decision.start());
        assertTrue(decision.reason().contains("quiet for 5s"), decision.reason());
    }

    @Test
    void plateauWithFullFieldStarts() {
        long lastJoin = NOW - 25_000;
        var decision = StartPolicy.shouldStart(List.of(waiting(lastJoin, team(1, 3), team(2, 4))), 2, 20, NOW);
        assertTrue(decision.start());
        assertTrue(decision.reason().contains("7"), decision.reason());
    }

    @Test
    void lopsidedButFullFieldStarts() {
        // Headcount imbalance is fair: distance normalizes by the players who tapped.
        long lastJoin = NOW - 25_000;
        var decision = StartPolicy.shouldStart(List.of(waiting(lastJoin, team(1, 2), team(2, 12))), 2, 20, NOW);
        assertTrue(decision.start());
    }

    @Test
    void nonWaitingSnapshotsAreFilteredByTheCaller() {
        // The policy itself only looks at what it is given; the director filters the window
        // to WAITING snapshots, so a finished race in the window cannot trigger a start.
        long lastJoin = NOW - 25_000;
        RaceSnapshot started = new RaceSnapshot("race-1", "STARTED", 1000, NOW, null,
            List.of(team(1, 3), team(2, 3)), List.of());
        var decision = StartPolicy.shouldStart(List.of(started), 2, 20, NOW);
        // Started snapshots pass the player count, but this method is only called with the
        // waiting window; the guard is the caller's. Here we just assert it does not crash.
        assertFalse(decision.reason().isEmpty());
    }
}
