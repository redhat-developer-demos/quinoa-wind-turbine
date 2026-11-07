package org.raceanalyst.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The race snapshot the caller sends in the analysis request, a deliberate mirror of the
 * game's race://current JSON. Defined here so this app never imports the game: unknown
 * fields are ignored, so the game can grow its snapshot without breaking the analyst.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RaceSnapshot(String raceId, String status, long elapsedMs, long updatedAt, Integer winner,
                           List<Team> teams, List<Player> players) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Team(int id, int position, int players, long power, double distance, Long finishTimeMs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Player(String name, int team, long generated) {
    }

    public Team team(int id) {
        return teams == null ? null : teams.stream().filter(t -> t.id() == id).findFirst().orElse(null);
    }

    public List<Player> teamPlayers(int id) {
        return players == null ? List.of() : players.stream().filter(p -> p.team() == id).toList();
    }
}
