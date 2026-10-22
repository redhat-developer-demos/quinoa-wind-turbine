package org.acme.theme;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import org.acme.game.GameConfig;

/**
 * The team configuration the UI renders. It serves the active theme's teams (which default to the
 * configured teams) plus whether AI is enabled, so the dashboard can show the AI theme controls.
 */
@Path("config")
public class UiConfigResource {
    private final ThemeService themes;
    private final GameConfig config;

    public UiConfigResource(ThemeService themes, GameConfig config) {
        this.themes = themes;
        this.config = config;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public UiConfig configuration() {
        List<Team> teams = themes.current().teams().stream()
            .map(t -> new Team(t.id(), t.name(), t.color(), t.car(), t.accent(), t.logo()))
            .toList();
        return new UiConfig(teams, config.ai().enabled(), themes.current().track());
    }

    public record UiConfig(List<Team> teams, boolean aiEnabled, RaceTheme.Track track) {}
    public record Team(int id, String name, String color, String car, String accent, String logo) {}
}
