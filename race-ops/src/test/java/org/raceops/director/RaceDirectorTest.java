package org.raceops.director;

import org.junit.jupiter.api.Test;
import org.raceops.model.RaceSnapshot;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaceDirectorTest {

    private static RaceSnapshot snapshot(String raceId, String status) {
        return new RaceSnapshot(raceId, status, 0, System.currentTimeMillis(), null,
            List.of(new RaceSnapshot.Team(1, 1, 0, 0, 0, null), new RaceSnapshot.Team(2, 2, 0, 0, 0, null)),
            List.of());
    }

    @Test
    void aStartedRaceBackInWaitingMeansTheGameRestarted() {
        // Race ids come from a counter that resets with a fresh game instance, so the same
        // id can come back; a started race never returns to WAITING within one instance.
        assertTrue(RaceDirector.gameRestarted(snapshot("race-0", "WAITING"), "race-0"));
    }

    @Test
    void aDifferentRaceOrAStartedSnapshotIsNotARestart() {
        assertFalse(RaceDirector.gameRestarted(snapshot("race-1", "WAITING"), "race-0"));
        assertFalse(RaceDirector.gameRestarted(snapshot("race-0", "STARTED"), "race-0"));
        assertFalse(RaceDirector.gameRestarted(snapshot("race-0", "WAITING"), null));
    }
}
