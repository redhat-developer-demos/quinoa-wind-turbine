package org.raceops.director;

import io.quarkus.logging.Log;
import io.quarkus.oidc.client.OidcClientException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.raceops.metrics.DirectorMetrics;

/**
 * Whether the director can act on the game. A reachable race-ops says nothing about that: the
 * game may be down, or running without OIDC so the admin tools reject the director's token.
 * A failure is logged once as a single line (the stack trace goes to debug), repeats of the
 * same problem stay quiet, and the console shows the current problem until a call succeeds.
 * The state is also a gauge ({@code race_ops_director_healthy}), so an alert can fire on it.
 */
@ApplicationScoped
public class DirectorHealth {

    private static final int MAX_LENGTH = 200;

    private final DirectorMetrics metrics;
    private volatile String problem;

    @Inject
    public DirectorHealth(DirectorMetrics metrics) {
        this.metrics = metrics;
    }

    public void failed(String operation, Exception e) {
        String summary = operation + ": " + describe(e);
        if (!summary.equals(problem)) {
            Log.warnf("Director cannot act on the game, %s", summary);
        }
        Log.debugf(e, "Director call %s failed", operation);
        problem = summary;
        metrics.directorHealthy(false);
    }

    public void succeeded() {
        if (problem != null) {
            Log.info("Director can reach the game again");
            problem = null;
        }
        metrics.directorHealthy(true);
    }

    /**
     * @return what currently stops the director, or null when its last call worked
     */
    public String problem() {
        return problem;
    }

    static String describe(Throwable e) {
        Throwable root = e;
        boolean oidc = false;
        for (int depth = 0; depth < 20 && root != null; depth++) {
            oidc |= root instanceof OidcClientException;
            if (root.getCause() == null) {
                break;
            }
            root = root.getCause();
        }
        String message = root == null || root.getMessage() == null
            ? e.getClass().getSimpleName() : root.getMessage();
        if (message.length() > MAX_LENGTH) {
            message = message.substring(0, MAX_LENGTH) + "...";
        }
        return oidc ? message + " (is the game running with OIDC enabled? GAME_OIDC_ENABLED=true)" : message;
    }
}
