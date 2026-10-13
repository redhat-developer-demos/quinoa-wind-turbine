package org.acme;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Snapshot of the current race, computed by {@link RaceTopology} and published on the race-progress topic.
 * Teams are sorted by position, players by generated power.
 */
@RegisterForReflection
public record RaceState(String raceId, Status status, long elapsedMs, long updatedAt, Integer winner,
                        List<TeamState> teams, List<PlayerState> players) {

    public enum Status { WAITING, STARTED, PAUSED, FINISHED }

    @RegisterForReflection
    public record TeamState(int id, int position, int players, long power, double distance, Long finishTimeMs) {
    }

    @RegisterForReflection
    public record PlayerState(String name, int team, long generated) {

        PlayerState add(long quantity) {
            return new PlayerState(name, team, generated + quantity);
        }
    }

    @RegisterForReflection
    public record LeaderboardEntry(int rank, String name, int team, long generated) {
    }

    static RaceState waiting(String raceId) {
        return new RaceState(raceId, Status.WAITING, 0, 0, null, List.of(), List.of());
    }

    List<LeaderboardEntry> leaderboard(Integer team) {
        List<PlayerState> ranked = players.stream()
            .filter(p -> team == null || p.team() == team)
            .toList();
        return IntStream.range(0, ranked.size())
            .mapToObj(i -> {
                PlayerState p = ranked.get(i);
                return new LeaderboardEntry(i + 1, p.name(), p.team(), p.generated());
            })
            .toList();
    }
}
