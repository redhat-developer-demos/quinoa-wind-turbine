package org.raceops.monitor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.raceops.RaceOpsConfig;
import org.raceops.analytics.EngagementAnalytics;
import org.raceops.analytics.EngagementReport;
import org.raceops.model.RaceSnapshot;
import org.raceops.watch.RaceEvent;
import org.raceops.watch.RaceEventFilter;

import java.util.ArrayDeque;
import java.util.List;

/**
 * The single place snapshot state lives. Applies the event filter to adjacent snapshots,
 * computes the engagement report over a time window (adjacent snapshots are too noisy for
 * active-versus-stalled: a human tapping three times a second only moves their number every
 * few snapshots), and pushes what survived to the ops stream.
 */
@ApplicationScoped
public class RaceMonitor {

    private final OpsBroadcaster broadcaster;
    private final RaceOpsConfig config;
    private final Event<RaceSnapshotEvent> snapshotEvent;
    private final CurrentRace currentRace;
    private final ArrayDeque<RaceSnapshot> window = new ArrayDeque<>();

    @Inject
    public RaceMonitor(OpsBroadcaster broadcaster, RaceOpsConfig config, Event<RaceSnapshotEvent> snapshotEvent,
                       CurrentRace currentRace) {
        this.broadcaster = broadcaster;
        this.config = config;
        this.snapshotEvent = snapshotEvent;
        this.currentRace = currentRace;
    }

    public synchronized void onSnapshot(RaceSnapshot snapshot) {
        if (snapshot == null || snapshot.raceId() == null) {
            return;
        }
        currentRace.update(snapshot.raceId());
        RaceSnapshot prev = window.peekLast();
        // Snapshots can arrive out of order across reconnections; stale ones are dropped.
        if (prev != null && prev.raceId().equals(snapshot.raceId())
            && snapshot.updatedAt() <= prev.updatedAt()) {
            return;
        }
        if (prev == null || !prev.raceId().equals(snapshot.raceId())) {
            window.clear();
        }
        window.addLast(snapshot);
        long windowMs = config.analyticsWindowSeconds() * 1000L;
        while (window.size() > 1 && snapshot.updatedAt() - window.peekFirst().updatedAt() > windowMs) {
            window.pollFirst();
        }

        List<RaceEvent> events = RaceEventFilter.events(prev, snapshot);
        EngagementReport report = EngagementAnalytics.analyze(window.peekFirst(), snapshot);
        if (events.isEmpty()) {
            broadcaster.refresh(report);
        } else {
            broadcaster.publish(new OpsUpdate(events, report, System.currentTimeMillis()));
        }
        snapshotEvent.fireAsync(new RaceSnapshotEvent(snapshot, List.copyOf(window)));
    }
}
