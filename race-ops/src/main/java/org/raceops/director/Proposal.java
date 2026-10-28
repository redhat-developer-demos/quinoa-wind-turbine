package org.raceops.director;

/**
 * A proposed action, waiting for a human decision on the console. The director never
 * executes a boost or a theme change on its own; it proposes, the operator approves,
 * and only then does {@link org.raceops.mcp.AdminRaceClient} act on the game.
 *
 * @param type       {@code boost} (theme proposals come with the between-races slice)
 * @param team       the team the action applies to
 * @param multiplier the proposed power multiplier, above 1
 * @param seconds    how long the effect would last
 * @param message    the director's rationale, shown on the approval card
 */
public record Proposal(String id, String type, String raceId, Integer team, Double multiplier, Integer seconds,
                       String message, long createdAt) {

    public static final String BOOST = "boost";
}
