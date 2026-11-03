package org.acme.input;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.game.GameConfig;
import org.acme.game.GameResource;
import org.acme.game.GameResource.GameEvent;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.commons.configuration.StringConfiguration;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Manages the active player input mode (tap, shake, or both). The active mode is persisted in
 * Infinispan so it survives restarts and stays consistent across replicas. Changes are broadcast
 * as an input-mode game event so all connected dashboards and phones update.
 */
@ApplicationScoped
@Startup
public class InputModeService {

    static final String CACHE = "input-mode";
    private static final String KEY = "mode";
    private static final Logger LOG = Logger.getLogger(InputModeService.class);

    private static final String PERSISTENT_CONFIG = """
        <distributed-cache>
          <persistence passivation="false">
            <file-store preload="true"/>
          </persistence>
        </distributed-cache>""";
    private static final String MEMORY_CONFIG = "<distributed-cache/>";

    private final RemoteCache<String, String> cache;
    private final GameConfig config;
    private final GameResource game;

    public InputModeService(RemoteCacheManager cacheManager, GameConfig config, GameResource game) {
        this.config = config;
        this.game = game;
        this.cache = cache(cacheManager);
    }

    private static RemoteCache<String, String> cache(RemoteCacheManager cacheManager) {
        try {
            return cacheManager.administration().getOrCreateCache(CACHE, new StringConfiguration(PERSISTENT_CONFIG));
        } catch (RuntimeException e) {
            LOG.warnf("Input mode falling back to an in-memory cache, setting won't survive a restart: %s",
                e.getMessage());
            return cacheManager.administration().getOrCreateCache(CACHE, new StringConfiguration(MEMORY_CONFIG));
        }
    }

    /**
     * The active input mode: reads from Infinispan cache, falling back to the configured default.
     */
    public InputMode current() {
        String stored = cache.get(KEY);
        if (stored != null) {
            Optional<InputMode> mode = InputMode.find(stored);
            if (mode.isPresent()) {
                return mode.get();
            }
        }
        return defaultMode();
    }

    /**
     * The default input mode from configuration.
     */
    public InputMode defaultMode() {
        return InputMode.find(config.input().mode()).orElse(InputMode.TAP);
    }

    /**
     * Validates and applies an input mode string. Returns an error message list if rejected, or empty on success.
     */
    public List<String> apply(String modeStr) {
        Optional<InputMode> parsed = InputMode.find(modeStr);
        if (parsed.isEmpty()) {
            return List.of("Unknown input mode '" + modeStr + "'; expected tap, shake or both");
        }
        return apply(parsed.get());
    }

    /**
     * Applies an input mode. Switching is only allowed between races. On success, writes to Infinispan,
     * broadcasts an input-mode game event, and returns an empty list.
     */
    public List<String> apply(InputMode mode) {
        if (mode == null) {
            return List.of("Input mode is required; expected tap, shake or both");
        }
        String status = game.status().type();
        if (GameEvent.START.equals(status) || GameEvent.PAUSE.equals(status)) {
            return List.of("Input mode can only be changed between races");
        }
        cache.put(KEY, mode.value());
        game.sendInputMode(mode.value());
        return List.of();
    }

    /**
     * Removes the stored input mode, reverting to the configured default.
     */
    public void clear() {
        cache.remove(KEY);
    }
}
