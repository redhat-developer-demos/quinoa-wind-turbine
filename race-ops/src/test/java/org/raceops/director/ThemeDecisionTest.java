package org.raceops.director;

import org.junit.jupiter.api.Test;
import org.raceops.mcp.AdminRaceClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeDecisionTest {

    private static final List<AdminRaceClient.ThemeOption> OPTIONS = List.of(
        new AdminRaceClient.ThemeOption("classic", "Mountains vs Beach"),
        new AdminRaceClient.ThemeOption("atlanta", "Aquarium vs Coca-Cola"),
        new AdminRaceClient.ThemeOption("fire-ice", "Fire vs Ice"));

    @Test
    void theFallbackSkipsTheDefaultAndRotates() {
        Proposal first = DirectorDecisions.themeFallback(OPTIONS, "race-1", 0, "classic");
        Proposal second = DirectorDecisions.themeFallback(OPTIONS, "race-2", 1, "classic");
        assertNotEquals("classic", first.presetId());
        assertNotEquals("classic", second.presetId());
        assertNotEquals(first.presetId(), second.presetId());
        assertTrue(first.message().contains("Aquarium") || first.message().contains("Fire"),
            first.message());
    }

    @Test
    void theFallbackSkipsTheCurrentTheme() {
        Proposal proposal = DirectorDecisions.themeFallback(OPTIONS, "race-1", 0, "atlanta");
        assertNotEquals("atlanta", proposal.presetId());
        assertNotEquals("classic", proposal.presetId());
    }

    @Test
    void anUnknownPresetIdFallsBack() {
        Proposal fallback = DirectorDecisions.themeFallback(OPTIONS, "race-1", 0, "classic");
        // The model invents a preset the game does not offer.
        Proposal proposal = DirectorDecisions.theme(
            new DirectorDecisions.ThemeAdvice("preset", "disneyland", null, "Let's go!"),
            OPTIONS, "race-1", "classic", fallback);
        assertEquals(fallback.presetId(), proposal.presetId());
    }

    @Test
    void theCurrentThemeIsRejectedForAVisibleChange() {
        Proposal fallback = DirectorDecisions.themeFallback(OPTIONS, "race-1", 0, "classic");
        Proposal proposal = DirectorDecisions.theme(
            new DirectorDecisions.ThemeAdvice("preset", "atlanta", null, "Same again!"),
            OPTIONS, "race-1", "atlanta", fallback);
        assertEquals(fallback.presetId(), proposal.presetId());
    }

    @Test
    void aKnownPresetIdIsKeptWithItsMessage() {
        Proposal proposal = DirectorDecisions.theme(
            new DirectorDecisions.ThemeAdvice("preset", "fire-ice", null, "Turn up the heat for the rematch."),
            OPTIONS, "race-1", "classic",
            DirectorDecisions.themeFallback(OPTIONS, "race-1", 0, "classic"));
        assertEquals(Proposal.THEME, proposal.type());
        assertEquals("fire-ice", proposal.presetId());
        assertEquals("Turn up the heat for the rematch.", proposal.message());
    }

    @Test
    void aBlankModelMessageKeepsTheFallbackMessage() {
        Proposal fallback = DirectorDecisions.themeFallback(OPTIONS, "race-1", 0, "classic");
        Proposal proposal = DirectorDecisions.theme(
            new DirectorDecisions.ThemeAdvice("preset", "atlanta", null, "  "),
            OPTIONS, "race-1", "classic", fallback);
        assertEquals("atlanta", proposal.presetId());
        assertEquals(fallback.message(), proposal.message());
    }

    @Test
    void anAiBriefBecomesAnExecutableProposal() {
        Proposal proposal = DirectorDecisions.theme(
            new DirectorDecisions.ThemeAdvice("ai-theme", null, "a retro arcade tournament", "Rockets for the next heat."),
            OPTIONS, "race-1", "classic", DirectorDecisions.themeFallback(OPTIONS, "race-1", 0, "classic"));
        assertEquals(Proposal.THEME_AI, proposal.type());
        assertEquals("ai-theme", proposal.kind());
        assertEquals("a retro arcade tournament", proposal.brief());
        assertEquals("Rockets for the next heat.", proposal.message());
    }

    @Test
    void anAiProposalWithoutABriefFallsBack() {
        Proposal fallback = DirectorDecisions.themeFallback(OPTIONS, "race-1", 0, "classic");
        Proposal proposal = DirectorDecisions.theme(
            new DirectorDecisions.ThemeAdvice("ai-names", null, "  ", "Names!"),
            OPTIONS, "race-1", "classic", fallback);
        assertEquals(fallback.presetId(), proposal.presetId());
    }

    @Test
    void theCreativeAnswerIsReadEvenInsideFencesAndProse() {
        DirectorDecisions.ThemeAdvice advice = DirectorDecisions.parseThemeAdvice("""
            Sure, here is the proposal:
            ```json
            {"kind":"ai-names","brief":"a seafood festival in Lisbon","message":"Lisbon for the next heat."}
            ```
            """);
        assertEquals("ai-names", advice.kind());
        assertEquals("a seafood festival in Lisbon", advice.brief());
    }

    @Test
    void aCreativeAnswerWithoutJsonIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> DirectorDecisions.parseThemeAdvice("I would paint it blue."));
    }

    @Test
    void theThemeRequestNamesTheProposeMode() {
        // race-creative serves propose and apply on one operation; without the mode every
        // proposal came back as an error and the director silently used a preset.
        String request = DirectorDecisions.themeRequest("race-1", "classic", "a conference",
            "a room of developers");
        assertTrue(request.contains("\"mode\":\"propose\""), request);
        assertTrue(request.contains("\"raceId\":\"race-1\""), request);
    }
}
