package org.raceops.analytics;

import org.junit.jupiter.api.Test;
import org.raceops.model.RaceSnapshot;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EngagementAnalyticsTest {

    private static RaceSnapshot.Team team(int id, int position, int players, long power, double distance) {
        return new RaceSnapshot.Team(id, position, players, power, distance, null);
    }

    private static RaceSnapshot.Player player(String name, int team, long generated) {
        return new RaceSnapshot.Player(name, team, generated);
    }

    private static RaceSnapshot snapshot(String raceId, String status, List<RaceSnapshot.Team> teams,
                                         List<RaceSnapshot.Player> players) {
        return new RaceSnapshot(raceId, status, 0, 1000, null, teams, players);
    }

    @Test
    void withoutABaseSnapshotEngagementIsUnknown() {
        RaceSnapshot curr = snapshot("race-1", "STARTED",
            List.of(team(1, 1, 3, 300, 40)), List.of(player("a", 1, 100), player("b", 1, 110)));
        EngagementReport report = EngagementAnalytics.analyze(null, curr);
        EngagementReport.TeamEngagement one = report.teams().get(0);
        assertEquals(2, one.tappers());
        assertEquals(0, one.active());
        assertEquals(0, one.stalled());
        assertEquals(0, one.engagementRate());
    }

    @Test
    void activeAndStalledSplitByPowerGrowth() {
        RaceSnapshot base = snapshot("race-1", "STARTED",
            List.of(team(1, 1, 3, 300, 40)),
            List.of(player("tapper", 1, 100), player("quitter", 1, 90), player("newcomer", 1, 5)));
        RaceSnapshot curr = snapshot("race-1", "STARTED",
            List.of(team(1, 1, 3, 350, 45)),
            List.of(player("tapper", 1, 150), player("quitter", 1, 90), player("newcomer", 1, 30)));
        EngagementReport.TeamEngagement one = EngagementAnalytics.analyze(base, curr).teams().get(0);
        assertEquals(3, one.tappers());
        // tapper and newcomer grew, quitter did not
        assertEquals(2, one.active());
        assertEquals(1, one.stalled());
        assertEquals(2.0 / 3, one.engagementRate(), 1e-9);
    }

    @Test
    void teamSwitcherCountsAsActiveInTheNewTeam() {
        RaceSnapshot base = snapshot("race-1", "STARTED",
            List.of(team(1, 1, 1, 100, 30), team(2, 2, 1, 100, 30)),
            List.of(player("mover", 1, 100)));
        RaceSnapshot curr = snapshot("race-1", "STARTED",
            List.of(team(1, 1, 0, 0, 30), team(2, 2, 1, 100, 30)),
            List.of(player("mover", 2, 5)));
        EngagementReport report = EngagementAnalytics.analyze(base, curr);
        assertEquals(0, report.teams().get(0).tappers());
        assertEquals(1, report.teams().get(1).active());
        assertEquals(0, report.teams().get(1).stalled());
    }

    @Test
    void pausedRaceReportsNoActivity() {
        RaceSnapshot base = snapshot("race-1", "STARTED",
            List.of(team(1, 1, 1, 100, 30)), List.of(player("a", 1, 100)));
        RaceSnapshot curr = snapshot("race-1", "PAUSED",
            List.of(team(1, 1, 1, 100, 30)), List.of(player("a", 1, 100)));
        EngagementReport.TeamEngagement one = EngagementAnalytics.analyze(base, curr).teams().get(0);
        assertEquals(1, one.tappers());
        assertEquals(0, one.active());
        assertEquals(0, one.stalled());
    }

    @Test
    void differentRaceIsNotComparable() {
        RaceSnapshot base = snapshot("race-1", "STARTED",
            List.of(team(1, 1, 1, 100, 30)), List.of(player("a", 1, 100)));
        RaceSnapshot curr = snapshot("race-2", "STARTED",
            List.of(team(1, 1, 1, 100, 30)), List.of(player("a", 1, 200)));
        EngagementReport.TeamEngagement one = EngagementAnalytics.analyze(base, curr).teams().get(0);
        assertEquals(0, one.active());
        assertEquals(0, one.stalled());
    }
}
