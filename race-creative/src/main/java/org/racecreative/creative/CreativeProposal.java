package org.racecreative.creative;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What the creative director answers. One shape for both modes:
 *
 * <ul>
 *   <li>{@code preset}: a built-in theme the operator can preview; race-ops applies it
 *       through {@code apply_theme} after approval.</li>
 *   <li>{@code ai-theme} / {@code ai-names}: a brief for the game's own AI generation. The
 *       card carries the brief, and the generation runs only after approval.</li>
 * </ul>
 *
 * @param kind one of {@link #PRESET}, {@link #AI_THEME}, {@link #AI_NAMES}
 * @param presetId the preset to apply, for a preset proposal
 * @param message the sentence shown on the operator's card
 * @param brief what to generate, for an AI proposal or an executed AI change
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreativeProposal(String kind, String presetId, String message, String brief) {

    public static final String PRESET = "preset";
    public static final String AI_THEME = "ai-theme";
    public static final String AI_NAMES = "ai-names";

    public static CreativeProposal preset(String presetId, String message) {
        return new CreativeProposal(PRESET, presetId, message, null);
    }

    public static CreativeProposal ai(String kind, String brief, String message) {
        return new CreativeProposal(kind, null, message, brief);
    }
}
