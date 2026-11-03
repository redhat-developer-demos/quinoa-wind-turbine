package org.raceops;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * race-ops settings. The MCP client itself is configured by the quarkus-langchain4j-mcp
 * properties (quarkus.langchain4j.mcp.race.*); this mapping covers the watcher behavior.
 */
@ConfigMapping(prefix = "race-ops")
public interface RaceOpsConfig {

    /**
     * The MCP resource carrying the current race snapshot.
     */
    @WithDefault("race://current")
    String resourceUri();

    /**
     * Off in tests, so the suite never needs a running game.
     */
    @WithDefault("true")
    boolean watcherEnabled();

    /**
     * How far back the engagement analytics compare, in seconds. A player whose power did not
     * grow across this window counts as stalled; adjacent snapshots alone would be too noisy,
     * because a human tapping three times a second updates only every few snapshots.
     */
    @WithDefault("5")
    int analyticsWindowSeconds();

    /**
     * The director acts on the game through the admin MCP tools. Off by default: nothing
     * starts or restyles the race unless this is on.
     */
    @WithDefault("false")
    boolean directorEnabled();

    /**
     * Both teams need at least this many registered players before the director may
     * start the race.
     */
    @WithDefault("2")
    int minPlayersPerTeam();

    /**
     * The join plateau: the race starts when the field has been unchanged for this many
     * seconds. Short on purpose, because on a live stage the room waits through it. The
     * wall-clock backstop for a huge room comes from the run-of-show, not from this app.
     */
    @WithDefault("5")
    int plateauSeconds();

    /**
     * The director's advice model. Off by default and in every test; when off (or when
     * the call fails), proposals fall back to the deterministic template.
     */
    @WithDefault("false")
    boolean aiEnabled();

    /**
     * How far behind, in percent of the track, a team must be before the director
     * considers proposing a boost. Only counts between a quarter and three quarters of
     * the race, so nobody is boosted during the opening noise or the final sprint.
     */
    @WithDefault("15")
    int boostDeficitPercent();
}
