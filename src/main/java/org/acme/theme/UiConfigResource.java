package org.acme.theme;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;

import java.util.List;
import org.acme.commentary.CommentaryStyleService;
import org.acme.game.GameConfig;
import org.acme.input.InputModeService;

/**
 * The team configuration the UI renders. It serves the active theme's teams (which default to the
 * configured teams) plus whether AI is enabled, so the dashboard can show the AI theme controls,
 * the active input mode (tap, shake, both), and the commentary style the AI commentator uses.
 */
@Path("config")
public class UiConfigResource {
    private final ThemeService themes;
    private final GameConfig config;
    private final InputModeService inputModes;
    private final CommentaryStyleService commentaryStyles;

    public UiConfigResource(ThemeService themes, GameConfig config, InputModeService inputModes,
                            CommentaryStyleService commentaryStyles) {
        this.themes = themes;
        this.config = config;
        this.inputModes = inputModes;
        this.commentaryStyles = commentaryStyles;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "The UI configuration", description = "Returns the active theme's teams (name, color, car), "
        + "whether AI features are enabled, the active input mode (tap, shake, both) and the commentary style "
        + "(broadcast, hype, analyst, dry). This is what the player and dashboard UIs render.")
    public UiConfig configuration() {
        List<Team> teams = themes.current().teams().stream()
            .map(t -> new Team(t.id(), t.name(), t.color(), t.car(), t.accent(), t.logo()))
            .toList();
        return new UiConfig(teams, config.ai().enabled(), themes.current().track(),
            config.ops().enabled() ? config.ops().url() : null,
            inputModes.current().value(), commentaryStyles.current().value());
    }

    public record UiConfig(List<Team> teams, boolean aiEnabled, RaceTheme.Track track, String opsUrl,
                           String inputMode, String commentaryStyle) {}
    public record Team(int id, String name, String color, String car, String accent, String logo) {}
}
