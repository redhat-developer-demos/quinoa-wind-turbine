package org.raceanalyst.a2a;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.TaskNotCancelableError;
import org.a2aproject.sdk.spec.TextPart;
import org.raceanalyst.analytics.AnalysisService;

import java.util.List;

/**
 * Runs one analysis per A2A task: the request text is the snapshot pair as JSON, the result
 * is a JSON artifact. No streaming and no follow-up turns — the analyst answers once, from
 * data the caller already captured. A bad request still completes with an error artifact
 * instead of failing the task, so the supervisor gets a readable reason back.
 */
@ApplicationScoped
public class AnalystAgentExecutorProducer {

    private final AnalysisService analysis;

    @Inject
    public AnalystAgentExecutorProducer(AnalysisService analysis) {
        this.analysis = analysis;
    }

    @Produces
    @ApplicationScoped
    public AgentExecutor agentExecutor() {
        return new AgentExecutor() {

            @Override
            public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
                String request = context.getUserInput();
                emitter.startWork();
                try {
                    emitter.addArtifact(List.of(new TextPart(analysis.analyze(request))),
                        null, "engagement-analysis", null);
                } catch (RuntimeException e) {
                    emitter.addArtifact(List.of(new TextPart("Error: " + e.getMessage())),
                        null, "error", null);
                }
                emitter.complete();
            }

            @Override
            public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
                throw new TaskNotCancelableError();
            }
        };
    }
}
