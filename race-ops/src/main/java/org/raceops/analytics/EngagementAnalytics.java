package org.raceops.analytics;

import org.raceops.model.RaceSnapshot;

import java.util.List;

/**
 * Computes the engagement report from two snapshots of the same race. Pure code, no model:
 * not everything in an agent application needs an LLM, and this is the number the director
 * reasons over when deciding whether a team is behind because it is small or because it quit.
 */
public final class EngagementAnalytics {

    private EngagementAnalytics() {
    }

    /**
     * @param base a snapshot from before curr (the analytics window), or null when only one
     *             snapshot has been seen; then active and stalled are reported as unknown (0)
     * @param curr the current snapshot
     */
    public static EngagementReport analyze(RaceSnapshot base, RaceSnapshot curr) {
        boolean comparable = base != null && base.raceId() != null && base.raceId().equals(curr.raceId());
        boolean running = "STARTED".equals(curr.status());
        List<EngagementReport.TeamEngagement> teams = curr.teams() == null ? List.of()
            : curr.teams().stream().map(team -> {
                List<RaceSnapshot.Player> players = curr.teamPlayers(team.id());
                int active = 0;
                int stalled = 0;
                if (comparable && running) {
                    for (RaceSnapshot.Player player : players) {
                        RaceSnapshot.Player before = findByName(base, player.name());
                        // A player who switched teams starts over, so they count as active.
                        if (before == null || before.team() != player.team()
                            || before.generated() < player.generated()) {
                            active++;
                        } else if (before.generated() == player.generated()) {
                            stalled++;
                        }
                    }
                }
                int tappers = players.size();
                double rate = tappers == 0 ? 0 : (double) active / tappers;
                return new EngagementReport.TeamEngagement(team.id(), team.position(), tappers,
                    active, stalled, rate, team.power(), team.distance());
            }).toList();
        return new EngagementReport(curr.raceId(), curr.status(), curr.elapsedMs(), teams);
    }

    private static RaceSnapshot.Player findByName(RaceSnapshot snapshot, String name) {
        return snapshot.players() == null ? null
            : snapshot.players().stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
    }
}
