package org.raceops.mesh;

import dev.langchain4j.agentic.declarative.AgentListenerSupplier;
import dev.langchain4j.agentic.declarative.SupervisorAgent;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.agentic.supervisor.SupervisorResponseStrategy;

/**
 * The director's one entry point to the mesh: a supervisor over both specialists. Mid-race the
 * analyst always runs first and the model decides the intervention from its diagnosis, so the
 * next step depends on an agent's answer rather than on a task line the caller filled in.
 *
 * <p>Its own answer is not used. {@code LAST} returns the last specialist's answer verbatim, so
 * a structured proposal reaches the director's guardrails unchanged. Summarizing it would put a
 * second model in the path: it reworded the creative JSON into prose once already (seen live,
 * 2026-11-06), and the numbers cost another model call the short races cannot afford.
 *
 * <p>The request text carries the phase and the facts to reason over, never the payload. The
 * payloads are passed as {@link AnalysisRequest} and {@link ThemeRequest}, typed scope values
 * the planner will not replace with model-generated text. Without that, the planner rewrote the
 * snapshot pair into a sentence and the analyst had nothing to compute from.
 *
 * <p>The steering lives in {@code supervisorContext}, not in a {@code @SystemMessage}: the
 * framework builds the planner's system message itself and only appends that context, so an
 * annotation here would be silently ignored (checked against the 1.22.0 source and a live run).
 * See {@code DirectorDecisions.supervisorContext}.
 */
public interface RaceDirectorSupervisor {

    @AgentListenerSupplier
    static AgentListener listener() {
        return new MeshTraceListener();
    }

    @SupervisorAgent(
        name = "Race director",
        description = "Diagnoses a race situation and chooses a boost or a next-heat look",
        outputKey = "answer",
        maxAgentsInvocations = 2,
        responseStrategy = SupervisorResponseStrategy.LAST,
        subAgents = {AnalystA2AAgent.class, CreativeA2AAgent.class})
    ResultWithAgenticScope<String> delegate(String request, String supervisorContext,
                                            AnalysisRequest analysisRequest, ThemeRequest themeRequest,
                                            boolean themeWindow);
}
