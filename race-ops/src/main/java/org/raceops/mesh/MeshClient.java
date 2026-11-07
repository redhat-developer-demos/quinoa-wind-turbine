package org.raceops.mesh;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * The one door to the mesh. The director asks here; whether the specialists are configured
 * at all is {@code race-ops.mesh-enabled}, and every failure propagates for the caller to
 * fall back from. The agents are resolved lazily: with the mesh off, their A2A clients are
 * never built, so a default start needs no other app.
 *
 * <p>Every decision the director makes with a model goes through {@link #intervene}: one
 * supervisor call that always starts with the analyst's diagnosis and lets the model choose
 * the intervention from it. What the model chose is read back from the agentic scope, never
 * from its wording. The analyst is also called directly for the console's engagement numbers:
 * those refresh every couple of seconds and are facts, not decisions. An approved theme is
 * executed directly as well, because a change the operator already approved must not wait on
 * another model decision.
 */
@ApplicationScoped
public class MeshClient {

    /**
     * Mid-race the model has to answer while the race is still running: a boost measured about
     * 6 s locally including the A2A hop.
     */
    public static final long INTERVENTION_TIMEOUT_SECONDS = 15;

    /**
     * Between heats nothing is moving, and the run has to pay for two model calls plus the
     * creative director's own MCP round trips to the game. Measured 15 s+ on the cluster, so a
     * next-heat look gets a longer budget than a decision that has to land inside a live race.
     */
    public static final long THEME_INTERVENTION_TIMEOUT_SECONDS = 40;

    private final Instance<RaceDirectorSupervisor> supervisor;
    private final Instance<AnalystA2AAgent> analyst;
    private final Instance<CreativeA2AAgent> creative;
    private final ManagedExecutor executor;
    private final ObjectMapper mapper;

    @Inject
    public MeshClient(Instance<RaceDirectorSupervisor> supervisor,
                      Instance<AnalystA2AAgent> analyst, Instance<CreativeA2AAgent> creative,
                      ManagedExecutor executor, ObjectMapper mapper) {
        this.supervisor = supervisor;
        this.analyst = analyst;
        this.creative = creative;
        this.executor = executor;
        this.mapper = mapper;
    }

    /**
     * The analyst alone, no model: the console's engagement numbers are facts, not prose.
     */
    public String analyze(String requestJson) {
        return analyst.get().analyze(new AnalysisRequest(requestJson)).result();
    }

    /**
     * One supervised intervention. The supervisor reads the diagnosis, decides, and may call
     * the creative director for a next-heat look. Bounded, because a card that lands after the
     * finish helps nobody. The payloads travel as typed arguments, so the planner picks the
     * route and the intervention but cannot paraphrase the data away.
     *
     * @param request      the phase and the facts to reason over; never the payload
     * @param context      the phase's standing order, read by the planner as
     *                     {@code supervisorContext}
     * @param analysisJson the analyst payload
     * @param themeJson    the creative payload
     * @param themeWindow  true when the creative director may be called: between heats, or
     *                     mid-race after an engagement collapse. Read by the agent's
     *                     activation condition, never by the planner's model.
     * @param timeoutSeconds how long the director waits before it falls back on its own rules
     */
    public Intervention intervene(String request, String context, String analysisJson, String themeJson,
                                  boolean themeWindow, long timeoutSeconds) {
        try {
            ResultWithAgenticScope<String> result = CompletableFuture
                .supplyAsync(() -> supervisor.get().delegate(request, context,
                    new AnalysisRequest(analysisJson), new ThemeRequest(themeJson), themeWindow), executor)
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .join();
            AgenticScope scope = result.agenticScope();
            boolean diagnosed = scope != null && scope.hasState("engagement");
            return new Intervention(result.result(),
                diagnosed ? scope.readState("engagement", "") : "",
                diagnosed,
                scope != null && scope.hasState("creative"));
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException runtime ? runtime
                : new IllegalStateException(cause == null ? e.getMessage() : cause.getMessage(), cause);
        }
    }

    /**
     * One supervised run: the last specialist's answer, the analyst's diagnosis, and which
     * specialists actually ran. The fields are read from the agentic scope; a route is never
     * parsed out of model text.
     *
     * @param answer    the last specialist's answer verbatim
     * @param diagnosis the analyst's answer, empty when it did not run
     * @param diagnosed the analyst ran
     * @param themed    the creative director ran
     */
    public record Intervention(String answer, String diagnosis, boolean diagnosed, boolean themed) {
    }

    /**
     * Approved execution, straight to the creative app with no model in the path. The
     * creative app answers with the proposal it executed; an error artifact comes back as
     * {@code "Error: ..."}, which is raised so the card records a failure instead of
     * showing a sentence that claims a change the game rejected.
     */
    public String applyTheme(String kind, String brief) {
        String answer;
        try {
            String request = mapper.writeValueAsString(
                Map.of("mode", "apply", "kind", kind, "brief", brief));
            answer = creative.get().creative(new ThemeRequest(request)).result();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize the theme brief: " + e.getOriginalMessage(), e);
        }
        if (answer == null || answer.isBlank()) {
            throw new IllegalStateException("The creative agent returned nothing");
        }
        if (answer.startsWith("Error:")) {
            throw new IllegalStateException(answer.substring("Error:".length()).trim());
        }
        try {
            JsonNode json = mapper.readTree(answer);
            if (json.hasNonNull("message")) {
                return json.get("message").asText();
            }
        } catch (JsonProcessingException e) {
            // Not JSON: hand the raw answer back, it is still the best thing to show.
        }
        return answer;
    }
}
