package org.raceops.monitor;

import org.raceops.analytics.EngagementReport;
import org.raceops.watch.RaceEvent;

import java.util.List;

/**
 * One push to the ops stream: the events that survived the filter, plus the engagement
 * report computed over the same snapshot.
 */
public record OpsUpdate(List<RaceEvent> events, EngagementReport engagement, long emittedAt) {
}
