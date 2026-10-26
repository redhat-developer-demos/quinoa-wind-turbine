package org.acme.theme;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.game.GameResource.GameEvent;
import org.acme.theme.RaceTheme.ThemeTeam;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.acme.game.GameConfig;
import org.acme.game.GameResource;

/**
 * Holds the active theme and applies new ones. An applied theme is broadcast as a theme game event,
 * so every replica updates its own copy from the event and all connected clients switch together.
 * The current theme lives in memory and is seeded from the configured teams on startup; a pod that
 * starts after a theme change shows the default until the next theme event (see the roadmap note on
 * late-joining pods).
 */
@ApplicationScoped
@Startup
public class ThemeService {

    private static final Logger LOG = Logger.getLogger(ThemeService.class);

    private final AtomicReference<RaceTheme> current = new AtomicReference<>();
    private final GameResource game;
    private final ThemeValidator validator;
    private final PresetThemes presets;
    private final ObjectMapper mapper;
    private final GameConfig config;
    private final ThemeGeneratorAi generator;

    public ThemeService(GameResource game, ThemeValidator validator, PresetThemes presets,
                        ObjectMapper mapper, GameConfig config, ThemeGeneratorAi generator) {
        this.game = game;
        this.validator = validator;
        this.presets = presets;
        this.mapper = mapper;
        this.config = config;
        this.generator = generator;
        this.current.set(defaultTheme());
    }

    @PostConstruct
    void subscribe() {
        game.updates().subscribe().with(event -> {
            if (!GameEvent.THEME.equals(event.type()) || event.data() == null) {
                return;
            }
            Object theme = event.data().get("theme");
            if (theme != null) {
                try {
                    current.set(mapper.convertValue(theme, RaceTheme.class));
                } catch (RuntimeException e) {
                    LOG.error("Cannot read theme from event", e);
                }
            }
        });
    }

    public RaceTheme current() {
        return current.get();
    }

    /** The active theme's name for a team, so commentary and the UI agree. Falls back to the config name. */
    public String teamName(int id) {
        RaceTheme theme = current.get();
        if (theme != null && theme.teams() != null) {
            for (RaceTheme.ThemeTeam team : theme.teams()) {
                if (team.id() == id) {
                    return team.name();
                }
            }
        }
        return config.teamName(id);
    }

    public List<RaceTheme> presets() {
        return presets.all();
    }

    /**
     * Validates and applies a theme. Switching is only allowed between races, so a restyle never
     * lands mid-race. On success it broadcasts the theme to every replica and client and returns
     * an empty list; otherwise it returns the validation errors and changes nothing.
     */
    public List<String> apply(RaceTheme theme) {
        List<String> errors = validator.validate(theme, config.teams().size());
        if (!errors.isEmpty()) {
            return errors;
        }
        String status = game.status().type();
        if (GameEvent.START.equals(status) || GameEvent.PAUSE.equals(status)) {
            return List.of("Themes can only be applied between races");
        }
        game.sendTheme(theme);
        return List.of();
    }

    /**
     * Generates a full theme from a prompt. It throws when the model output cannot be read at all;
     * the result still goes through {@link #apply(RaceTheme)}, so a bad theme keeps the current one.
     */
    public RaceTheme generate(String prompt) {
        ThemeGeneratorAi.GeneratedTheme generated = generator.theme(prompt);
        List<ThemeTeam> teams = generated.teams().stream()
            .map(t -> new ThemeTeam(t.id(), t.name(), t.color(), t.car(), null))
            .toList();
        return new RaceTheme("ai", generated.name(), teams, null);
    }

    /**
     * The light AI path: suggests two team names for an event or location and keeps the current
     * colors, cars and track, so the known look stays and only the names change.
     */
    public RaceTheme suggestNames(String prompt) {
        ThemeGeneratorAi.TeamNames names = generator.suggestNames(prompt);
        List<ThemeTeam> teams = current().teams().stream()
            .map(t -> new ThemeTeam(t.id(), t.id() == 1 ? names.team1() : names.team2(),
                t.color(), t.car(), t.accent(), t.logo()))
            .toList();
        return new RaceTheme("custom", "Custom", teams, null);
    }

    private RaceTheme defaultTheme() {
        List<ThemeTeam> teams = config.teams().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> new ThemeTeam(e.getKey(), e.getValue().name(), e.getValue().color(), e.getValue().car(), null))
            .toList();
        return new RaceTheme("default", "Default", teams, null);
    }
}
