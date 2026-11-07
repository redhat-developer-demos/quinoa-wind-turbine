package org.raceops.director;

import org.junit.jupiter.api.Test;
import org.raceops.model.RaceSnapshot;
import org.raceops.monitor.RaceSnapshotEvent;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaceDirectorTest {

    private static RaceSnapshot snapshot(String raceId, String status) {
        return new RaceSnapshot(raceId, status, 0, System.currentTimeMillis(), null,
            List.of(new RaceSnapshot.Team(1, 1, 0, 0, 0, null), new RaceSnapshot.Team(2, 2, 0, 0, 0, null)),
            List.of());
    }

    private static RaceSnapshotEvent transition(String previous, String current) {
        return new RaceSnapshotEvent(snapshot("race-0", current),
            List.of(snapshot("race-0", previous), snapshot("race-0", current)));
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

    @Test
    void aFinishedRaceIsProposedForTheNextHeatOnlyOnTheTransition() {
        assertTrue(RaceDirector.justFinished(transition("STARTED", "FINISHED")));
        assertFalse(RaceDirector.justFinished(transition("FINISHED", "FINISHED")),
            "the game keeps serving FINISHED snapshots; only the transition counts");
        assertFalse(RaceDirector.justFinished(transition("WAITING", "STARTED")));
        assertFalse(RaceDirector.justFinished(new RaceSnapshotEvent(snapshot("race-0", "FINISHED"),
            List.of(snapshot("race-0", "FINISHED")))),
            "no window means no previous status to compare against");
    }

    @Test
    void aDecisionIsDroppedOnceTheRaceNoLongerRuns() {
        // The spike's second failure: the two-hop decision took 14 s, the race finished, and
        // the card was still proposed. A card must belong to a heat that is still running.
        assertTrue(RaceDirector.stillRunning(snapshot("race-0", "STARTED"), "race-0"));
        assertFalse(RaceDirector.stillRunning(snapshot("race-0", "FINISHED"), "race-0"));
        assertFalse(RaceDirector.stillRunning(snapshot("race-0", "WAITING"), "race-0"));
        assertFalse(RaceDirector.stillRunning(snapshot("race-1", "STARTED"), "race-0"),
            "a card for the previous heat is stale");
        assertFalse(RaceDirector.stillRunning(null, "race-0"), "no snapshot means nothing to act on");
    }
}
