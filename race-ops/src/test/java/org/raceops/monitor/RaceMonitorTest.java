package org.raceops.monitor;

import org.junit.jupiter.api.Test;
import org.raceops.model.RaceSnapshot;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaceMonitorTest {

    private static RaceSnapshot snapshot(String raceId, String status, long updatedAt) {
        return new RaceSnapshot(raceId, status, 0, updatedAt, null,
            List.of(new RaceSnapshot.Team(1, 1, 0, 0, 0, null), new RaceSnapshot.Team(2, 2, 0, 0, 0, null)),
            List.of());
    }

    @Test
    void aDifferentRaceIdIsANewRace() {
        assertTrue(RaceMonitor.isNewRace(snapshot("race-1", "FINISHED", 100), snapshot("race-2", "WAITING", 0)));
    }

    @Test
    void waitingAfterStartedIsANewRaceEvenWithTheSameIdAndAnOlderTimestamp() {
        // A restarted game resets the race-id counter, so race-0 comes back with
        // updatedAt 0 while the previously seen race-0 finished at 100.
        assertTrue(RaceMonitor.isNewRace(snapshot("race-0", "FINISHED", 100), snapshot("race-0", "WAITING", 0)));
    }

    @Test
    void waitingToWaitingIsTheSameRace() {
        assertFalse(RaceMonitor.isNewRace(snapshot("race-0", "WAITING", 10), snapshot("race-0", "WAITING", 20)));
    }

    @Test
    void startedProgressionIsTheSameRace() {
        assertFalse(RaceMonitor.isNewRace(snapshot("race-0", "STARTED", 10), snapshot("race-0", "STARTED", 20)));
        assertFalse(RaceMonitor.isNewRace(snapshot("race-0", "STARTED", 10), snapshot("race-0", "PAUSED", 20)));
        assertFalse(RaceMonitor.isNewRace(snapshot("race-0", "STARTED", 10), snapshot("race-0", "FINISHED", 20)));
    }

    @Test
    void aResetToWaitingMidRaceIsANewRace() {
        assertTrue(RaceMonitor.isNewRace(snapshot("race-0", "STARTED", 10), snapshot("race-0", "WAITING", 20)));
    }
}
