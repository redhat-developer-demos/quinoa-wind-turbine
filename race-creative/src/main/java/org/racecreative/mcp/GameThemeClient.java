package org.racecreative.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import io.quarkiverse.langchain4j.mcp.runtime.McpClientName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.racecreative.theme.ThemeGateway;
import org.racecreative.theme.ThemeOption;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The game's admin theme tools, called programmatically behind the OIDC admin scope: the
 * same client shape race-ops uses, trimmed to the three theme tools the creative director
 * needs. The game applies changes itself and broadcasts them, so nothing here touches state
 * beyond these calls.
 */
@ApplicationScoped
public class GameThemeClient implements ThemeGateway {

    private final McpClient admin;
    private final ObjectMapper mapper;

    @Inject
    public GameThemeClient(@McpClientName("race-admin") McpClient admin, ObjectMapper mapper) {
        this.admin = admin;
        this.mapper = mapper;
    }

    /**
     * The presets, parsed from the admin tool's answer. The server sends one text content per
     * theme and the client joins them with newlines, so parse line by line — reading the joined
     * text as one JSON document would return only the first theme.
     */
    @Override
    public List<ThemeOption> listThemes() {
        try {
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

    @Override
    public String suggestNames(String brief) {
        return call("suggest_team_names", Map.of("prompt", brief));
    }

    @Override
    public String generateTheme(String brief) {
        return call("generate_theme", Map.of("prompt", brief));
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

    private String execute(String tool, String arguments) {
        try {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id(UUID.randomUUID().toString())
                .name(tool)
                .arguments(arguments)
                .build();
            return admin.executeTool(request).resultText();
        } catch (Exception e) {
            throw new IllegalStateException("MCP tool " + tool + " failed: " + e.getMessage(), e);
        }
    }
}
