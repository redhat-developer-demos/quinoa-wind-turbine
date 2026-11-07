package org.racecreative.a2a;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.TaskNotCancelableError;
import org.a2aproject.sdk.spec.TextPart;
import org.racecreative.creative.CreativeService;

import java.util.List;

/**
 * Runs one creative request per A2A task: propose or apply, chosen by the request's own
 * {@code mode}. One answer, no streaming and no follow-up turns. A bad request completes
 * with an error artifact so race-ops gets a readable reason back.
 */
@ApplicationScoped
public class CreativeAgentExecutorProducer {

    private final CreativeService creative;

    @Inject
    public CreativeAgentExecutorProducer(CreativeService creative) {
        this.creative = creative;
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
                    emitter.addArtifact(List.of(new TextPart(creative.handle(request))),
                        null, "creative-proposal", null);
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
