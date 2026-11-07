package org.racecreative.creative;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.racecreative.theme.ThemeOption;

import java.util.List;

/**
 * The guardrails around the creative model, kept as pure code: parse the answer, accept only
 * a preset the game really offers, and fall back to a deterministic pick. The model never
 * gets the last word, so a hallucinated preset id cannot reach the console.
 */
public final class CreativeProposals {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CreativeProposals() {
    }

    /**
     * @param raw      the model's answer, ideally bare JSON but tolerated inside fences or prose
     * @param options  the game's real presets
     * @param current  the theme currently on; proposing it again is useless
     * @throws IllegalArgumentException when the answer names an unknown preset or has no brief
     */
    public static CreativeProposal parse(String raw, List<ThemeOption> options, String current) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("The model returned no proposal");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("The model returned no JSON object");
        }
        JsonNode answer;
        try {
            answer = MAPPER.readTree(raw.substring(start, end + 1));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot parse the model's proposal: " + e.getOriginalMessage(), e);
        }
        String kind = answer.path("kind").asText("");
        String message = answer.path("message").asText("");
        String brief = answer.path("brief").asText("");
        switch (kind) {
            case CreativeProposal.PRESET -> {
                String presetId = answer.path("presetId").asText("");
                boolean known = options.stream().anyMatch(o -> o.id().equals(presetId));
                if (!known || presetId.equals(current)) {
                    throw new IllegalArgumentException("The model proposed an unknown or current preset: "
                        + presetId);
                }
                if (message.isBlank()) {
                    message = label(options, presetId) + " for the next heat?";
                }
                return CreativeProposal.preset(presetId, message);
            }
            case CreativeProposal.AI_THEME, CreativeProposal.AI_NAMES -> {
                if (brief.isBlank()) {
                    throw new IllegalArgumentException("The model proposed " + kind + " without a brief");
                }
                if (message.isBlank()) {
                    message = CreativeProposal.AI_THEME.equals(kind)
                        ? "A generated theme: " + brief + "?"
                        : "Generated team names: " + brief + "?";
                }
                return CreativeProposal.ai(kind, brief, message);
            }
            default -> throw new IllegalArgumentException("Unknown proposal kind: " + kind);
        }
    }

    /**
     * The deterministic between-heats pick used when the model is off or fails: skip the
     * default and the theme already on, so consecutive heats look different.
     */
    public static CreativeProposal fallback(List<ThemeOption> options, String current) {
        List<ThemeOption> usable = options.stream()
            .filter(o -> !"classic".equals(o.id()) && !o.id().equals(current))
            .toList();
        if (usable.isEmpty()) {
            usable = options;
        }
        ThemeOption pick = usable.getFirst();
        return CreativeProposal.preset(pick.id(),
            "Next heat: %s? Fresh colors and cars for the teams.".formatted(pick.label()));
    }

    private static String label(List<ThemeOption> options, String id) {
        return options.stream().filter(o -> o.id().equals(id))
            .map(ThemeOption::label).findFirst().orElse(id);
    }
}
