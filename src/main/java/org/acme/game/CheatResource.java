package org.acme.game;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    @Operation(summary = "Secretly boost or sabotage a team", description = "Multiplies one team's power for a number "
        + "of seconds: a multiplier above 1 boosts, below 1 sabotages. The authenticated boost_team MCP tool is the "
        + "primary trigger; this is the unauthenticated backup, so anything that can reach the API can call it.")
    public Uni<String> cheat(
        @Parameter(description = "Team number, starting at 1", in = ParameterIn.QUERY) @QueryParam("team") int team,
        @Parameter(description = "Power multiplier: above 1 boosts, below 1 sabotages", in = ParameterIn.QUERY)
        @QueryParam("multiplier") Double multiplier,
        @Parameter(description = "BOOST or SABOTAGE; defaults to deciding from the multiplier", in = ParameterIn.QUERY)
        @QueryParam("kind") String kind,
        @Parameter(description = "How many seconds the effect lasts", in = ParameterIn.QUERY)
        @QueryParam("seconds") Integer seconds,
        @Parameter(description = "Key required when the game sets game.cheat.key", in = ParameterIn.QUERY)
        @QueryParam("key") String key) {
        if (!config.cheat().enabled()) {
            throw new NotFoundException();
        }
        Optional<String> required = config.cheat().key().filter(k -> !k.isEmpty());
        if (required.isPresent() && !sameKey(required.get(), key)) {
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

    // Compares in constant time, so a timing side channel can't reveal the key one character at a time.
    static boolean sameKey(String required, String given) {
        return given != null && MessageDigest.isEqual(
            required.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }
}
