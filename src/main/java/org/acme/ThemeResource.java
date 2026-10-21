package org.acme;

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

/**
 * Admin control over the race theme. The paths are pinned to the form login in application.properties,
 * so the dashboard uses them with its operator session. Applying a theme returns 204 on success or 422
 * with the validation errors, in which case the current theme stays.
 */
@Path("theme")
public class ThemeResource {

    private final ThemeService themes;

    public ThemeResource(ThemeService themes) {
        this.themes = themes;
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

    private Response applied(List<String> errors) {
        if (errors.isEmpty()) {
            return Response.noContent().build();
        }
        return Response.status(422).entity(Map.of("errors", errors)).build();
    }
}
