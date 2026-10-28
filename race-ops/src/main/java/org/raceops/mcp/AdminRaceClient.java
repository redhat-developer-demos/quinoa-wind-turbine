package org.raceops.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import io.quarkiverse.langchain4j.mcp.runtime.McpClientName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The director's hands: the admin toolset of the game's MCP server (start, pause, reset,
 * boost, themes), called programmatically. Every power this app has over the game goes
 * through here, behind the OIDC admin scope.
 */
@ApplicationScoped
public class AdminRaceClient {

    private final McpClient admin;
    private final ObjectMapper mapper;

    @Inject
    public AdminRaceClient(@McpClientName("race-admin") McpClient admin, ObjectMapper mapper) {
        this.admin = admin;
        this.mapper = mapper;
    }

    public String start() {
        return call("start_race", Map.of());
    }

    public String pause() {
        return call("pause_race", Map.of());
    }

    public String reset() {
        return call("reset_race", Map.of());
    }

    /**
     * @param team team number, starting at 1
     * @param multiplier above 1 boosts, below 1 sabotages
     * @param seconds how long the effect lasts, null for the configured default
     */
    public String boost(int team, double multiplier, Integer seconds) {
        Map<String, Object> arguments = new java.util.HashMap<>();
        arguments.put("team", team);
        arguments.put("multiplier", multiplier);
        if (seconds != null) {
            arguments.put("seconds", seconds);
        }
        return call("boost_team", arguments);
    }

    public String suggestTeamNames(String prompt) {
        return call("suggest_team_names", Map.of("prompt", prompt));
    }

    public String applyPresetTheme(String presetId) {
        return call("apply_theme", Map.of("presetId", presetId));
    }

    /**
     * The theme presets the game offers, parsed from the admin tool's answer. The server
     * sends one text content per theme, so the full list comes from resultContents(),
     * not resultText() (which only carries a single content item). Used to ground the
     * director's theme suggestions: the model picks from the real list.
     */
    public record ThemeOption(String id, String label) {
    }

    public List<ThemeOption> listThemes() {
        try {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id(UUID.randomUUID().toString())
                .name("list_preset_themes")
                .arguments(mapper.writeValueAsString(Map.of()))
                .build();
            // The server sends one text content per theme and the client joins them with
            // newlines into a single result, so parse line by line — readTree on the
            // joined text would silently return only the first theme.
            List<ThemeOption> options = new ArrayList<>();
            for (String line : admin.executeTool(request).resultText().split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                var theme = mapper.readTree(line);
                if (theme.hasNonNull("id")) {
                    options.add(new ThemeOption(theme.get("id").asText(),
                        theme.path("name").asText(theme.get("id").asText())));
                }
            }
            if (options.isEmpty()) {
                throw new IllegalStateException("list_preset_themes returned no themes");
            }
            return options;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("MCP tool list_preset_themes failed: " + e.getMessage(), e);
        }
    }

    private String call(String tool, Map<String, Object> arguments) {
        try {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id(UUID.randomUUID().toString())
                .name(tool)
                .arguments(mapper.writeValueAsString(arguments))
                .build();
            return admin.executeTool(request).resultText();
        } catch (Exception e) {
            throw new IllegalStateException("MCP tool " + tool + " failed: " + e.getMessage(), e);
        }
    }
}
