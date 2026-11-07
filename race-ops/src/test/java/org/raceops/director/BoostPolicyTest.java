package org.raceops.director;

import org.junit.jupiter.api.Test;
import org.raceops.analytics.EngagementReport;
import org.raceops.model.RaceSnapshot;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoostPolicyTest {

    private static RaceSnapshot.Team team(int id, int position, double distance) {
        return new RaceSnapshot.Team(id, position, 5, 0, distance, null);
    }

    private static RaceSnapshot started(RaceSnapshot.Team... teams) {
        return new RaceSnapshot("race-1", "STARTED", 10_000, System.currentTimeMillis(), null,
            List.of(teams), List.of());
    }

    private static EngagementReport engagement(int stalledTeam1) {
        return new EngagementReport("race-1", "STARTED", 10_000, List.of(
            new EngagementReport.TeamEngagement(1, 2, 5, 5 - stalledTeam1, stalledTeam1, (5 - stalledTeam1) / 5.0, 0, 30),
            new EngagementReport.TeamEngagement(2, 1, 5, 5, 0, 1.0, 0, 50)), "teams", "in-process");
    }

    @Test
    void bigDeficitMidRaceTriggers() {
        Optional<BoostPolicy.Situation> situation = BoostPolicy.evaluate(
            started(team(1, 2, 30), team(2, 1, 50)), engagement(2), 15);
        assertTrue(situation.isPresent());
        assertEquals(1, situation.get().team());
        assertEquals(20.0, situation.get().deficit(), 1e-9);
        assertEquals(0.4, situation.get().stalledShare(), 1e-9);
    }

    @Test
    void smallDeficitDoesNotTrigger() {
        assertTrue(BoostPolicy.evaluate(started(team(1, 2, 45), team(2, 1, 50)), engagement(0), 15).isEmpty());
    }

    @Test
    void openingNoiseDoesNotTrigger() {
        assertTrue(BoostPolicy.evaluate(started(team(1, 2, 5), team(2, 1, 25)), engagement(3), 15).isEmpty());
    }

    @Test
    void finalSprintDoesNotTrigger() {
        assertTrue(BoostPolicy.evaluate(started(team(1, 2, 60), team(2, 1, 80)), engagement(3), 15).isEmpty());
    }

    @Test
    void notRunningDoesNotTrigger() {
        RaceSnapshot waiting = new RaceSnapshot("race-1", "WAITING", 0, System.currentTimeMillis(), null,
            List.of(team(1, 2, 0), team(2, 1, 0)), List.of());
        assertTrue(BoostPolicy.evaluate(waiting, engagement(3), 15).isEmpty());
    }

    @Test
    void sanitizedAdviceCannotBoostTheLeaderOrRunForever() {
        RaceSnapshot snapshot = started(team(1, 2, 30), team(2, 1, 50));
        BoostPolicy.Situation situation = new BoostPolicy.Situation(1, 20, 0.4);
        // The model tries to boost the leading team with an absurd multiplier and duration.
        DirectorDecisions.BoostAdvice rogue = new DirectorDecisions.BoostAdvice(true, 2, 99, 3600, "  ");
        Proposal proposal = DirectorDecisions.advice(rogue, situation, snapshot);
        assertEquals(1, proposal.team());
        assertEquals(3.0, proposal.multiplier());
        assertEquals(60, proposal.seconds());
        assertTrue(proposal.message().contains("Team 1"));
    }

    @Test
    void fallbackDiagnosesQuitters() {
        RaceSnapshot snapshot = started(team(1, 2, 30), team(2, 1, 50));
        Proposal proposal = DirectorDecisions.fallbackProposal(new BoostPolicy.Situation(1, 20, 0.5), snapshot);
        assertTrue(proposal.message().contains("50% of its players stopped tapping"), proposal.message());
        assertEquals(2.5, proposal.multiplier());
        assertEquals(30, proposal.seconds());
    }
}
