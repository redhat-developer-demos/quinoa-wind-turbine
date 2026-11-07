package org.raceops.mesh;

import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.scope.AgenticScope;
import io.quarkus.logging.Log;

/**
 * Logs every hand-off in the mesh: which agent ran, what it was given, and which keys the
 * scope holds afterwards. Copied from the a2a lab's listener because it is the demo moment
 * that makes the routing visible — the stage shows the analyst answering before the boost
 * card appears.
 */
public class DebugAgentListener implements AgentListener {

    @Override
    public void beforeAgentInvocation(AgentRequest request) {
        Log.infof("Mesh: calling %s with %s", request.agentName(), request.inputs());
        AgenticScope scope = request.agenticScope();
        if (scope != null) {
            Log.debugf("Mesh scope keys before %s: %s", request.agentName(), scope.state().keySet());
        }
    }

    @Override
    public void afterAgentInvocation(AgentResponse response) {
        Log.infof("Mesh: %s returned", response.agentName());
    }

    @Override
    public boolean inheritedBySubagents() {
        return true;
    }
}
