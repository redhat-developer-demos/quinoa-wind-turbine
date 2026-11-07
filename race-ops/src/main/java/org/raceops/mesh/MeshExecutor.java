package org.raceops.mesh;

/**
 * Runs a mesh call off the snapshot observer. A specialist that hangs must not hold up the
 * director: the observer keeps accepting snapshots while one executor thread waits on the
 * A2A call, and the in-process report keeps the console live.
 */
@FunctionalInterface
public interface MeshExecutor {

    void execute(Runnable task);
}
