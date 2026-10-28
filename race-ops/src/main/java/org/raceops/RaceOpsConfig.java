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
}
