package org.raceops.mesh;

/**
 * The analyst's A2A payload, wrapped so the supervisor's planner cannot paraphrase it.
 *
 * <p>A declarative supervisor asks its model for the arguments of the specialist it picked,
 * and the model rewrites a long JSON request into a sentence: the analyst once received prose
 * with no snapshots and answered "The analysis request needs a current snapshot" (seen live,
 * 2026-11-06). The planner will not overwrite a typed scope value with generated text, so the
 * payload travels as this value and only the choice of agent comes from the model.
 *
 * <p>The A2A client turns a parameter into the message text with its {@code toString()},
 * which is why this record's {@code toString()} is the JSON and nothing else.
 */
public record AnalysisRequest(String json) {

    @Override
    public String toString() {
        return json;
    }
}
