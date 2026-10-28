package org.raceops.watch;

import org.junit.jupiter.api.Test;
import org.raceops.model.RaceSnapshot;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaceEventFilterTest {

    private static RaceSnapshot.Team team(int id, int position, double distance) {
        return new RaceSnapshot.Team(id, position, 0, 0, distance, null);
    }

    private static RaceSnapshot snapshot(String raceId, String status, long updatedAt, Integer winner,
                                         RaceSnapshot.Team... teams) {
        return new RaceSnapshot(raceId, status, 0, updatedAt, winner, List.of(teams), List.of());
    }

    @Test
    void firstSeenRunningRaceIsStarted() {
        RaceSnapshot curr = snapshot("race-1", "STARTED", 1000, null, team(1, 1, 12), team(2, 2, 8));
        List<RaceEvent> events = RaceEventFilter.events(null, curr);
        assertEquals(1, events.size());
        assertEquals(RaceEvent.STARTED, events.get(0).type());
    }

    @Test
    void waitingToStartedIsStarted() {
        RaceSnapshot prev = snapshot("race-1", "WAITING", 900, null);
        RaceSnapshot curr = snapshot("race-1", "STARTED", 1000, null);
        assertEquals(RaceEvent.STARTED, RaceEventFilter.events(prev, curr).get(0).type());
    }

    @Test
    void pausedThenStartedIsResumed() {
        RaceSnapshot prev = snapshot("race-1", "PAUSED", 900, null);
        RaceSnapshot curr = snapshot("race-1", "STARTED", 1000, null);
        assertEquals(RaceEvent.RESUMED, RaceEventFilter.events(prev, curr).get(0).type());
    }

    @Test
    void startedToPausedIsPaused() {
        RaceSnapshot prev = snapshot("race-1", "STARTED", 900, null);
        RaceSnapshot curr = snapshot("race-1", "PAUSED", 1000, null);
        assertEquals(RaceEvent.PAUSED, RaceEventFilter.events(prev, curr).get(0).type());
    }

    @Test
    void finishedCarriesWinnerAndTime() {
        RaceSnapshot prev = snapshot("race-1", "STARTED", 900, null, team(1, 1, 100), team(2, 2, 90));
        RaceSnapshot curr = new RaceSnapshot("race-1", "FINISHED", 0, 1000, 2,
            List.of(team(1, 2, 100), new RaceSnapshot.Team(2, 1, 0, 0, 100, 35_238L)), List.of());
        RaceEvent event = RaceEventFilter.events(prev, curr).get(0);
        assertEquals(RaceEvent.FINISHED, event.type());
        assertEquals(2, event.team());
        assertTrue(event.detail().contains("35.2 s"), event.detail());
    }

    @Test
    void milestoneFiresOnceOnCrossing() {
        RaceSnapshot prev = snapshot("race-1", "STARTED", 900, null, team(1, 1, 24.8), team(2, 2, 25.1));
        RaceSnapshot curr = snapshot("race-1", "STARTED", 1000, null, team(1, 1, 25.3), team(2, 2, 25.4));
        List<RaceEvent> events = RaceEventFilter.events(prev, curr);
        assertEquals(1, events.size());
        assertEquals(RaceEvent.MILESTONE, events.get(0).type());
        assertEquals(1, events.get(0).team());
        assertTrue(events.get(0).detail().contains("25%"));
    }

    @Test
    void leadChangeOnlyPastTheNoiseFloor() {
        RaceSnapshot.Team aheadEarly = team(2, 1, 3);
        RaceSnapshot.Team behindEarly = team(1, 2, 1);
        RaceSnapshot prev = snapshot("race-1", "STARTED", 900, null, behindEarly, aheadEarly);
        // A flip at 4% is noise: no event.
        RaceSnapshot early = snapshot("race-1", "STARTED", 950, null, team(1, 1, 4), team(2, 2, 3));
        assertTrue(RaceEventFilter.events(prev, early).isEmpty());
        // The same flip at 15% is a real lead change.
        RaceSnapshot late = snapshot("race-1", "STARTED", 1000, null, team(1, 1, 15), team(2, 2, 12));
        List<RaceEvent> events = RaceEventFilter.events(prev, late);
        assertEquals(1, events.size());
        assertEquals(RaceEvent.LEAD_CHANGE, events.get(0).type());
        assertEquals(1, events.get(0).team());
    }

    @Test
    void routineProgressEmitsNothing() {
        RaceSnapshot prev = snapshot("race-1", "STARTED", 900, null, team(1, 1, 40.1), team(2, 2, 38.7));
        RaceSnapshot curr = snapshot("race-1", "STARTED", 1000, null, team(1, 1, 41.0), team(2, 2, 39.5));
        assertTrue(RaceEventFilter.events(prev, curr).isEmpty());
    }

    @Test
    void newRaceIdStartsOver() {
        RaceSnapshot prev = snapshot("race-1", "FINISHED", 900, 1, team(1, 1, 100));
        RaceSnapshot curr = snapshot("race-2", "STARTED", 1000, null, team(1, 1, 5), team(2, 2, 5));
        List<RaceEvent> events = RaceEventFilter.events(prev, curr);
        assertEquals(1, events.size());
        assertEquals(RaceEvent.STARTED, events.get(0).type());
        assertEquals("race-2", events.get(0).raceId());
    }
}
