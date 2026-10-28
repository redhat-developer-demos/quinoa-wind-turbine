package org.raceops.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import io.quarkiverse.langchain4j.mcp.runtime.McpClientName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
        return call("boost_team", Map.of("team", team, "multiplier", multiplier, "seconds", seconds));
    }

    public String suggestTeamNames(String prompt) {
        return call("suggest_team_names", Map.of("prompt", prompt));
    }

    public String applyPresetTheme(String presetId) {
        return call("apply_theme", Map.of("presetId", presetId));
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
