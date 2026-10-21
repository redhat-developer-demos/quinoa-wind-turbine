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
    private final GameConfig config;
    private final HallOfFameResource hallOfFame;
    private final ThemeService themes;

    public RaceTools(RaceResource race, GameResource game, ResourceManager resources, GameConfig config,
                     HallOfFameResource hallOfFame, ThemeService themes) {
        this.race = race;
        this.game = game;
        this.resources = resources;
        this.config = config;
        this.hallOfFame = hallOfFame;
        this.themes = themes;
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
    @Tool(name = "get_hall_of_fame", description = "Returns past finished races, most recent first: the winning team, "
        + "the race time, the final team standings and the top generators of each race.")
    List<HallOfFameEntry> getHallOfFame() {
        return hallOfFame.all();
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

    @McpServer(ADMIN_SERVER)
    @Tool(name = "boost_team", description = "Secretly boosts or sabotages a team by multiplying its power for a while. "
        + "Nothing shows in the app; the team just moves faster or slower. Use a multiplier above 1 to boost, below 1 "
        + "to sabotage. The trace is only on the race-modifiers topic.")
    Uni<String> boostTeam(
        @ToolArg(description = "Team number, starting at 1") int team,
        @ToolArg(description = "Power multiplier: above 1 boosts, below 1 sabotages. Defaults to the configured boost.",
            required = false) Double multiplier,
        @ToolArg(description = "How many seconds the effect lasts. Defaults to the configured duration.",
            required = false) Integer seconds) {
        double factor = multiplier != null ? multiplier : config.modifiers().boostMultiplier();
        long durationMs = seconds != null ? seconds * 1000L : config.modifiers().duration().toMillis();
        return game.sendModifier(team, factor, durationMs)
            .replaceWith("Applied x" + factor + " to team " + team + " for " + (durationMs / 1000) + "s");
    }

    @McpServer(ADMIN_SERVER)
    @Tool(name = "list_preset_themes", description = "Lists the built-in race themes that can be applied, each with "
        + "its id, name and the two teams' names, colors and cars.")
    List<RaceTheme> listPresetThemes() {
        return themes.presets();
    }

    @McpServer(ADMIN_SERVER)
    @Tool(name = "apply_theme", description = "Restyles the race between races: sets each team's name, color and car. "
        + "Apply a built-in preset by id, or pass a full theme. Changes every dashboard and phone at once.")
    String applyTheme(
        @ToolArg(description = "Id of a preset theme from list_preset_themes. Leave out to pass a full theme instead.",
            required = false) String presetId,
        @ToolArg(description = "A full theme to apply, instead of a preset id.", required = false) RaceTheme theme) {
        RaceTheme toApply = theme;
        if (presetId != null) {
            toApply = themes.presets().stream()
                .filter(t -> t.id().equals(presetId))
                .findFirst()
                .orElseThrow(() -> new ToolCallException("No preset theme " + presetId));
        }
        if (toApply == null) {
            throw new ToolCallException("Pass a preset id or a full theme");
        }
        List<String> errors = themes.apply(toApply);
        return errors.isEmpty() ? "Applied theme" : "Theme rejected: " + String.join("; ", errors);
    }

    private Uni<String> send(String type, String done) {
        return game.sendGameEvent(new GameEvent(type)).replaceWith(done);
    }
}
