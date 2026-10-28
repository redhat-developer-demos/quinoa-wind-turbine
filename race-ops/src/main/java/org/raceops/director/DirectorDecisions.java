package org.raceops.director;

import org.raceops.analytics.EngagementReport;
import org.raceops.mcp.AdminRaceClient;
import org.raceops.model.RaceSnapshot;

import java.util.List;

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
        return Proposal.boost(snapshot.raceId(), situation.team(), 2.5, 30,
            "Team %d is %.1f%% behind because %s. Boost them for 30 s to keep the race close?"
                .formatted(situation.team(), situation.deficit(), why));
    }

    /**
     * Clamps the model's advice to the situation: only the trailing team, a sane
     * multiplier, a sane duration, a non-empty message.
     */
    public static Proposal advice(DirectorAi.BoostAdvice advice, BoostPolicy.Situation situation,
                                    RaceSnapshot snapshot) {
        int team = situation.team(); // the model does not get to pick the winner
        double multiplier = Math.clamp(advice.multiplier(), 1.5, 3.0);
        int seconds = (int) Math.clamp(advice.seconds(), 10, 60);
        String message = advice.message() == null || advice.message().isBlank()
            ? "Team " + team + " is behind. Boost them to keep the race close?"
            : advice.message().trim();
        return Proposal.boost(snapshot.raceId(), team, multiplier, seconds, message);
    }

    /**
     * The facts for the between-heats theme suggestion: what just happened, the presets
     * the game actually offers, and the theme currently on.
     */
    public static String themeFacts(RaceSnapshot snapshot, EngagementReport engagement,
                                    List<AdminRaceClient.ThemeOption> options, String currentTheme) {
        String winner = snapshot.winner() != null ? "team " + snapshot.winner() : "nobody";
        String result = engagement.teams() == null ? "" : engagement.teams().stream()
            .map(t -> "team %d: %.1f%%".formatted(t.team(), t.distance()))
            .reduce((a, b) -> a + ", " + b).orElse("");
        String presets = options.stream()
            .map(o -> "{\"id\": \"%s\", \"name\": \"%s\"}".formatted(o.id(), o.label()))
            .reduce((a, b) -> a + ", " + b).orElse("");
        return """
            {"raceId": "%s", "winner": "%s", "result": "%s", "elapsedMs": %d, "currentTheme": "%s", "presets": [%s]}""".formatted(
            snapshot.raceId(), winner, result, snapshot.elapsedMs(), currentTheme, presets);
    }

    /**
     * Clamps the model's theme answer to the real preset list; an id the game does not
     * offer, or the theme that is already on, falls back to the deterministic pick.
     */
    public static Proposal theme(DirectorAi.ThemeAdvice advice, List<AdminRaceClient.ThemeOption> options,
                                 String raceId, String currentTheme, Proposal fallback) {
        boolean known = advice != null && advice.presetId() != null && options.stream()
            .anyMatch(o -> o.id().equals(advice.presetId()));
        if (!known || advice.presetId().equals(currentTheme)) {
            return fallback;
        }
        String message = advice.message() == null || advice.message().isBlank()
            ? fallback.message() : advice.message().trim();
        return Proposal.theme(raceId, advice.presetId(), message);
    }

    /**
     * The deterministic between-heats pick: rotate through the presets so consecutive
     * heats look different, skipping the default and the theme already on.
     */
    public static Proposal themeFallback(List<AdminRaceClient.ThemeOption> options, String raceId, int heat,
                                         String currentTheme) {
        List<AdminRaceClient.ThemeOption> usable = options.stream()
            .filter(o -> !"classic".equals(o.id()) && !o.id().equals(currentTheme))
            .toList();
        if (usable.isEmpty()) {
            usable = options;
        }
        AdminRaceClient.ThemeOption pick = usable.get(Math.floorMod(heat, usable.size()));
        return Proposal.theme(raceId, pick.id(),
            "Next heat: %s? Fresh colors and cars for the teams.".formatted(pick.label()));
    }
}
