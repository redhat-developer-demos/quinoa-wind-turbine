package org.raceops.watch;

import org.raceops.model.RaceSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a pair of adjacent race snapshots into the events worth acting on. The same shape as
 * the game's own commentary trigger detector, written independently from the snapshot spec:
 * lifecycle transitions, distance milestones, and lead changes. Everything else is routine.
 *
 * The fairness math behind what is deliberately absent: team distance is normalized by the
 * number of players who tapped, so headcount differences are not events. A team falling
 * behind is the director's business (see the engagement analytics), not this filter's.
 */
public final class RaceEventFilter {

    private static final double[] MILESTONES = {25, 50, 75};

    /**
     * Positions only count as a lead once the field has actually moved: early flips at one or
     * two percent are noise from the first taps, not something to narrate.
     */
    private static final double LEAD_MIN_DISTANCE = 10;

    private RaceEventFilter() {
    }

    /**
     * @param prev the previous snapshot of the same race, or null when this is the first one seen
     * @param curr the new snapshot
     */
    public static List<RaceEvent> events(RaceSnapshot prev, RaceSnapshot curr) {
        List<RaceEvent> events = new ArrayList<>();
        if (curr == null || curr.raceId() == null) {
            return events;
        }
        boolean newRace = prev == null || !prev.raceId().equals(curr.raceId());
        boolean started = "STARTED".equals(curr.status());
        boolean wasWaiting = !newRace && "WAITING".equals(prev.status());
        boolean wasPaused = !newRace && "PAUSED".equals(prev.status());

        if (started && (newRace || wasWaiting)) {
            events.add(new RaceEvent(RaceEvent.STARTED, curr.raceId(), curr.updatedAt(), null,
                "The race started"));
        } else if (started && wasPaused) {
            events.add(new RaceEvent(RaceEvent.RESUMED, curr.raceId(), curr.updatedAt(), null,
                "The race resumed"));
        }
        if (!newRace && "STARTED".equals(prev.status()) && "PAUSED".equals(curr.status())) {
            events.add(new RaceEvent(RaceEvent.PAUSED, curr.raceId(), curr.updatedAt(), null,
                "The race paused"));
        }
        if (!newRace && !"FINISHED".equals(prev.status()) && "FINISHED".equals(curr.status())) {
            RaceSnapshot.Team winner = curr.winner() != null ? curr.team(curr.winner()) : null;
            String time = winner != null && winner.finishTimeMs() != null
                ? "%.1f s".formatted(winner.finishTimeMs() / 1000.0) : "unknown time";
            events.add(new RaceEvent(RaceEvent.FINISHED, curr.raceId(), curr.updatedAt(), curr.winner(),
                "The race finished, team " + (curr.winner() != null ? curr.winner() : "?") + " won in " + time));
        }

        for (RaceSnapshot.Team team : curr.teams() == null ? List.<RaceSnapshot.Team>of() : curr.teams()) {
            double before = newRace ? 0 : distanceOf(prev, team.id());
            for (double milestone : MILESTONES) {
                if (before < milestone && team.distance() >= milestone) {
                    events.add(new RaceEvent(RaceEvent.MILESTONE, curr.raceId(), curr.updatedAt(), team.id(),
                        "Team " + team.id() + " passed " + (int) milestone + "% of the track"));
                }
            }
        }

        if (!newRace) {
            RaceSnapshot.Team before = prev.leader();
            RaceSnapshot.Team now = curr.leader();
            // The new leader must be past the noise floor; the previous one does not have to be,
            // because an early jostling lead being replaced by an established one is still a change.
            if (before != null && now != null && before.id() != now.id()
                && now.distance() > LEAD_MIN_DISTANCE) {
                events.add(new RaceEvent(RaceEvent.LEAD_CHANGE, curr.raceId(), curr.updatedAt(), now.id(),
                    "Team " + now.id() + " took the lead from team " + before.id() + " ("
                        + "%.1f%% vs %.1f%%)".formatted(now.distance(), before.distance())));
            }
        }
        return events;
    }

    private static double distanceOf(RaceSnapshot snapshot, int teamId) {
        RaceSnapshot.Team team = snapshot.team(teamId);
        return team == null ? 0 : team.distance();
    }
}
