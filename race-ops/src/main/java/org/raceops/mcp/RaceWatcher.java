package org.raceops.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpReadResourceResult;
import dev.langchain4j.mcp.client.McpTextResourceContents;
import io.quarkiverse.langchain4j.mcp.runtime.McpClientName;
import io.quarkiverse.langchain4j.mcp.runtime.McpResourceUpdatedEvent;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.raceops.RaceOpsConfig;
import org.raceops.model.RaceSnapshot;
import org.raceops.monitor.RaceMonitor;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The read path of the watcher: subscribes to the game's race resource over MCP and feeds
 * every update to the monitor. The notifications carry the resource URI only (about four per
 * second mid-race), so each one is followed by a read of the snapshot.
 *
 * The subscription runs on its own virtual thread with backoff, so the app starts even when
 * the game is not up yet. Reads run on a single-reader executor: blocking calls stay off the
 * transport's event loop, and one reader keeps snapshots in order.
 */
@ApplicationScoped
@Startup
public class RaceWatcher {

    private final McpClient race;
    private final RaceMonitor monitor;
    private final RaceOpsConfig config;
    private final ObjectMapper mapper;
    private final ExecutorService reader = Executors.newSingleThreadExecutor(
        Thread.ofVirtual().name("race-ops-reader").factory());
    private final AtomicInteger pollFailures = new AtomicInteger();

    public RaceWatcher(@McpClientName("race") McpClient race, RaceMonitor monitor, RaceOpsConfig config,
                       ObjectMapper mapper) {
        this.race = race;
        this.monitor = monitor;
        this.config = config;
        this.mapper = mapper;
    }

    void onStart(@Observes StartupEvent event) {
        if (!config.watcherEnabled()) {
            Log.info("Race watcher disabled");
            return;
        }
        Thread.ofVirtual().name("race-ops-subscribe").start(this::subscribeWithRetry);
    }

    private void subscribeWithRetry() {
        for (long attempt = 1;; attempt++) {
            try {
                subscribe();
                Log.infof("Subscribed to %s", config.resourceUri());
                // Catch up on the current state: a quiet race produces no notifications,
                // so without this read a restart would stay blind until something changes.
                RaceSnapshot current = read(true);
                if (current != null) {
                    monitor.onSnapshot(current);
                }
                return;
            } catch (Exception e) {
                long delay = Math.min(2000L * attempt, 30_000L);
                Log.warnf(e, "Cannot subscribe to %s (attempt %d), retrying in %d ms",
                    config.resourceUri(), attempt, delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void subscribe() {
        try {
            // subscriptions/listen, MCP protocol 2026-07-28 and later
            race.subscribeToResources(List.of(config.resourceUri()));
        } catch (UnsupportedOperationException e) {
            // resources/subscribe, the older protocol
            race.subscribeToResource(config.resourceUri());
        }
    }

    void onResourceUpdated(@Observes @McpClientName("race") McpResourceUpdatedEvent event) {
        if (!config.watcherEnabled() || !config.resourceUri().equals(event.uri())) {
            return;
        }
        reader.execute(() -> {
            RaceSnapshot snapshot = read(true);
            if (snapshot != null) {
                monitor.onSnapshot(snapshot);
            }
        });
    }

    /**
     * The polling fallback under the subscription. Notifications carry every update while
     * they flow, but the subscription is tied to the MCP session and dies with it — a game
     * restart leaves this app blind even though the transport reconnects. A quiet read
     * every few seconds heals that: the monitor drops snapshots it has already seen, so
     * the poll costs nothing while notifications work, and after a failure a successful
     * read re-subscribes to bring the notifications back.
     */
    @Scheduled(every = "5s")
    void pollFallback() {
        if (!config.watcherEnabled()) {
            return;
        }
        reader.execute(() -> {
            RaceSnapshot snapshot = read(false);
            if (snapshot == null) {
                if (pollFailures.incrementAndGet() == 1) {
                    Log.warnf("Cannot read %s on the poll fallback, will keep retrying every few seconds",
                        config.resourceUri());
                }
                return;
            }
            if (pollFailures.getAndSet(0) > 0) {
                Log.infof("%s readable again, re-subscribing to restore notifications", config.resourceUri());
                try {
                    subscribe();
                } catch (Exception e) {
                    Log.warnf(e, "Re-subscribe failed, the poll keeps the state fresh and retries with the next poll");
                }
            }
            monitor.onSnapshot(snapshot);
        });
    }

    private RaceSnapshot read(boolean logFailures) {
        try {
            McpReadResourceResult result = race.readResource(config.resourceUri());
            if (result.contents() == null || result.contents().isEmpty()) {
                Log.warnf("Resource %s returned no contents", config.resourceUri());
                return null;
            }
            if (!(result.contents().get(0) instanceof McpTextResourceContents contents)) {
                Log.warnf("Resource %s returned %s instead of text", config.resourceUri(),
                    result.contents().get(0).getClass().getSimpleName());
                return null;
            }
            return mapper.readValue(contents.text(), RaceSnapshot.class);
        } catch (Exception e) {
            if (logFailures) {
                Log.warnf(e, "Cannot read %s", config.resourceUri());
            }
            return null;
        }
    }

    @PreDestroy
    void stop() {
        reader.shutdownNow();
    }
}
