package org.raceops.monitor;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * The race the latest snapshot belongs to. The approval gate reads it to reject
 * proposals left open across a reset or a finish, so an old card cannot boost
 * the next race.
 */
@ApplicationScoped
public class CurrentRace {

    private volatile String raceId;

    public void update(String raceId) {
        this.raceId = raceId;
    }

    /**
     * @return the current race id, or null before the first snapshot
     */
    public String raceId() {
        return raceId;
    }
}
