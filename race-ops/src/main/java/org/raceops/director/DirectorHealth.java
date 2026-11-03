package org.raceops.director;

import io.quarkus.logging.Log;
import io.quarkus.oidc.client.OidcClientException;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Whether the director can act on the game. A reachable race-ops says nothing about that: the
 * game may be down, or running without OIDC so the admin tools reject the director's token.
 * A failure is logged once as a single line (the stack trace goes to debug), repeats of the
 * same problem stay quiet, and the console shows the current problem until a call succeeds.
 */
@ApplicationScoped
public class DirectorHealth {

    private static final int MAX_LENGTH = 200;

    private volatile String problem;

    public void failed(String operation, Exception e) {
        String summary = operation + ": " + describe(e);
        if (!summary.equals(problem)) {
            Log.warnf("Director cannot act on the game, %s", summary);
        }
        Log.debugf(e, "Director call %s failed", operation);
        problem = summary;
    }

    public void succeeded() {
        if (problem != null) {
            Log.info("Director can reach the game again");
            problem = null;
        }
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
