package org.acme.theme;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

import org.acme.game.GameConfig;

/**
 * Admin control over the race theme. The paths are pinned to the form login in application.properties,
 * so the dashboard uses them with its operator session. Applying a theme returns 204 on success or 422
 * with the validation errors, in which case the current theme stays. The AI endpoints need
 * {@code game.ai.enabled} and answer 503 without it.
 */
@Path("theme")
public class ThemeResource {

    private final ThemeService themes;
    private final GameConfig config;

    public ThemeResource(ThemeService themes, GameConfig config) {
        this.themes = themes;
        this.config = config;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public RaceTheme current() {
        return themes.current();
    }

    @GET
    @Path("presets")
    @Produces(MediaType.APPLICATION_JSON)
    public List<RaceTheme> presets() {
        return themes.presets();
    }

    @POST
    @Path("preset/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("admin")
    public Response applyPreset(@PathParam("id") String id) {
        RaceTheme preset = themes.presets().stream()
            .filter(t -> t.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("No preset theme " + id));
        return applied(themes.apply(preset));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("admin")
    public Response apply(RaceTheme theme) {
        return applied(themes.apply(theme));
    }

    /**
     * Generates a full theme from a prompt with the LLM, validates it and applies it. The current
     * track is kept; a theme the model returns but the validator rejects answers 422 like a manual
     * entry, and output that cannot be parsed at all answers 502.
     */
    @POST
    @Path("generate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("admin")
    public Response generate(ThemePrompt prompt) {
        if (!config.ai().enabled()) {
            return aiDisabled();
        }
        if (prompt == null || prompt.prompt() == null || prompt.prompt().isBlank()) {
            return badPrompt();
        }
        RaceTheme theme;
        try {
            theme = themes.generate(prompt.prompt());
        } catch (RuntimeException e) {
            return Response.status(502).entity(Map.of("errors",
                List.of("The model did not return a usable theme"))).build();
        }
        List<String> errors = themes.apply(theme);
        return errors.isEmpty() ? Response.ok(theme).build() : applied(errors);
    }

    /**
     * The light AI path: suggests two team names from an event or location prompt and applies them
     * onto the current colors, cars and track, so a booth can localize the teams in one call.
     */
    @POST
    @Path("suggest-names")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("admin")
    public Response suggestNames(ThemePrompt prompt) {
        if (!config.ai().enabled()) {
            return aiDisabled();
        }
        if (prompt == null || prompt.prompt() == null || prompt.prompt().isBlank()) {
            return badPrompt();
        }
        RaceTheme theme;
        try {
            theme = themes.suggestNames(prompt.prompt());
        } catch (RuntimeException e) {
            return Response.status(502).entity(Map.of("errors",
                List.of("The model did not return usable team names"))).build();
        }
        List<String> errors = themes.apply(theme);
        return errors.isEmpty() ? Response.ok(theme).build() : applied(errors);
    }

    private Response aiDisabled() {
        return Response.status(503).entity(Map.of("errors",
            List.of("AI is disabled; set game.ai.enabled=true to use it"))).build();
    }

    private Response badPrompt() {
        return Response.status(422).entity(Map.of("errors",
            List.of("A prompt is required, such as a city, event or audience"))).build();
    }

    private Response applied(List<String> errors) {
        if (errors.isEmpty()) {
            return Response.noContent().build();
        }
        return Response.status(422).entity(Map.of("errors", errors)).build();
    }

    public record ThemePrompt(String prompt) {
    }
}
