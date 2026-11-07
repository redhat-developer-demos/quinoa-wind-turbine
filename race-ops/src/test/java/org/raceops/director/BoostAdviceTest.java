package org.raceops.director;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The supervisor's answer is untrusted input: a fenced answer is fine, prose without JSON is
 * not, and a missing field falls back to the deterministic defaults rather than zero.
 */
class BoostAdviceTest {

    @Test
    void aBareAnswerIsRead() {
        DirectorDecisions.BoostAdvice advice = DirectorDecisions.parseBoostAdvice("""
            {"propose": true, "team": 2, "multiplier": 2.5, "seconds": 30, "message": "Team 2 is behind."}""");
        assertTrue(advice.propose());
        assertEquals(2, advice.team());
        assertEquals(2.5, advice.multiplier());
        assertEquals(30, advice.seconds());
        assertEquals("Team 2 is behind.", advice.message());
    }

    @Test
    void anAnswerInsideFencesAndProseIsTolerated() {
        DirectorDecisions.BoostAdvice advice = DirectorDecisions.parseBoostAdvice("""
            Here is my decision:
            ```json
            {"propose": false, "message": "The race is close."}
            ```
            """);
        assertFalse(advice.propose());
        assertEquals("The race is close.", advice.message());
        // The deterministic defaults stand in for the fields the model left out.
        assertEquals(2.5, advice.multiplier());
        assertEquals(30, advice.seconds());
    }

    @Test
    void proseWithoutJsonIsRejectedSoTheCallerFallsBack() {
        assertThrows(IllegalArgumentException.class,
            () -> DirectorDecisions.parseBoostAdvice("Boost team 2 now."));
    }

    @Test
    void theAnalystsDiagnosisBecomesTheCardReason() {
        // With SupervisorResponseStrategy.LAST the supervisor returns the analyst's answer
        // verbatim, so the reason on the card is the analyst's sentence and the policy owns
        // the numbers.
        DirectorDecisions.BoostAdvice advice = DirectorDecisions.parseBoostAdvice("""
            {"engagement": {"raceId": "race-1", "teams": []},
             "diagnosis": "Team 2 is 12.0 points behind because 2 of its 4 players stopped tapping."}""");
        assertTrue(advice.propose());
        assertEquals(2.5, advice.multiplier());
        assertEquals(30, advice.seconds());
        assertEquals("Team 2 is 12.0 points behind because 2 of its 4 players stopped tapping. "
            + "Boost them for 30 s to keep the race close?", advice.message());
    }

    @Test
    void anAnalysisWithoutADiagnosisIsNotAdvice() {
        DirectorDecisions.BoostAdvice advice = DirectorDecisions.parseBoostAdvice(
            "{\"engagement\": {\"raceId\": \"race-1\"}}");
        assertFalse(advice.propose());
    }

    @Test
    void theAnalysisRequestCarriesANullBaseOnTheFirstSnapshot() throws Exception {
        var snapshot = new org.raceops.model.RaceSnapshot("race-1", "STARTED", 1_000, 10, null,
            java.util.List.of(), java.util.List.of());
        var json = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(DirectorDecisions.analysisRequest(null, snapshot));
        assertTrue(json.has("base"));
        assertTrue(json.get("base").isNull());
        assertEquals("race-1", json.get("current").get("raceId").asText());
    }

    @Test
    void theThemeRequestCarriesTheEventAndAudience() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(DirectorDecisions.themeRequest("race-1", "classic", "a seafood festival", "a room of chefs"));
        assertEquals("race-1", json.get("raceId").asText());
        assertEquals("classic", json.get("currentTheme").asText());
        assertEquals("a seafood festival", json.get("event").asText());
        assertEquals("a room of chefs", json.get("audience").asText());
    }
}
