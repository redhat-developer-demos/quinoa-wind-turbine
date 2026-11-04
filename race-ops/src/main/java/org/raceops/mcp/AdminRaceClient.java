package org.raceops.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import io.quarkiverse.langchain4j.mcp.runtime.McpClientName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.raceops.metrics.DirectorMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The director's hands: the admin toolset of the game's MCP server (start, pause, reset,
 * boost, themes), called programmatically. Every power this app has over the game goes
 * through here, behind the OIDC admin scope. Every call is timed per tool, so the metrics
 * show which tool is slow or failing.
 */
@ApplicationScoped
public class AdminRaceClient {

    private final McpClient admin;
    private final ObjectMapper mapper;
    private final DirectorMetrics metrics;

    @Inject
    public AdminRaceClient(@McpClientName("race-admin") McpClient admin, ObjectMapper mapper,
                           DirectorMetrics metrics) {
        this.admin = admin;
        this.mapper = mapper;
        this.metrics = metrics;
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
            // The server sends one text content per theme and the client joins them with
            // newlines into a single result, so parse line by line — readTree on the
            // joined text would silently return only the first theme.
            List<ThemeOption> options = new ArrayList<>();
            for (String line : execute("list_preset_themes", "{}").split("\\R")) {
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
            return execute(tool, mapper.writeValueAsString(arguments));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("MCP tool " + tool + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * Runs one tool call and times it with the tool name and whether it worked, so the
     * metrics separate a failing boost from a failing token exchange.
     */
    private String execute(String tool, String arguments) {
        long started = System.nanoTime();
        String outcome = "success";
        try {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id(UUID.randomUUID().toString())
                .name(tool)
                .arguments(arguments)
                .build();
            return admin.executeTool(request).resultText();
        } catch (Exception e) {
            outcome = "error";
            throw new IllegalStateException("MCP tool " + tool + " failed: " + e.getMessage(), e);
        } finally {
            metrics.mcpCall(tool, outcome, System.nanoTime() - started);
        }
    }
}
