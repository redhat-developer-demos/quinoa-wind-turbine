package org.raceops.watch;

/**
 * One meaningful moment in the race, distilled from the snapshot stream. The watcher receives
 * about four snapshots per second mid-race; these events are what survives the filter, because
 * nothing downstream (the console, later the director's LLM) should run per snapshot.
 *
 * @param type   one of {@code started}, {@code resumed}, {@code paused}, {@code finished},
 *               {@code milestone}, {@code lead-change}
 * @param raceId the race the event belongs to
 * @param at     the snapshot's updatedAt, epoch milliseconds
 * @param team   the team the event is about, when it is about one
 * @param detail a human-readable sentence for the console
 */
public record RaceEvent(String type, String raceId, long at, Integer team, String detail) {

    public static final String STARTED = "started";
    public static final String RESUMED = "resumed";
    public static final String PAUSED = "paused";
    public static final String FINISHED = "finished";
    public static final String MILESTONE = "milestone";
    public static final String LEAD_CHANGE = "lead-change";
}
