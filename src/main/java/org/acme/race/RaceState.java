package org.acme.race;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Optional;
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
    public record PlayerState(String name, int team, long generated, java.util.Set<String> modes) {

        public PlayerState {
            modes = modes != null ? java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(modes)) : java.util.Set.of();
        }

        public PlayerState(String name, int team, long generated) {
            this(name, team, generated, java.util.Set.of());
        }

        PlayerState add(long quantity, java.util.Collection<String> newModes) {
            java.util.Set<String> merged = new java.util.TreeSet<>(modes != null ? modes : java.util.Set.of());
            if (newModes != null) {
                merged.addAll(newModes);
            }
            return new PlayerState(name, team, generated + quantity, merged);
        }

        PlayerState add(long quantity) {
            return add(quantity, java.util.Set.of());
        }
    }

    @RegisterForReflection
    public record LeaderboardEntry(int rank, String name, int team, long generated, java.util.Set<String> modes) {
        public LeaderboardEntry {
            modes = modes != null ? java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(modes)) : java.util.Set.of();
        }

        public LeaderboardEntry(int rank, String name, int team, long generated) {
            this(rank, name, team, generated, java.util.Set.of());
        }
    }

    /**
     * Where one player stands: their team's position among the teams, and their rank inside the team.
     */
    @RegisterForReflection
    public record PlayerStanding(String raceId, Status status, int teamPosition, int teams, int rankInTeam,
                                 int teamPlayers, long generated) {
    }

    public static RaceState waiting(String raceId) {
        return new RaceState(raceId, Status.WAITING, 0, 0, null, List.of(), List.of());
    }

    public List<LeaderboardEntry> leaderboard(Integer team) {
        List<PlayerState> ranked = players.stream()
            .filter(p -> team == null || p.team() == team)
            .toList();
        return IntStream.range(0, ranked.size())
            .mapToObj(i -> {
                PlayerState p = ranked.get(i);
                return new LeaderboardEntry(i + 1, p.name(), p.team(), p.generated(), p.modes());
            })
            .toList();
    }

    /**
     * @return the player's standing, or empty if the player hasn't registered in this race yet
     */
    public Optional<PlayerStanding> standing(String name) {
        Optional<PlayerState> player = players.stream().filter(p -> p.name().equals(name)).findFirst();
        Optional<TeamState> team = player.flatMap(p -> teams.stream().filter(t -> t.id() == p.team()).findFirst());
        if (player.isEmpty() || team.isEmpty()) {
            return Optional.empty();
        }
        // Players are sorted by generated power, so the rank is one more than the teammates listed before
        int rankInTeam = 1 + (int) players.stream()
            .takeWhile(p -> !p.name().equals(name))
            .filter(p -> p.team() == player.get().team())
            .count();
        return Optional.of(new PlayerStanding(raceId, status, team.get().position(), teams.size(), rankInTeam,
            team.get().players(), player.get().generated()));
    }
}
