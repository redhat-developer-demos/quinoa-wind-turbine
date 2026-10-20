package org.acme;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A secret power-up or sabotage: it multiplies a team's incoming power for a while, so the team moves
 * faster or slower without anything showing in the app. The event carries the final multiplier and
 * duration, so the topology only has to apply it. The only trace is on the race-modifiers topic, which
 * the Confluent segment inspects to explain why a team pulled ahead.
 *
 * @param team       the team the effect applies to
 * @param multiplier above 1 boosts the team, below 1 sabotages it
 * @param durationMs how long the effect lasts, in race-elapsed milliseconds
 * @param raceId     the race the effect belongs to, so an effect for an old race is ignored
 */
@RegisterForReflection
public record RaceModifier(int team, double multiplier, long durationMs, String raceId) {
}
