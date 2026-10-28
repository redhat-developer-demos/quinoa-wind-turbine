package org.raceops.ops;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;
import org.raceops.monitor.OpsBroadcaster;
import org.raceops.monitor.OpsUpdate;

/**
 * The ops surface: an SSE stream of what survives the event filter, and the current state
 * for pages that just loaded. The director console (item 4) and this app's own /ops page
 * both read from here.
 */
@ApplicationScoped
@Path("/api/ops")
public class OpsResource {

    private final OpsBroadcaster broadcaster;

    @Inject
    public OpsResource(OpsBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GET
    @Path("stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<OpsUpdate> stream() {
        return broadcaster.updates();
    }

    @GET
    @Path("state")
    @Produces(MediaType.APPLICATION_JSON)
    public OpsState state() {
        return broadcaster.state();
    }
}
