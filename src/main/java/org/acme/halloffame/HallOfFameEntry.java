package org.acme.halloffame;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.function.IntFunction;
import org.acme.race.RaceState;

/**
 * One finished race, kept in the hall of fame. It is built from the finished {@link RaceState} snapshot, which carries
 * the race time and the per-team result. Team names come from the active theme (the snapshot has only team IDs), so the
 * entry records the names that were on screen during that race.
 *
 * <p>The entry records the race result, not individual achievement across races. Player names are generated per id and
 * don't follow a person between sessions, so the top players are kept as the names from that race, for flavor.
 */
@RegisterForReflection
public record HallOfFameEntry(String raceId, long finishedAt, long elapsedMs, Integer winner, String winnerName,
                             List<TeamResult> teams, List<PlayerResult> topPlayers) {

    @RegisterForReflection
    public record TeamResult(int id, String name, int position, int players, long power, double distance,
                             Long finishTimeMs) {
    }

    @RegisterForReflection
    public record PlayerResult(String name, int team, long generated) {
    }

    /**
     * @param finishedAt  wall-clock time the race was captured, so the view can show when it happened
     * @param topPlayers  how many of the race's top generators to keep
     */
    static HallOfFameEntry from(RaceState race, IntFunction<String> teamName, long finishedAt, int topPlayers) {
        List<TeamResult> teams = race.teams().stream()
            .map(team -> new TeamResult(team.id(), teamName.apply(team.id()), team.position(), team.players(),
                team.power(), team.distance(), team.finishTimeMs()))
            .toList();
        List<PlayerResult> players = race.players().stream()
            .limit(Math.max(0, topPlayers))
            .map(player -> new PlayerResult(player.name(), player.team(), player.generated()))
            .toList();
        String winnerName = race.winner() == null ? null : teamName.apply(race.winner());
        return new HallOfFameEntry(race.raceId(), finishedAt, race.elapsedMs(), race.winner(), winnerName,
            teams, players);
    }
}
