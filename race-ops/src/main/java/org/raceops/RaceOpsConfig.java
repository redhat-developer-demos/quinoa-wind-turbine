package org.raceops;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

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
     * Whether the director consults the A2A specialists (race-analyst, race-creative) and
     * uses the supervisor's model to phrase the advice. Off by default so the suite and a
     * bare start need no other app; on in dev and on the cluster, where the mesh runs.
     */
    @WithDefault("false")
    boolean meshEnabled();

    /**
     * The race-analyst A2A endpoint. Dev and the cluster differ only by URL.
     */
    Analyst analyst();

    /**
     * The race-creative A2A endpoint.
     */
    Creative creative();

    interface Analyst {
        @WithDefault("http://localhost:8082/")
        String a2aUrl();
    }

    interface Creative {
        @WithDefault("http://localhost:8083/")
        String a2aUrl();
    }

    /**
     * The A2A mesh's TLS. Empty without the mtls profile: the A2A clients then use the JVM's
     * own TLS and present no SVID.
     */
    MeshTls meshTls();

    interface MeshTls {

        /**
         * The directory the SPIFFE sidecar keeps the X.509 SVID files in ({@code svid.pem},
         * {@code svid.key}, {@code bundle.pem}). Presented as the client certificate of the
         * A2A calls, so the specialists accept only this app's SPIFFE ID.
         */
        Optional<String> directory();
    }

    /**
     * What the creative director is proposing for, passed to race-creative with the theme
     * request. Concrete, because the agent turns it into a brief for the game's generator.
     */
    @WithDefault("a developer conference")
    String eventName();

    /**
     * The crowd the creative director writes for, passed with the theme request.
     */
    @WithDefault("a room of developers")
    String audience();

    /**
     * How far behind, in percent of the track, a team must be before the director
     * considers proposing a boost. Only counts between a quarter and three quarters of
     * the race, so nobody is boosted during the opening noise or the final sprint.
     */
    @WithDefault("15")
    int boostDeficitPercent();
}
