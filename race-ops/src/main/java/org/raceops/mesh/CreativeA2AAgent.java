package org.raceops.mesh;

import dev.langchain4j.agentic.declarative.A2AClientAgent;
import dev.langchain4j.agentic.declarative.A2AServerUrlSupplier;
import dev.langchain4j.agentic.declarative.ActivationCondition;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * The creative specialist, over A2A. Proposals go through the supervisor so the mesh has one
 * entry point; approved execution calls this agent directly, because an approved change must
 * run as plain code rather than a second model decision.
 *
 * <p>The agent is inactive mid-race unless the trailing team's engagement collapsed. The
 * planner only offers active sub-agents, so on a soft-tapping diagnosis the model cannot spend
 * a hop on a look it would have to throw away. The gate is structural
 * ({@code @ActivationCondition}); the director's route guard stays as the second line of
 * defense.
 */
public interface CreativeA2AAgent {

    /** The scope value the director writes before a supervised run; read by the condition. */
    String THEME_WINDOW = "themeWindow";

    @A2AClientAgent(
        name = "Race Creative Director",
        description = "Proposes the next heat's theme and team names when players stopped "
            + "tapping or between heats, and executes an approved brief through the game's own "
            + "AI theme tools. It never changes the race in progress and cannot boost a team.",
        outputKey = "creative")
    ResultWithAgenticScope<String> creative(ThemeRequest themeRequest);

    /**
     * Between heats, and mid-race only after an engagement collapse. The value is written by
     * the director through the supervisor's {@code themeWindow} parameter; no agent declares
     * it as an argument, so the planner's model never fills it in.
     */
    @ActivationCondition(description = "between heats, or a mid-race engagement collapse")
    static boolean themeWindowOpen(AgenticScope scope) {
        return Boolean.TRUE.equals(scope.readState(THEME_WINDOW));
    }

    @A2AServerUrlSupplier
    static String serverUrl() {
        return ConfigProvider.getConfig().getValue("race-ops.creative.a2a-url", String.class);
    }
}
