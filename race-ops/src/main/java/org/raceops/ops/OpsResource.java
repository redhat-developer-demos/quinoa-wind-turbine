package org.raceops.ops;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;
import org.raceops.director.ProposalService;
import org.raceops.monitor.OpsBroadcaster;
import org.raceops.monitor.OpsUpdate;

import java.util.List;

/**
 * The ops surface: an SSE stream of what survives the event filter, the current state
 * for pages that just loaded, and the approval endpoints for the director's proposals.
 * The director console (item 4) and this app's own /ops page both read from here.
 */
@ApplicationScoped
@Path("/api/ops")
public class OpsResource {

    private final OpsBroadcaster broadcaster;
    private final ProposalService proposals;

    @Inject
    public OpsResource(OpsBroadcaster broadcaster, ProposalService proposals) {
        this.broadcaster = broadcaster;
        this.proposals = proposals;
    }

    /**
     * The whole console state: the race, the director's pending proposal with its
     * approve/dismiss decision, and what was recently decided.
     */
    public record OpsSnapshot(OpsState race, List<ProposalService.ProposalView> pending,
                              List<ProposalService.ProposalView> recentProposals) {
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
    public OpsSnapshot state() {
        return new OpsSnapshot(broadcaster.state(), proposals.pending(), proposals.recent());
    }

    @POST
    @Path("proposals/{id}/approve")
    @Produces(MediaType.APPLICATION_JSON)
    public ProposalService.ProposalView approve(String id) {
        return proposals.approve(id).orElseThrow(() -> new jakarta.ws.rs.NotFoundException("No proposal " + id));
    }

    @POST
    @Path("proposals/{id}/dismiss")
    @Produces(MediaType.APPLICATION_JSON)
    public ProposalService.ProposalView dismiss(String id) {
        return proposals.dismiss(id).orElseThrow(() -> new jakarta.ws.rs.NotFoundException("No proposal " + id));
    }
}
