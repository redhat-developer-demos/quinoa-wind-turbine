package org.raceops.analytics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Per-team engagement as the director's console shows it: the report race-analyst computes
 * over the analytics window, or the in-process fallback when the analyst is down. The
 * director never computes active-versus-stalled itself any more.
 *
 * @param raceId    the race the report describes
 * @param status    the race status at report time
 * @param elapsedMs race-elapsed milliseconds at report time
 * @param teams     one entry per team, by position
 * @param diagnosis one sentence saying why the trailing team is behind, from the analyst
 *                  or the fallback
 * @param source    {@code analyst} when the A2A specialist computed it, {@code in-process}
 *                  for the degraded fallback — so the console can say which one is showing
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EngagementReport(String raceId, String status, long elapsedMs, List<TeamEngagement> teams,
                               String diagnosis, String source) {

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
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TeamEngagement(int team, int position, int tappers, int active, int stalled,
                                 double engagementRate, long power, double distance) {
    }
}
