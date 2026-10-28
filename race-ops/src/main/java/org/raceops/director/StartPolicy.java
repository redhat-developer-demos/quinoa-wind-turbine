package org.raceops.director;

import org.raceops.model.RaceSnapshot;

import java.util.List;

/**
 * When the director may start the race: both teams at or above the minimum, and the field
 * unchanged for the plateau. The wall-clock backstop for a huge room belongs to the
 * run-of-show, not to this rule.
 *
 * Headcount balance is deliberately absent: team distance normalizes by the number of
 * players who tapped, so a lopsided split is mathematically fair (see the fairness math in
 * the plan). What matters is that enough players joined and that they stopped arriving.
 */
public final class StartPolicy {

    private StartPolicy() {
    }

    public record Decision(boolean start, String reason) {

        static Decision wait(String reason) {
            return new Decision(false, reason);
        }

        static Decision go(String reason) {
            return new Decision(true, reason);
        }
    }

    /**
     * @param window snapshots of one race in arrival order, oldest first
     * @param now the current time, epoch milliseconds, because the plateau is measured
     *            against the clock: snapshots stop arriving once joins stop
     */
    public static Decision shouldStart(List<RaceSnapshot> window, int minPlayersPerTeam, int plateauSeconds, long now) {
        if (window == null || window.isEmpty()) {
            return Decision.wait("no snapshots yet");
        }
        RaceSnapshot latest = window.get(window.size() - 1);
        if (latest.teams() == null || latest.teams().isEmpty()) {
            // A fresh game's first WAITING snapshot carries no teams yet; an empty loop
            // below would pass the minimum check and start a playerless race.
            return Decision.wait("no teams in the race state yet");
        }
        for (RaceSnapshot.Team team : latest.teams()) {
            int players = Math.max(team.players(), latest.teamPlayers(team.id()).size());
            if (players < minPlayersPerTeam) {
                return Decision.wait("team " + team.id() + " has " + players + " players, needs " + minPlayersPerTeam);
            }
        }
        int total = totalPlayers(latest);
        long quietFor = now - latest.updatedAt();
        if (quietFor < plateauSeconds * 1000L) {
            return Decision.wait("field of " + total + " quiet for " + quietFor / 1000 + "s, needs " + plateauSeconds + "s");
        }
        return Decision.go("field of " + total + " stable for " + plateauSeconds + "s");
    }

    private static int totalPlayers(RaceSnapshot snapshot) {
        int fromTeams = snapshot.teams() == null ? 0
            : snapshot.teams().stream().mapToInt(RaceSnapshot.Team::players).sum();
        return Math.max(fromTeams, snapshot.players() == null ? 0 : snapshot.players().size());
    }
}
