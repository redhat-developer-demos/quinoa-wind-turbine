package org.raceops;

import io.quarkus.test.junit.QuarkusTest;
import dev.langchain4j.agentic.declarative.ActivationCondition;
import dev.langchain4j.agentic.declarative.SupervisorAgent;
import dev.langchain4j.agentic.scope.DefaultAgenticScope;
import dev.langchain4j.agentic.supervisor.SupervisorResponseStrategy;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.raceops.mesh.AnalysisRequest;
import org.raceops.mesh.AnalystA2AAgent;
import org.raceops.mesh.CreativeA2AAgent;
import org.raceops.mesh.MeshClient;
import org.raceops.mesh.RaceDirectorSupervisor;
import org.raceops.mesh.ThemeRequest;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The default boot: no game, no analysts, no model, no Keycloak. The supervisor and the A2A
 * agents are registered but never built, so the app starts without reaching the two specialist
 * apps. This is the state a bare {@code ./mvnw quarkus:dev} and the test suite run in.
 */
@QuarkusTest
class RaceOpsBootTest {

    @Inject
    Instance<RaceDirectorSupervisor> supervisor;

    @Inject
    Instance<CreativeA2AAgent> creative;

    @Inject
    Instance<MeshClient> mesh;

    @Test
    void theSupervisorTheAgentsAndTheMeshClientAreRegistered() {
        assertTrue(supervisor.isResolvable(), "the supervisor comes from the agentic extension");
        assertTrue(creative.isResolvable(), "the creative agent is the second A2A hop");
        assertTrue(mesh.isResolvable());
    }

    @Test
    void theSupervisorHasBothSpecialistsAndReturnsTheirAnswerUnchanged() throws Exception {
        // The talk's orchestration beat is one model choosing between two specialists. A
        // supervisor with a single sub-agent demonstrates nothing, and a summarizing strategy
        // puts a second model between a structured proposal and the guardrails that read it.
        SupervisorAgent config = RaceDirectorSupervisor.class
            .getMethod("delegate", String.class, String.class, AnalysisRequest.class, ThemeRequest.class,
                boolean.class)
            .getAnnotation(SupervisorAgent.class);
        assertEquals(List.of(AnalystA2AAgent.class, CreativeA2AAgent.class),
            List.of(config.subAgents()));
        assertEquals(SupervisorResponseStrategy.LAST, config.responseStrategy());
        // Two hops: the analyst always runs, and the model may add the creative director for
        // a next-heat look. The cap keeps a misbehaving planner at two agent calls.
        assertEquals(2, config.maxAgentsInvocations());
    }

    @Test
    void theCreativeDirectorIsOnlyActiveInTheThemeWindow() throws Exception {
        // A structural gate beats a prompt request: the planner only offers active agents,
        // so a soft-tapping diagnosis cannot cost the creative hop.
        assertNotNull(CreativeA2AAgent.class.getMethod("themeWindowOpen",
                dev.langchain4j.agentic.scope.AgenticScope.class)
            .getAnnotation(ActivationCondition.class), "the condition must be declared");
        DefaultAgenticScope open = DefaultAgenticScope.ephemeralAgenticScope();
        open.writeState(CreativeA2AAgent.THEME_WINDOW, true);
        assertTrue(CreativeA2AAgent.themeWindowOpen(open));
        DefaultAgenticScope closed = DefaultAgenticScope.ephemeralAgenticScope();
        closed.writeState(CreativeA2AAgent.THEME_WINDOW, false);
        assertFalse(CreativeA2AAgent.themeWindowOpen(closed));
        assertFalse(CreativeA2AAgent.themeWindowOpen(DefaultAgenticScope.ephemeralAgenticScope()),
            "no value means no window");
    }

    @Test
    void theConsoleAnswersWithNoSnapshotYet() {
        given()
            .when().get("/api/ops/state")
            .then().statusCode(200)
            .body("pending", empty())
            .body("problem", nullValue())
            .body("race.engagement", nullValue());
    }
}
