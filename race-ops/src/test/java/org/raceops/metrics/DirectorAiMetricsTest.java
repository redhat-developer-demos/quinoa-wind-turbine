package org.raceops.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.raceops.director.DirectorAi;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one metric the director cannot count itself: the model's token usage. It comes from
 * the langchain4j observability integration, so this boots the app against a local stub and
 * proves the meters land on the same registry the /metrics endpoint serves.
 */
@QuarkusTest
@TestProfile(DirectorAiMetricsTest.Enabled.class)
@QuarkusTestResource(value = OpenAiStub.class, restrictToAnnotatedClass = true)
class DirectorAiMetricsTest {

    @Inject
    DirectorAi ai;

    @Inject
    MeterRegistry registry;

    public static class Enabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("race-ops.ai-enabled", "true");
        }
    }

    @Test
    void aModelCallExposesItsTokenCount() {
        OpenAiStub.answer("""
            {"propose": true, "team": 2, "multiplier": 2.5, "seconds": 30, "message": "Team 2 is behind."}""");

        DirectorAi.BoostAdvice advice = ai.advise("{\"trailingTeam\": 2, \"deficitPercent\": 18.0}");

        assertTrue(advice.propose());
        double tokens = registry.getMeters().stream()
            .filter(Counter.class::isInstance)
            .filter(meter -> meter.getId().getName().contains("token"))
            .mapToDouble(meter -> ((Counter) meter).count())
            .sum();
        assertTrue(tokens > 0, "the model call must report prompt and completion tokens, saw: "
            + registry.getMeters().stream().map(meter -> meter.getId().getName()).distinct().toList());
        assertNotNull(registry.find("gen_ai.client.operation.duration").timer(),
            "the call duration is recorded next to the token counts, saw: "
                + registry.getMeters().stream().map(meter -> meter.getId().getName()).distinct().toList());
    }
}
