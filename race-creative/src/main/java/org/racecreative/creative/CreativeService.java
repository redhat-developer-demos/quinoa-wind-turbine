package org.racecreative.creative;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.racecreative.theme.ThemeGateway;
import org.racecreative.theme.ThemeOption;

import java.util.List;

/**
 * The creative director's one operation, in two modes:
 *
 * <ul>
 *   <li>{@code propose} runs between heats. With a model, the creative agent picks a preset or
 *       shapes an AI brief; without one (or when the call fails), a deterministic preset pick
 *       still comes back. Neither path changes the game.</li>
 *   <li>{@code apply} runs only after the operator approves a card. It executes the brief
 *       through the game's own {@code suggest_team_names} / {@code generate_theme}, which apply
 *       the theme inside the game immediately.</li>
 * </ul>
 *
 * The split is what keeps the stage reliable: the model proposes, the operator decides, and
 * an approved change runs as plain code with no second model call in the critical path.
 */
@ApplicationScoped
public class CreativeService {

    private final ObjectMapper mapper;
    private final ThemeGateway gateway;
    private final CreativeAgent agent;
    private final boolean aiEnabled;

    @Inject
    public CreativeService(ObjectMapper mapper, ThemeGateway gateway, CreativeAgent agent,
                           @ConfigProperty(name = "race-creative.ai-enabled", defaultValue = "false")
                           boolean aiEnabled) {
        this.mapper = mapper;
        this.gateway = gateway;
        this.agent = agent;
        this.aiEnabled = aiEnabled;
    }

    /**
     * @param requestJson {@code {"mode":"propose","racedId":..,"currentTheme":..,"event":..,"audience":..}}
     *                    or {@code {"mode":"apply","kind":"ai-theme"|"ai-names","brief":".."}}.
     *                    A plain sentence is read as a propose request: an agent-to-agent caller
     *                    often asks in words, and only apply changes the game, so prose can never
     *                    reach the write tools.
     * @return a {@link CreativeProposal} as JSON
     */
    public String handle(String requestJson) {
        if (requestJson == null || requestJson.isBlank()) {
            throw new IllegalArgumentException("The creative request is empty");
        }
        Request request;
        try {
            request = mapper.readValue(requestJson, Request.class);
        } catch (JsonProcessingException e) {
            Log.debugf("The creative request is not JSON (%s); reading it as a propose brief",
                e.getOriginalMessage());
            request = new Request("propose", null, null, requestJson == null ? "" : requestJson.trim(),
                null, null, null);
        }
        if (request == null || request.mode() == null || request.mode().isBlank()) {
            throw new IllegalArgumentException("The creative request needs a mode");
        }
        return switch (request.mode()) {
            case "propose" -> json(propose(request));
            case "apply" -> json(apply(request));
            default -> throw new IllegalArgumentException("Unknown creative mode " + request.mode());
        };
    }

    private CreativeProposal propose(Request request) {
        List<ThemeOption> options = gateway.listThemes();
        String current = request.currentTheme() == null ? "" : request.currentTheme();
        if (aiEnabled && agent != null) {
            try {
                return CreativeProposals.parse(agent.propose(prompt(request, options)), options, current);
            } catch (RuntimeException e) {
                Log.warnf("The creative model proposed nothing usable (%s); falling back to a preset",
                    e.getMessage());
            }
        }
        return CreativeProposals.fallback(options, current);
    }

    private CreativeProposal apply(Request request) {
        String kind = request.kind();
        if (CreativeProposal.PRESET.equals(kind)) {
            throw new IllegalArgumentException("Preset themes are applied by the caller, not the creative agent");
        }
        String brief = request.brief();
        if (kind == null || brief == null || brief.isBlank()) {
            throw new IllegalArgumentException("An apply request needs a kind and a brief");
        }
        String applied = switch (kind) {
            case CreativeProposal.AI_NAMES -> gateway.suggestNames(brief);
            case CreativeProposal.AI_THEME -> gateway.generateTheme(brief);
            default -> throw new IllegalArgumentException("Unknown proposal kind " + kind);
        };
        return new CreativeProposal(kind, null, describe(applied, kind), brief);
    }

    private String prompt(Request request, List<ThemeOption> options) {
        String presets = options.stream()
            .map(o -> "{\"id\":\"%s\",\"label\":\"%s\"}".formatted(o.id(), o.label()))
            .reduce((a, b) -> a + "," + b).orElse("");
        return """
            {"raceId":"%s","currentTheme":"%s","event":"%s","audience":"%s","presets":[%s]}""".formatted(
            nullToEmpty(request.raceId()), nullToEmpty(request.currentTheme()),
            nullToEmpty(request.event()), nullToEmpty(request.audience()), presets);
    }

    /**
     * The game answers an applied theme with its full JSON. The console card only has room for
     * a line, so name the two teams when the answer carries them and stay generic otherwise.
     */
    private String describe(String applied, String kind) {
        try {
            JsonNode theme = mapper.readTree(applied);
            JsonNode teams = theme.path("teams");
            if (teams.isArray() && teams.size() == 2
                && teams.get(0).hasNonNull("name") && teams.get(1).hasNonNull("name")) {
                return "Applied: %s vs %s.".formatted(
                    teams.get(0).get("name").asText(), teams.get(1).get("name").asText());
            }
        } catch (JsonProcessingException | RuntimeException e) {
            Log.debugf("The applied theme answer is not JSON: %s", e.getMessage());
        }
        return CreativeProposal.AI_THEME.equals(kind)
            ? "Applied the generated theme." : "Applied the generated team names.";
    }

    private String json(CreativeProposal proposal) {
        try {
            return mapper.writeValueAsString(proposal);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize the proposal: " + e.getOriginalMessage(), e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Request(String mode, String raceId, String currentTheme, String event, String audience,
                          String kind, String brief) {
    }
}
