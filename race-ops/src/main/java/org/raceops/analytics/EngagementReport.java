package org.raceops.analytics;

import java.util.List;

/**
 * Per-team engagement derived from the race snapshot. This is the director's fairness
 * instrument: team distance is the average power per tapping player, so headcount does not
 * matter, but players who tapped once and pocketed the phone drag their team's average down
 * while still counting in the denominator. Active versus stalled is that distinction.
 *
 * @param raceId  the race the report describes
 * @param status  the race status at report time
 * @param elapsedMs race-elapsed milliseconds at report time
 * @param teams   one entry per team, by position
 */
public record EngagementReport(String raceId, String status, long elapsedMs, List<TeamEngagement> teams) {

    /**
     * @param team    team number, starting at 1
     * @param position current position in the race
     * @param tappers players who sent at least one tap (the denominator of the race math)
     * @param active  players whose power grew over the analytics window
     * @param stalled players whose power did not grow over the analytics window
     * @param engagementRate active divided by tappers, 0 when nobody tapped
     * @param power   total team power
     * @param distance team distance, percent of the track
     */
    public record TeamEngagement(int team, int position, int tappers, int active, int stalled,
                                 double engagementRate, long power, double distance) {
    }
}
