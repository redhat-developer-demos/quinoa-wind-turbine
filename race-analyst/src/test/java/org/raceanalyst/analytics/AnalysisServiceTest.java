package org.raceanalyst.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The A2A contract in one place: what race-ops sends and what it can parse back. The
 * snapshots here carry the game's own field names, including fields the analyst ignores,
 * so a game-side addition cannot break this app.
 */
class AnalysisServiceTest {

    private static final String BASE = """
        {"raceId":"race-7","status":"STARTED","elapsedMs":12000,"updatedAt":1000,"winner":null,
         "teams":[{"id":1,"position":1,"players":2,"power":300,"distance":55.0,"finishTimeMs":null},
                  {"id":2,"position":2,"players":2,"power":150,"distance":40.0,"finishTimeMs":null}],
         "players":[{"name":"ada","team":1,"generated":150,"color":"#fff"},
                    {"name":"bo","team":1,"generated":150},
                    {"name":"cy","team":2,"generated":150},
                    {"name":"di","team":2,"generated":20}]}
        """;

    private static final String CURRENT = """
        {"raceId":"race-7","status":"STARTED","elapsedMs":17000,"updatedAt":2000,"winner":null,
         "teams":[{"id":1,"position":1,"players":2,"power":340,"distance":60.0,"finishTimeMs":null},
                  {"id":2,"position":2,"players":2,"power":150,"distance":52.0,"finishTimeMs":null}],
         "players":[{"name":"ada","team":1,"generated":180},
                    {"name":"bo","team":1,"generated":160},
                    {"name":"cy","team":2,"generated":150},
                    {"name":"di","team":2,"generated":20}]}
        """;

    private final AnalysisService service = new AnalysisService(new ObjectMapper());

    @Test
    void returnsTheReportAndTheDiagnosisAsJson() throws Exception {
        JsonNode answer = new ObjectMapper().readTree(
            service.analyze("{\"base\":" + BASE + ",\"current\":" + CURRENT + "}"));

        JsonNode engagement = answer.get("engagement");
        assertEquals("race-7", engagement.get("raceId").asText());
        assertEquals("STARTED", engagement.get("status").asText());
        assertEquals(17000, engagement.get("elapsedMs").asLong());
        JsonNode teamTwo = engagement.get("teams").get(1);
        assertEquals(2, teamTwo.get("tappers").asInt());
        assertEquals(0, teamTwo.get("active").asInt());
        assertEquals(2, teamTwo.get("stalled").asInt());
        assertEquals(0.0, teamTwo.get("engagementRate").asDouble(), 1e-9);

        String diagnosis = answer.get("diagnosis").asText();
        assertTrue(diagnosis.contains("Team 2"), diagnosis);
        assertTrue(diagnosis.contains("stopped tapping"), diagnosis);
    }

    @Test
    void acceptsASingleSnapshotWithoutABase() throws Exception {
        JsonNode answer = new ObjectMapper().readTree(
            service.analyze("{\"current\":" + CURRENT + "}"));
        assertEquals(0, answer.get("engagement").get("teams").get(0).get("active").asInt());
        assertTrue(answer.get("diagnosis").asText().contains("Team 2"),
            answer.get("diagnosis").asText());
    }

    @Test
    void acceptsTheSnapshotsWrappedInASentence() throws Exception {
        // A supervisor handing this task over tends to add a sentence around the payload.
        String wrapped = "Analyze this race, please. {\"base\":" + BASE + ",\"current\":" + CURRENT
            + "} Then say why team 2 is behind.";
        JsonNode answer = new ObjectMapper().readTree(service.analyze(wrapped));
        assertEquals("race-7", answer.get("engagement").get("raceId").asText());
        assertTrue(answer.get("diagnosis").asText().contains("Team 2"),
            answer.get("diagnosis").asText());
    }

    @Test
    void rejectsARequestWithoutACurrentSnapshot() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> service.analyze("{\"base\":" + BASE + "}"));
        assertTrue(error.getMessage().contains("current snapshot"), error.getMessage());
    }

    @Test
    void rejectsMalformedJsonWithAReadableMessage() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> service.analyze("{not json"));
        assertTrue(error.getMessage().startsWith("Cannot parse the analysis request"),
            error.getMessage());
    }
}
