package org.acme.halloffame;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import org.acme.game.GameConfig;

/**
 * Serves the hall of fame: the finished races stored in Infinispan, most recent first. Like the race queries it is
 * anonymous, so a booth screen can show it without a login.
 */
@ApplicationScoped
@Path("hall-of-fame")
@Startup
public class HallOfFameResource {

    private final HallOfFameStore store;
    private final int maxEntries;

    public HallOfFameResource(HallOfFameStore store, GameConfig config) {
        this.store = store;
        this.maxEntries = config.hallOfFame().maxEntries();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<HallOfFameEntry> all() {
        return store.all().stream().limit(maxEntries).toList();
    }
}
