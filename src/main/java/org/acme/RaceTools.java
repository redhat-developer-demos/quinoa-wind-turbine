package org.acme;

import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Resource;
import io.quarkiverse.mcp.server.ResourceManager;
import io.quarkiverse.mcp.server.TextResourceContents;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.Json;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.acme.GameResource.GameEvent;

import java.util.List;

/**
 * Race tools for MCP clients. The default server at /mcp only reads the race and needs no login. The admin server at
 * /admin/mcp has the same read tools plus race control, and its path needs a bearer token with the admin role.
 */
@Singleton
@Startup
public class RaceTools {

    static final String ADMIN_SERVER = "admin";
    static final String RACE_URI = "race://current";

    private final RaceResource race;
    private final GameResource game;
    private final ResourceManager resources;

    public RaceTools(RaceResource race, GameResource game, ResourceManager resources) {
        this.race = race;
        this.game = game;
        this.resources = resources;
    }

    @PostConstruct
    void notifySubscribers() {
        race.updates().subscribe().with(state -> {
            ResourceManager.ResourceInfo resource = resources.getResource(RACE_URI);
            if (resource != null) {
                resource.sendUpdateAndForget();
            }
        });
    }

    @McpServer(McpServer.DEFAULT)
    @McpServer(ADMIN_SERVER)
    @Tool(name = "get_race_state", description = "Returns the current race: its status, race time, the teams in race "
        + "order with their power and distance in percent of the track, and the players sorted by generated power.")
    RaceState getRaceState() {
        return race.state();
    }

    @McpServer(McpServer.DEFAULT)
    @McpServer(ADMIN_SERVER)
    @Tool(name = "get_leaderboard", description = "Returns the players ranked by generated power, optionally for one "
        + "team.")
    List<RaceState.LeaderboardEntry> getLeaderboard(
        @ToolArg(description = "Team number, starting at 1. Leave it out for all teams.", required = false)
        Integer team) {
        return race.leaderboard(team);
    }

    @McpServer(McpServer.DEFAULT)
    @McpServer(ADMIN_SERVER)
    @Tool(name = "get_player", description = "Returns one player's team, generated power and overall rank.")
    RaceState.LeaderboardEntry getPlayer(@ToolArg(description = "The player's name") String name) {
        return player(race.state(), name);
    }

    static RaceState.LeaderboardEntry player(RaceState state, String name) {
        return state.leaderboard(null).stream()
            .filter(p -> p.name().equalsIgnoreCase(name))
            .findFirst()
            .orElseThrow(() -> new ToolCallException("No player named " + name + " in the current race"));
    }

    @McpServer(McpServer.DEFAULT)
    @McpServer(ADMIN_SERVER)
    @Resource(uri = RACE_URI, name = "current_race", mimeType = "application/json",
        description = "The current race as JSON. Subscribe to get a notification for every race update.")
    TextResourceContents currentRace() {
        return TextResourceContents.create(RACE_URI, Json.encode(race.state()));
    }

    @McpServer(ADMIN_SERVER)
    @Tool(name = "start_race", description = "Starts the race, or resumes it after a pause.")
    Uni<String> startRace() {
        return send(GameEvent.START, "Race started");
    }

    @McpServer(ADMIN_SERVER)
    @Tool(name = "pause_race", description = "Pauses the running race.")
    Uni<String> pauseRace() {
        return send(GameEvent.PAUSE, "Race paused");
    }

    @McpServer(ADMIN_SERVER)
    @Tool(name = "reset_race", description = "Resets the race. Players start over at zero in a new race.")
    Uni<String> resetRace() {
        return send(GameEvent.RESET, "Race reset");
    }

    private Uni<String> send(String type, String done) {
        return game.sendGameEvent(new GameEvent(type)).replaceWith(done);
    }
}
