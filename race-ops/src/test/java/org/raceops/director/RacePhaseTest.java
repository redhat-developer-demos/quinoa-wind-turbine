package org.raceops.director;

import org.junit.jupiter.api.Test;
import org.raceops.model.RaceSnapshot;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The phase is a prompt input now, so a wrong label would make every boost message talk about the
 * wrong moment of the race.
 */
class RacePhaseTest {

    @Test
    void phaseFollowsTheLeadingDistance() {
        assertEquals("opening laps", DirectorDecisions.racePhase(snapshot(12.0)));
        assertEquals("middle of the race", DirectorDecisions.racePhase(snapshot(55.0)));
        assertEquals("final laps", DirectorDecisions.racePhase(snapshot(92.0)));
    }

    @Test
    void aSnapshotWithoutTeamsCountsAsTheOpeningLaps() {
        assertEquals("opening laps", DirectorDecisions.racePhase(
            new RaceSnapshot("race-1", "STARTED", 0, 0, null, List.of(), List.of())));
    }

    private static RaceSnapshot snapshot(double leaderDistance) {
        return new RaceSnapshot("race-1", "STARTED", 1_000, 1, null, List.of(
            new RaceSnapshot.Team(1, 1, 4, 900, leaderDistance, null),
            new RaceSnapshot.Team(2, 2, 4, 700, leaderDistance - 10, null)), List.of());
    }
}
