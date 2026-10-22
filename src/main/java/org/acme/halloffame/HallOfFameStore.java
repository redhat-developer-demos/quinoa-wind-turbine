package org.acme.halloffame;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.commons.configuration.StringConfiguration;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Stores finished races in an Infinispan cache so the hall of fame spans a whole event and survives a restart. Entries
 * are JSON strings keyed by race ID, so no Protostream schema is needed. A persistent counter is not a persistent
 * cache, so the cache is created with a file-store; if the server rejects that, it falls back to an in-memory cache so
 * the game still runs.
 */
@ApplicationScoped
@Startup
public class HallOfFameStore {

    static final String CACHE = "hall-of-fame";
    private static final Logger LOG = Logger.getLogger(HallOfFameStore.class);

    // A file-store keeps the entries across a server restart, under the server's data volume.
    private static final String PERSISTENT_CONFIG = """
        <distributed-cache>
          <persistence passivation="false">
            <file-store preload="true"/>
          </persistence>
        </distributed-cache>""";
    private static final String MEMORY_CONFIG = "<distributed-cache/>";

    private final RemoteCache<String, String> cache;
    private final ObjectMapper mapper;

    public HallOfFameStore(RemoteCacheManager cacheManager, ObjectMapper mapper) {
        this.mapper = mapper;
        this.cache = cache(cacheManager);
    }

    private static RemoteCache<String, String> cache(RemoteCacheManager cacheManager) {
        try {
            return cacheManager.administration().getOrCreateCache(CACHE, new StringConfiguration(PERSISTENT_CONFIG));
        } catch (RuntimeException e) {
            LOG.warnf("Hall of fame falling back to an in-memory cache, entries won't survive a restart: %s",
                e.getMessage());
            return cacheManager.administration().getOrCreateCache(CACHE, new StringConfiguration(MEMORY_CONFIG));
        }
    }

    /**
     * Writes the entry only if its race isn't stored yet, so a finish seen on more than one replica during a rebalance
     * is recorded once.
     */
    void record(HallOfFameEntry entry) {
        try {
            cache.putIfAbsent(entry.raceId(), mapper.writeValueAsString(entry));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize hall-of-fame entry", e);
        }
    }

    /**
     * Every stored race, most recent first.
     */
    List<HallOfFameEntry> all() {
        Set<String> keys = new HashSet<>(cache.keySet());
        if (keys.isEmpty()) {
            return List.of();
        }
        return cache.getAll(keys).values().stream()
            .map(this::parse)
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingLong(HallOfFameEntry::finishedAt).reversed())
            .toList();
    }

    private HallOfFameEntry parse(String json) {
        try {
            return mapper.readValue(json, HallOfFameEntry.class);
        } catch (JsonProcessingException e) {
            LOG.warnf("Skipping an unreadable hall-of-fame entry: %s", e.getMessage());
            return null;
        }
    }
}
