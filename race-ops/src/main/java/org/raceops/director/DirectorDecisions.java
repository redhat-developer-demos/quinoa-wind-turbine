package org.raceops.director;

import org.raceops.analytics.EngagementReport;
import org.raceops.model.RaceSnapshot;

/**
 * The deterministic parts of the director's decisions: the facts the model (or the
 * fallback) reasons over, and the guardrails applied to whatever comes back. The model
 * never gets the last word — the trailing team is forced, the multiplier and duration
 * clamped — so a bad answer cannot boost the winning team or run for an hour.
 */
public final class DirectorDecisions {

    private DirectorDecisions() {
    }

    /**
     * The facts handed to the model or printed into the fallback message. Plain JSON with
     * rounded numbers, because the model echoes the precision it receives.
     */
    public static String raceFacts(RaceSnapshot snapshot, EngagementReport engagement, BoostPolicy.Situation situation) {
        String teams = engagement.teams() == null ? ""
            : engagement.teams().stream()
                .map(t -> """
                    {"team": %d, "position": %d, "tappers": %d, "active": %d, "stalled": %d, \
                    "engagementRate": %.2f, "power": %d, "distance": %.1f}""".formatted(
                    t.team(), t.position(), t.tappers(), t.active(), t.stalled(),
                    t.engagementRate(), t.power(), t.distance()))
                .reduce((a, b) -> a + ", " + b).orElse("");
        return """
            {"raceId": "%s", "status": "%s", "elapsedMs": %d, "teams": [%s], \
            "trailingTeam": %d, "deficitPercent": %.1f, "trailingStalledShare": %.2f}""".formatted(
            snapshot.raceId(), snapshot.status(), snapshot.elapsedMs(), teams,
            situation.team(), situation.deficit(), situation.stalledShare());
    }

    /**
     * The fallback when the model is off or fails: boost the trailing team with the
     * default parameters and a plain diagnosis.
     */
    public static Proposal fallbackProposal(BoostPolicy.Situation situation, RaceSnapshot snapshot) {
        String why = situation.stalledShare() >= 0.3
            ? "%.0f%% of its players stopped tapping".formatted(situation.stalledShare() * 100)
            : "its players are generating less power";
        return new Proposal(java.util.UUID.randomUUID().toString(), Proposal.BOOST, snapshot.raceId(),
            situation.team(), 2.5, 30,
            "Team %d is %.1f%% behind because %s. Boost them for 30 s to keep the race close?"
                .formatted(situation.team(), situation.deficit(), why),
            System.currentTimeMillis());
    }

    /**
     * Clamps the model's advice to the situation: only the trailing team, a sane
     * multiplier, a sane duration, a non-empty message.
     */
    public static Proposal sanitize(DirectorAi.BoostAdvice advice, BoostPolicy.Situation situation,
                                    RaceSnapshot snapshot) {
        int team = situation.team(); // the model does not get to pick the winner
        double multiplier = Math.clamp(advice.multiplier(), 1.5, 3.0);
        int seconds = (int) Math.clamp(advice.seconds(), 10, 60);
        String message = advice.message() == null || advice.message().isBlank()
            ? "Team " + team + " is behind. Boost them to keep the race close?"
            : advice.message().trim();
        return new Proposal(java.util.UUID.randomUUID().toString(), Proposal.BOOST, snapshot.raceId(),
            team, multiplier, seconds, message, System.currentTimeMillis());
    }
}
