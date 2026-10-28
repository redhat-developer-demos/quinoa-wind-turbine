package org.raceops.director;

import org.junit.jupiter.api.Test;
import org.raceops.mcp.AdminRaceClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
            new DirectorAi.ThemeAdvice("disneyland", "Let's go!"), OPTIONS, "race-1", "classic", fallback);
        assertEquals(fallback.presetId(), proposal.presetId());
    }

    @Test
    void theCurrentThemeIsRejectedForAVisibleChange() {
        Proposal fallback = DirectorDecisions.themeFallback(OPTIONS, "race-1", 0, "classic");
        Proposal proposal = DirectorDecisions.theme(
            new DirectorAi.ThemeAdvice("atlanta", "Same again!"), OPTIONS, "race-1", "atlanta", fallback);
        assertEquals(fallback.presetId(), proposal.presetId());
    }

    @Test
    void aKnownPresetIdIsKeptWithItsMessage() {
        Proposal proposal = DirectorDecisions.theme(
            new DirectorAi.ThemeAdvice("fire-ice", "Turn up the heat for the rematch."), OPTIONS, "race-1", "classic",
            DirectorDecisions.themeFallback(OPTIONS, "race-1", 0, "classic"));
        assertEquals("fire-ice", proposal.presetId());
        assertEquals("Turn up the heat for the rematch.", proposal.message());
    }

    @Test
    void aBlankModelMessageKeepsTheFallbackMessage() {
        Proposal fallback = DirectorDecisions.themeFallback(OPTIONS, "race-1", 0, "classic");
        Proposal proposal = DirectorDecisions.theme(
            new DirectorAi.ThemeAdvice("atlanta", "  "), OPTIONS, "race-1", "classic", fallback);
        assertEquals("atlanta", proposal.presetId());
        assertEquals(fallback.message(), proposal.message());
    }
}
