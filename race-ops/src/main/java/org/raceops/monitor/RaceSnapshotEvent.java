package org.raceops.monitor;

import org.raceops.model.RaceSnapshot;

import java.util.List;

/**
 * Every accepted snapshot, with the analytics window it belongs to, oldest first. The
 * director observes these to run the event; the engagement analytics already live on the
 * window inside the monitor.
 */
public record RaceSnapshotEvent(RaceSnapshot snapshot, List<RaceSnapshot> window) {
}
