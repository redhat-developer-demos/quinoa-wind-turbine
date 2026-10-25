package org.acme.commentary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;

import java.util.Map;
import org.acme.game.GameConfig;
import org.acme.race.RaceState;
import org.acme.theme.ThemeService;

@ApplicationScoped
public class AiCommentator implements Commentator {
    private final RaceCommentaryAi ai;
    private final ObjectMapper mapper;
    private final int maxLength;
    private final GameConfig config;
    private final ThemeService themes;

    public AiCommentator(RaceCommentaryAi ai, ObjectMapper mapper, GameConfig config, ThemeService themes) {
        this.ai = ai;
        this.mapper = mapper;
        this.maxLength = config.commentary().maxLength();
        this.config = config;
        this.themes = themes;
    }

    @Override
    @ActivateRequestContext
    public String comment(CommentaryTrigger trigger, RaceState race) {
        try {
            // Bound the prompt independently of the number of phones in the race.
            // Distances are rounded because the model echoes the precision it receives: raw doubles
            // from the race math (74.5111111111111) showed up verbatim in generated lines.
            return ai.comment(mapper.writeValueAsString(Map.of("event", trigger,
                "elapsedMs", race.elapsedMs(), "teams", race.teams().stream()
                    .map(t -> new RaceState.TeamState(t.id(), t.position(), t.players(), t.power(),
                        Math.round(t.distance() * 10) / 10.0, t.finishTimeMs()))
                    .toList(),
                "teamNames", config.teams().keySet().stream().collect(java.util.stream.Collectors.toMap(id -> id, themes::teamName)),
                "players", race.players().stream()
                    .filter(p -> p.name().equals(trigger.player())).toList())), maxLength);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize commentary facts", e);
        }
    }
}
