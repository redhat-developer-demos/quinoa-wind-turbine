package org.raceops.monitor;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * The race the latest snapshot belongs to. The approval gate reads it to reject
 * proposals left open across a reset or a finish, so an old card cannot boost
 * the next race. It also carries the status, because a look approved mid-race has
 * to wait for the finish: the game rejects a restyle while a heat is running.
 */
@ApplicationScoped
public class CurrentRace {

    private volatile String raceId;
    private volatile String status;

    public void update(String raceId) {
        update(raceId, null);
    }

    public void update(String raceId, String status) {
        this.raceId = raceId;
        this.status = status;
    }

    /**
     * @return the current race id, or null before the first snapshot
     */
    public String raceId() {
        return raceId;
    }

    /**
     * @return true while a heat is running, so a theme must wait for the finish
     */
    public boolean running() {
        return "STARTED".equals(status) || "PAUSED".equals(status);
    }

    /**
     * @return the last status seen, or null before the first snapshot
     */
    public String status() {
        return status;
    }
}
