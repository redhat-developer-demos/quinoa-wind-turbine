package org.acme;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.GameResource.GameEvent;
import org.acme.RaceTheme.ThemeTeam;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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

    public ThemeService(GameResource game, ThemeValidator validator, PresetThemes presets,
                        ObjectMapper mapper, GameConfig config) {
        this.game = game;
        this.validator = validator;
        this.presets = presets;
        this.mapper = mapper;
        this.config = config;
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
     * Validates and applies a theme. On success it broadcasts the theme to every replica and client
     * and returns an empty list; otherwise it returns the validation errors and changes nothing.
     */
    public List<String> apply(RaceTheme theme) {
        List<String> errors = validator.validate(theme, config.teams().size());
        if (!errors.isEmpty()) {
            return errors;
        }
        game.sendTheme(theme);
        return List.of();
    }

    private RaceTheme defaultTheme() {
        List<ThemeTeam> teams = config.teams().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> new ThemeTeam(e.getKey(), e.getValue().name(), e.getValue().color(), e.getValue().car(), null))
            .toList();
        return new RaceTheme("default", "Default", teams);
    }
}
