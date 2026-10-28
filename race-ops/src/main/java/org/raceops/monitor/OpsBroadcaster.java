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
 */
@ApplicationScoped
public class OpsBroadcaster {

    private static final int RECENT_LIMIT = 50;

    private final BroadcastProcessor<OpsUpdate> updates = BroadcastProcessor.create();
    private final ConcurrentLinkedDeque<RaceEvent> recent = new ConcurrentLinkedDeque<>();
    private volatile EngagementReport engagement;

    public void publish(OpsUpdate update) {
        engagement = update.engagement();
        for (RaceEvent event : update.events()) {
            recent.addLast(event);
            while (recent.size() > RECENT_LIMIT) {
                recent.pollFirst();
            }
        }
        updates.onNext(update);
    }

    /**
     * Refreshes the reported engagement without emitting an ops update, for snapshots where
     * nothing survived the filter but the underlying numbers still moved.
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
