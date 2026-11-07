package org.raceanalyst.analytics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.raceanalyst.model.RaceSnapshot;

/**
 * The analyst's one operation: take the requested snapshot pair, compute the engagement
 * report, and add the human diagnosis. The answer is JSON so the caller can parse the
 * report on its own; the diagnosis is the sentence the director's model phrases for the
 * console card.
 */
@ApplicationScoped
public class AnalysisService {

    private final ObjectMapper mapper;

    @Inject
    public AnalysisService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param requestJson {@code {"base": <snapshot or null>, "current": <snapshot>}}, or a
     *                    sentence with that JSON inside it: an orchestrator in front of this app
     *                    may wrap the payload in prose
     * @return {@code {"engagement": <report>, "diagnosis": "<one sentence>"}}
     */
    public String analyze(String requestJson) {
        Request request;
        try {
            request = mapper.readValue(jsonObject(requestJson), Request.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                "Cannot parse the analysis request: " + e.getOriginalMessage(), e);
        }
        if (request == null || request.current() == null) {
            throw new IllegalArgumentException("The analysis request needs a current snapshot");
        }
        EngagementReport report = EngagementAnalytics.analyze(request.base(), request.current());
        try {
            return mapper.writeValueAsString(new Analysis(report, Diagnosis.of(report)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize the analysis: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * The JSON object in the request, wherever the caller put it. A planner handing this task
     * over tends to add a sentence around the payload; it costs nothing to accept that.
     */
    private static String jsonObject(String request) {
        if (request == null) {
            throw new IllegalArgumentException("The analysis request needs a current snapshot");
        }
        int start = request.indexOf('{');
        int end = request.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return request; // let Jackson report what is wrong with it
        }
        return request.substring(start, end + 1);
    }

    /**
     * @param base    a snapshot from earlier in the same race, or null when this is the first one
     * @param current the latest snapshot
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Request(RaceSnapshot base, RaceSnapshot current) {
    }

    public record Analysis(EngagementReport engagement, String diagnosis) {
    }
}
