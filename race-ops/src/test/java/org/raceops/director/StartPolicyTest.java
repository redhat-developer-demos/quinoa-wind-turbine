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
    void aSnapshotWithoutTeamsWaits() {
        // A fresh game's first WAITING snapshot has no teams; that must not start a race.
        RaceSnapshot empty = new RaceSnapshot("race-0", "WAITING", 0, NOW - 60_000, null, List.of(), List.of());
        var decision = StartPolicy.shouldStart(List.of(empty), 2, 20, NOW);
        assertFalse(decision.start());
        assertTrue(decision.reason().contains("no teams"), decision.reason());
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
}
