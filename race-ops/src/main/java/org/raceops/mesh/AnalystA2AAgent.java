package org.raceops.mesh;

import dev.langchain4j.agentic.declarative.A2AClientAgent;
import dev.langchain4j.agentic.declarative.A2AServerUrlSupplier;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * The analyst specialist, over A2A. One call per request: send the snapshot pair, get the
 * engagement report and diagnosis back. The URL is resolved from config when the agent is
 * built, so dev (localhost:8082) and the cluster (service DNS) differ only by property.
 */
public interface AnalystA2AAgent {

    /** The agentic-scope key this agent's answer lands under; MeshClient reads the route from it. */
    String OUTPUT_KEY = "engagement";

    @A2AClientAgent(
        name = "Race Analyst",
        description = "Compares two race snapshots and says why a team is behind: tappers, "
            + "players who stopped tapping, and average power per player.",
        outputKey = OUTPUT_KEY)
    ResultWithAgenticScope<String> analyze(AnalysisRequest analysisRequest);

    @A2AServerUrlSupplier
    static String serverUrl() {
        return ConfigProvider.getConfig().getValue("race-ops.analyst.a2a-url", String.class);
    }
}
