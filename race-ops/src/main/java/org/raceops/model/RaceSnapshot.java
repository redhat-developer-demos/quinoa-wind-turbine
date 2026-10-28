package org.raceops.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The race snapshot published by the game as the race://current MCP resource. A deliberate
 * mirror of the game's JSON, defined here so race-ops never imports game classes: the game
 * is known only through its MCP server. Unknown fields are ignored, so the game can grow
 * its snapshot without breaking this app.
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

    /**
     * The team ahead on distance; ties go to the better position, then the lower id, so the
     * leader is stable when the field is level.
     */
    public Team leader() {
        if (teams == null || teams.isEmpty()) {
            return null;
        }
        Team best = null;
        for (Team t : teams) {
            if (best == null
                || t.distance() > best.distance()
                || (t.distance() == best.distance() && (t.position() < best.position()
                    || t.position() == best.position() && t.id() < best.id()))) {
                best = t;
            }
        }
        return best;
    }
}
