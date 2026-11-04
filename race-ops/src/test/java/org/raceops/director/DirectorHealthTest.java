package org.raceops.director;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.oidc.client.OidcClientException;
import org.junit.jupiter.api.Test;
import org.raceops.metrics.DirectorMetrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectorHealthTest {

    private static DirectorHealth health() {
        return new DirectorHealth(new DirectorMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void aFailureIsReportedUntilACallSucceeds() {
        DirectorHealth health = health();
        assertNull(health.problem());

        health.failed("start_race", new IllegalStateException("game unreachable"));
        assertEquals("start_race: game unreachable", health.problem());

        health.succeeded();
        assertNull(health.problem());
    }

    @Test
    void anOidcFailureNamesTheRootCauseAndHintsAtTheGameSetting() {
        Exception e = new IllegalStateException("MCP tool start_race failed",
            new RuntimeException("wrapper", new OidcClientException("Keycloak is not available")));

        String problem = DirectorHealth.describe(e);

        assertTrue(problem.startsWith("Keycloak is not available"), problem);
        assertTrue(problem.contains("GAME_OIDC_ENABLED=true"), problem);
    }

    @Test
    void aLongMessageIsCut() {
        String problem = DirectorHealth.describe(new IllegalStateException("x".repeat(500)));

        assertTrue(problem.length() < 250, problem);
    }
}
