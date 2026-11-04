package org.acme.commentary;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.game.GameConfig;
import org.acme.game.GameResource;
import org.acme.game.GameResource.GameEvent;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the active commentary style and applies new ones. An applied style is broadcast as a
 * commentary-style game event, so every replica updates its own copy from the event. The active
 * style lives in memory and is seeded from configuration on startup, like the theme: only the pod
 * that owns the commentary partition generates lines, and the broadcast reaches it.
 *
 * Switching mid-race would change the voice between sentences of the same heat, so it is limited
 * to the gap between races, the same rule as themes and the input mode.
 */
@ApplicationScoped
@Startup
public class CommentaryStyleService {

    private static final Logger LOG = Logger.getLogger(CommentaryStyleService.class);

    private final AtomicReference<CommentaryStyle> current = new AtomicReference<>();
    private final GameResource game;
    private final GameConfig config;

    public CommentaryStyleService(GameResource game, GameConfig config) {
        this.game = game;
        this.config = config;
        this.current.set(defaultStyle());
    }

    @PostConstruct
    void subscribe() {
        game.updates().subscribe().with(event -> {
            if (!GameEvent.COMMENTARY_STYLE.equals(event.type()) || event.data() == null) {
                return;
            }
            Object style = event.data().get("style");
            if (style != null) {
                CommentaryStyle.find(String.valueOf(style)).ifPresentOrElse(current::set,
                    () -> LOG.errorf("Cannot read commentary style from event: %s", style));
            }
        });
    }

    public CommentaryStyle current() {
        return current.get();
    }

    public CommentaryStyle defaultStyle() {
        return CommentaryStyle.find(config.commentary().style()).orElse(CommentaryStyle.BROADCAST);
    }

    /**
     * Validates and applies a style string. Returns an error message list if rejected, or empty on
     * success.
     */
    public List<String> apply(String style) {
        var parsed = CommentaryStyle.find(style);
        if (parsed.isEmpty()) {
            return List.of("Unknown commentary style '" + style + "'; expected broadcast, hype, analyst or dry");
        }
        return apply(parsed.get());
    }

    /**
     * Applies a style between races, writes it for this replica and broadcasts it to the others.
     */
    public List<String> apply(CommentaryStyle style) {
        if (style == null) {
            return List.of("A commentary style is required; expected broadcast, hype, analyst or dry");
        }
        String status = game.status().type();
        if (GameEvent.START.equals(status) || GameEvent.PAUSE.equals(status)) {
            return List.of("The commentary style can only be changed between races");
        }
        current.set(style);
        game.sendCommentaryStyle(style.value());
        LOG.infof("Commentary style set to %s", style.value());
        return List.of();
    }

    /**
     * Reverts this replica to the configured default without broadcasting.
     */
    public void reset() {
        current.set(defaultStyle());
    }

    /**
     * The styles a client can choose, with the prompt fragment each one applies.
     */
    public List<Map<String, String>> describe() {
        return java.util.Arrays.stream(CommentaryStyle.values())
            .map(style -> Map.of("id", style.value(), "guidance", style.guidance()))
            .toList();
    }
}
