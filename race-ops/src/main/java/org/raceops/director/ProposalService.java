package org.raceops.director;

import jakarta.enterprise.context.ApplicationScoped;
import org.raceops.mcp.AdminRaceClient;
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
 * open across a reset or a finish must not boost the next race. The MCP call runs outside
 * the lock, so a slow game cannot stall the state endpoint.
 */
@ApplicationScoped
public class ProposalService {

    private static final int RECENT_LIMIT = 10;

    private final AdminRaceClient admin;
    private final CurrentRace currentRace;
    private final Map<String, ProposalView> proposals = new LinkedHashMap<>();

    public ProposalService(AdminRaceClient admin, CurrentRace currentRace) {
        this.admin = admin;
        this.currentRace = currentRace;
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
                return Optional.of(stale);
            }
            proposals.put(id, new ProposalView(proposal, Status.DECIDING, null, 0));
            taken = proposal;
        }
        ProposalView decided;
        try {
            decided = new ProposalView(taken, Status.EXECUTED, execute(taken), System.currentTimeMillis());
        } catch (Exception e) {
            decided = new ProposalView(taken, Status.FAILED, e.getMessage(), System.currentTimeMillis());
        }
        synchronized (this) {
            proposals.put(id, decided);
        }
        return Optional.of(decided);
    }

    public synchronized Optional<ProposalView> dismiss(String id) {
        ProposalView view = proposals.get(id);
        if (view != null && view.status().equals(Status.PENDING)) {
            view = new ProposalView(view.proposal(), Status.DISMISSED, null, System.currentTimeMillis());
            proposals.put(id, view);
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

    public enum Status {PENDING, DECIDING, EXECUTED, DISMISSED, FAILED}

    /**
     * A proposal with its current status, as the console sees it.
     */
    public record ProposalView(Proposal proposal, Status status, String result, long decidedAt) {
    }
}
