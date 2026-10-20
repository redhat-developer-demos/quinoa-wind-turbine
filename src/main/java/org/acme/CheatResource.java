package org.acme;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.Optional;

/**
 * A secret fallback for triggering a boost or sabotage without a code assistant, for example
 * {@code GET /api/cheat?team=1}. It is unlinked and anonymous so it can be hit from a phone browser
 * mid-race; an optional key and an on/off switch keep it from being stumbled on. The admin MCP
 * {@code boost_team} tool is the primary, authenticated trigger; this is the backup.
 */
@Path("cheat")
public class CheatResource {

    private final GameResource game;
    private final GameConfig config;

    public CheatResource(GameResource game, GameConfig config) {
        this.game = game;
        this.config = config;
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> cheat(@QueryParam("team") int team,
                             @QueryParam("multiplier") Double multiplier,
                             @QueryParam("kind") String kind,
                             @QueryParam("seconds") Integer seconds,
                             @QueryParam("key") String key) {
        if (!config.cheat().enabled()) {
            throw new NotFoundException();
        }
        Optional<String> required = config.cheat().key().filter(k -> !k.isEmpty());
        if (required.isPresent() && !required.get().equals(key)) {
            throw new ForbiddenException();
        }
        if (team < 1) {
            throw new BadRequestException("team must be 1 or more");
        }
        double factor = multiplier != null ? multiplier
            : "sabotage".equalsIgnoreCase(kind) ? config.modifiers().sabotageMultiplier()
            : config.modifiers().boostMultiplier();
        long durationMs = seconds != null ? seconds * 1000L : config.modifiers().duration().toMillis();
        return game.sendModifier(team, factor, durationMs)
            .replaceWith("applied x" + factor + " to team " + team + " for " + (durationMs / 1000) + "s\n");
    }
}
