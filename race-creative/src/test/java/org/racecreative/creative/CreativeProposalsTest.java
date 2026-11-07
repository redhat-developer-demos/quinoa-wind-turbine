package org.racecreative.creative;

import org.junit.jupiter.api.Test;
import org.racecreative.theme.ThemeOption;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model's answer is untrusted input. These cases are the guardrail: a fenced answer is
 * fine, a hallucinated preset or a brief-less AI proposal is not, and the fallback still
 * produces something the operator can approve.
 */
class CreativeProposalsTest {

    private static final List<ThemeOption> PRESETS = List.of(
        new ThemeOption("classic", "Mountains vs Beach"),
        new ThemeOption("atlanta", "Aquarium vs Coca-Cola"),
        new ThemeOption("fire-ice", "Fire vs Ice"));

    @Test
    void aBarePresetAnswerIsAccepted() {
        CreativeProposal proposal = CreativeProposals.parse(
            "{\"kind\":\"preset\",\"presetId\":\"atlanta\",\"message\":\"Aquarium for the next heat?\"}",
            PRESETS, "classic");
        assertEquals(CreativeProposal.PRESET, proposal.kind());
        assertEquals("atlanta", proposal.presetId());
        assertEquals("Aquarium for the next heat?", proposal.message());
    }

    @Test
    void anAnswerInsideAFenceOrProseIsTolerated() {
        CreativeProposal proposal = CreativeProposals.parse("""
            Sure, here it is:
            ```json
            {"kind":"ai-theme","brief":"a space expo for kids","message":"Rockets for the next heat."}
            ```
            """, PRESETS, "classic");
        assertEquals(CreativeProposal.AI_THEME, proposal.kind());
        assertEquals("a space expo for kids", proposal.brief());
    }

    @Test
    void aHallucinatedPresetIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CreativeProposals.parse(
            "{\"kind\":\"preset\",\"presetId\":\"neon-pastel\"}", PRESETS, "classic"));
    }

    @Test
    void theCurrentThemeIsNotAProposal() {
        assertThrows(IllegalArgumentException.class, () -> CreativeProposals.parse(
            "{\"kind\":\"preset\",\"presetId\":\"classic\"}", PRESETS, "classic"));
    }

    @Test
    void anAiProposalNeedsABrief() {
        assertThrows(IllegalArgumentException.class, () -> CreativeProposals.parse(
            "{\"kind\":\"ai-theme\",\"message\":\"Something new?\"}", PRESETS, "classic"));
    }

    @Test
    void anUnknownKindIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> CreativeProposals.parse("{\"kind\":\"sabotage\"}", PRESETS, "classic"));
        assertTrue(error.getMessage().contains("Unknown proposal kind"), error.getMessage());
    }

    @Test
    void theFallbackSkipsTheDefaultAndTheCurrentTheme() {
        CreativeProposal proposal = CreativeProposals.fallback(PRESETS, "atlanta");
        assertEquals("fire-ice", proposal.presetId());
        assertTrue(proposal.message().contains("Fire vs Ice"), proposal.message());
    }

    @Test
    void theFallbackStillAnswersWhenOnlyTheDefaultIsLeft() {
        CreativeProposal proposal = CreativeProposals.fallback(
            List.of(new ThemeOption("classic", "Mountains vs Beach")), "classic");
        assertEquals("classic", proposal.presetId());
    }
}
