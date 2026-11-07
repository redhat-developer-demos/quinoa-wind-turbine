package org.raceops.director;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.raceops.mcp.AdminRaceClient;
import org.raceops.mesh.MeshClient;
import org.raceops.metrics.DirectorMetrics;
import org.raceops.monitor.CurrentRace;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Holds the director's proposals and carries them through their lifecycle: pending until
 * the operator approves or dismisses on the console, then executed (or failed) through
 * the admin toolset. One pending proposal at a time keeps the console readable.
 *
 * Approving a proposal for a race that has already ended rejects it instead: a card left
 * open across a reset or a finish must not boost the next race. A theme approved mid-race
 * is queued instead of applied, because the game refuses a restyle while a heat runs; the
 * finish releases it. The MCP call runs outside the lock, so a slow game cannot stall the
 * state endpoint. Every transition is counted, so the console and the metrics agree on what
 * was proposed and what happened to it.
 */
@ApplicationScoped
public class ProposalService {

    private static final int RECENT_LIMIT = 10;

    private final AdminRaceClient admin;
    private final CurrentRace currentRace;
    private final DirectorMetrics metrics;
    private final MeshClient mesh;
    private final Map<String, ProposalView> proposals = new LinkedHashMap<>();
    /** Looks approved mid-race, waiting for the heat to end before the game will take them. */
    private final Deque<Proposal> deferred = new ArrayDeque<>();

    @Inject
    public ProposalService(AdminRaceClient admin, CurrentRace currentRace, DirectorMetrics metrics,
                           MeshClient mesh) {
        this.admin = admin;
        this.currentRace = currentRace;
        this.metrics = metrics;
        this.mesh = mesh;
    }

    /**
     * @return false when another proposal is already pending
     */
    public synchronized boolean propose(Proposal proposal) {
        if (proposals.values().stream().anyMatch(v -> v.status().equals(Status.PENDING))) {
            return false;
        }
        proposals.put(proposal.id(), new ProposalView(proposal, Status.PENDING, null, 0));
        trim();
        metrics.proposalProposed(proposal.type());
        metrics.pendingProposals(pending().size());
        return true;
    }

    public Optional<ProposalView> approve(String id) {
        Proposal taken;
        synchronized (this) {
            ProposalView view = proposals.get(id);
            if (view == null || !view.status().equals(Status.PENDING)) {
                return Optional.ofNullable(view);
            }
            Proposal proposal = view.proposal();
            String race = currentRace.raceId();
            if (race != null && !proposal.raceId().equals(race)) {
                ProposalView stale = new ProposalView(proposal, Status.DISMISSED,
                    "Rejected: " + proposal.raceId() + " already ended", System.currentTimeMillis());
                proposals.put(id, stale);
                metrics.proposalDecided(proposal.type(), "stale");
                metrics.pendingProposals(pending().size());
                return Optional.of(stale);
            }
            if (proposal.deferred() && currentRace.running()) {
                // A next-heat look approved mid-race cannot be applied yet: the game refuses
                // a restyle while a heat runs. Hold it and let the finish release it.
                deferred.addLast(proposal);
                ProposalView queued = new ProposalView(proposal, Status.QUEUED,
                    "Applies when the race finishes", System.currentTimeMillis());
                proposals.put(id, queued);
                metrics.pendingProposals(pending().size());
                return Optional.of(queued);
            }
            proposals.put(id, new ProposalView(proposal, Status.DECIDING, null, 0));
            metrics.pendingProposals(pending().size());
            taken = proposal;
        }
        return Optional.of(run(taken));
    }

    /**
     * Applies every look the operator approved mid-race, now that the heat is over. Called
     * on the finish and on a reset, never while a heat runs. Returns what was applied, so
     * the caller can tell the console served a card instead of proposing another one.
     */
    public List<ProposalView> applyDeferred() {
        List<Proposal> queued;
        synchronized (this) {
            if (deferred.isEmpty()) {
                return List.of();
            }
            queued = List.copyOf(deferred);
            deferred.clear();
        }
        return queued.stream().map(this::run).toList();
    }

    /**
     * @return true when a look approved mid-race is still waiting for the finish
     */
    public synchronized boolean hasDeferred() {
        return !deferred.isEmpty();
    }

    private ProposalView run(Proposal proposal) {
        ProposalView decided;
        try {
            decided = new ProposalView(proposal, Status.EXECUTED, execute(proposal), System.currentTimeMillis());
        } catch (Exception e) {
            decided = new ProposalView(proposal, Status.FAILED, e.getMessage(), System.currentTimeMillis());
        }
        synchronized (this) {
            proposals.put(proposal.id(), decided);
        }
        metrics.proposalDecided(proposal.type(), decided.status() == Status.EXECUTED ? "executed" : "failed");
        metrics.pendingProposals(pending().size());
        return decided;
    }

    public synchronized Optional<ProposalView> dismiss(String id) {
        ProposalView view = proposals.get(id);
        if (view != null && view.status().equals(Status.PENDING)) {
            view = new ProposalView(view.proposal(), Status.DISMISSED, null, System.currentTimeMillis());
            proposals.put(id, view);
            metrics.proposalDecided(view.proposal().type(), "dismissed");
            metrics.pendingProposals(pending().size());
        }
        return Optional.ofNullable(view);
    }

    public synchronized List<ProposalView> pending() {
        return proposals.values().stream().filter(v -> v.status().equals(Status.PENDING)).toList();
    }

    public synchronized List<ProposalView> recent() {
        return proposals.values().stream()
            .filter(v -> !v.status().equals(Status.PENDING))
            .sorted((a, b) -> Long.compare(b.decidedAt(), a.decidedAt()))
            .toList();
    }

    private String execute(Proposal proposal) {
        return switch (proposal.type()) {
            case Proposal.BOOST -> admin.boost(proposal.team(), proposal.multiplier(), proposal.seconds());
            case Proposal.THEME -> admin.applyPresetTheme(proposal.presetId());
            // An approved AI theme runs in the creative app with no second model call: the brief
            // was shaped at proposal time, and the game applies the result immediately.
            case Proposal.THEME_AI -> mesh.applyTheme(proposal.kind(), proposal.brief());
            default -> throw new IllegalArgumentException("Unknown proposal type " + proposal.type());
        };
    }

    private void trim() {
        Deque<String> decided = new ArrayDeque<>();
        proposals.forEach((id, view) -> {
            if (!view.status().equals(Status.PENDING)) {
                decided.addLast(id);
            }
        });
        while (decided.size() > RECENT_LIMIT) {
            proposals.remove(decided.pollFirst());
        }
    }

    public enum Status {PENDING, QUEUED, DECIDING, EXECUTED, DISMISSED, FAILED}

    /**
     * A proposal with its current status, as the console sees it.
     */
    public record ProposalView(Proposal proposal, Status status, String result, long decidedAt) {
    }
}
