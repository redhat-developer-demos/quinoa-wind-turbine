package org.raceops.director;

import java.util.UUID;

/**
 * A proposed action, waiting for a human decision on the console. The director never
 * executes a boost or a theme change on its own; it proposes, the operator approves,
 * and only then does the admin toolset act on the game.
 *
 * <p>A theme proposal comes in two shapes: a preset id the game applies directly, or an AI
 * brief ({@code kind} {@code ai-theme} or {@code ai-names}) that the creative app executes
 * through the game's generators after approval. Both are {@code theme} on the console, so
 * the operator sees one kind of card.
 *
 * @param type       {@code boost}, {@code theme} or {@code theme-ai}
 * @param team       the team a boost applies to
 * @param multiplier the proposed power multiplier, above 1
 * @param seconds    how long the effect would last
 * @param presetId   the theme preset a preset proposal would apply
 * @param kind       the creative kind of an AI theme proposal: {@code ai-theme} or {@code ai-names}
 * @param brief      what the game's generator is asked for, for an AI theme proposal
 * @param message    the director's rationale, shown on the approval card
 * @param deferred   true for a theme proposed mid-race: the game cannot restyle until the
 *                   heat is over, so an approval waits for the finish instead of applying
 */
public record Proposal(String id, String type, String raceId, Integer team, Double multiplier, Integer seconds,
                       String presetId, String kind, String brief, String message, long createdAt,
                       boolean deferred) {

    public static final String BOOST = "boost";
    public static final String THEME = "theme";
    public static final String THEME_AI = "theme-ai";

    public static Proposal boost(String raceId, Integer team, Double multiplier, Integer seconds, String message) {
        return new Proposal(UUID.randomUUID().toString(), BOOST, raceId, team, multiplier, seconds, null, null,
            null, message, System.currentTimeMillis(), false);
    }

    public static Proposal theme(String raceId, String presetId, String message) {
        return new Proposal(UUID.randomUUID().toString(), THEME, raceId, null, null, null, presetId, null,
            null, message, System.currentTimeMillis(), false);
    }

    public static Proposal aiTheme(String raceId, String kind, String brief, String message) {
        return new Proposal(UUID.randomUUID().toString(), THEME_AI, raceId, null, null, null, null, kind,
            brief, message, System.currentTimeMillis(), false);
    }

    /**
     * The same looked marked to apply after the current heat: a theme card shown mid-race.
     */
    public Proposal defer() {
        return new Proposal(id, type, raceId, team, multiplier, seconds, presetId, kind, brief, message,
            createdAt, true);
    }
}
