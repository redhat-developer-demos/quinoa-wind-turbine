package org.raceops.director;

import java.util.UUID;

/**
 * A proposed action, waiting for a human decision on the console. The director never
 * executes a boost or a theme change on its own; it proposes, the operator approves,
 * and only then does {@link org.raceops.mcp.AdminRaceClient} act on the game.
 *
 * @param type       {@code boost} or {@code theme}
 * @param team       the team a boost applies to
 * @param multiplier the proposed power multiplier, above 1
 * @param seconds    how long the effect would last
 * @param presetId   the theme preset a theme proposal would apply
 * @param message    the director's rationale, shown on the approval card
 */
public record Proposal(String id, String type, String raceId, Integer team, Double multiplier, Integer seconds,
                       String presetId, String message, long createdAt) {

    public static final String BOOST = "boost";
    public static final String THEME = "theme";

    public static Proposal boost(String raceId, Integer team, Double multiplier, Integer seconds, String message) {
        return new Proposal(UUID.randomUUID().toString(), BOOST, raceId, team, multiplier, seconds, null, message,
            System.currentTimeMillis());
    }

    public static Proposal theme(String raceId, String presetId, String message) {
        return new Proposal(UUID.randomUUID().toString(), THEME, raceId, null, null, null, presetId, message,
            System.currentTimeMillis());
    }
}
