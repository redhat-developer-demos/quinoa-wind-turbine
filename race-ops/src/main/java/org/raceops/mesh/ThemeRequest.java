package org.raceops.mesh;

/**
 * The creative app's A2A payload, wrapped so the supervisor's planner cannot paraphrase it.
 * See {@link AnalysisRequest} for why: the planner model rewrites a JSON request into prose,
 * and the creative app only accepts its own request object (or a proposal brief). Keeping the
 * payload typed makes the route the only thing the model decides.
 */
public record ThemeRequest(String json) {

    @Override
    public String toString() {
        return json;
    }
}
