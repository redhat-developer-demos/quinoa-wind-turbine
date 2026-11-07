package org.raceops.monitor;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import org.raceops.analytics.EngagementReport;
import org.raceops.ops.OpsState;
import org.raceops.watch.RaceEvent;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Holds what the /ops surface shows: a live Multi for SSE subscribers, the latest engagement
 * report, and a bounded ring of recent events. Subscribers that connect late get future
 * updates only; the page loads the current state from /api/ops/state first.
 *
 * <p>The engagement report is owned by {@code EngagementService}, not by this class: the
 * monitor accepts a snapshot and pushes the events that survived the filter, and this
 * broadcaster attaches whatever report is current. That keeps the A2A call off the snapshot
 * thread — the director fills it in asynchronously.
 */
@ApplicationScoped
public class OpsBroadcaster {

    private static final int RECENT_LIMIT = 50;

    private final BroadcastProcessor<OpsUpdate> updates = BroadcastProcessor.create();
    private final ConcurrentLinkedDeque<RaceEvent> recent = new ConcurrentLinkedDeque<>();
    private volatile EngagementReport engagement;

    public void publish(List<RaceEvent> newEvents) {
        for (RaceEvent event : newEvents) {
            recent.addLast(event);
            while (recent.size() > RECENT_LIMIT) {
                recent.pollFirst();
            }
        }
        updates.onNext(new OpsUpdate(newEvents, engagement, System.currentTimeMillis()));
    }

    /**
     * Replaces the engagement report without emitting an ops update: the console reads it from
     * the state endpoint, and a report that changes without a race event is not an event.
     */
    public void refresh(EngagementReport report) {
        engagement = report;
    }

    public Multi<OpsUpdate> updates() {
        return updates;
    }

    public OpsState state() {
        return new OpsState(engagement, List.copyOf(recent), System.currentTimeMillis());
    }
}
