package org.acme;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

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
            .map(t -> new Team(t.id(), t.name(), t.color(), t.car(), t.carTint()))
            .toList();
        return new UiConfig(teams, config.ai().enabled());
    }

    public record UiConfig(List<Team> teams, boolean aiEnabled) {}
    public record Team(int id, String name, String color, String car, String carTint) {}
}
