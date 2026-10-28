package org.raceops.ops;

import org.raceops.analytics.EngagementReport;
import org.raceops.watch.RaceEvent;

import java.util.List;

/**
 * A point-in-time answer for /api/ops/state: the latest engagement report and the recent
 * events. Null engagement means no snapshot has been seen yet.
 */
public record OpsState(EngagementReport engagement, List<RaceEvent> recentEvents, long generatedAt) {
}
