package org.racecreative.creative;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.racecreative.theme.ThemeGateway;
import org.racecreative.theme.ThemeOption;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The one thing the creative model may do on its own: read the game's theme list. That is
 * enough to propose a real preset, and it is safe before approval. The two tools that apply
 * changes are deliberately absent — they are reached only through
 * {@link CreativeService}'s approved-execution path.
 */
@ApplicationScoped
public class ThemeTools {

    private final ThemeGateway gateway;

    @Inject
    public ThemeTools(ThemeGateway gateway) {
        this.gateway = gateway;
    }

    @Tool("Lists the game's built-in race themes with their ids and labels. Use it to propose a "
        + "real preset instead of inventing one.")
    public String listThemes() {
        List<ThemeOption> options = gateway.listThemes();
        return options.stream()
            .map(o -> "{\"id\":\"%s\",\"label\":\"%s\"}".formatted(o.id(), o.label()))
            .collect(Collectors.joining(",", "[", "]"));
    }
}
