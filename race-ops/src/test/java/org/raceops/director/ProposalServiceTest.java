package org.raceops.director;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.raceops.mcp.AdminRaceClient;
import org.raceops.mesh.MeshClient;
import org.raceops.metrics.DirectorMetrics;
import org.raceops.monitor.CurrentRace;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProposalServiceTest {

    /** Records what the director did to the game, without a game. */
    static class RecordingAdmin extends AdminRaceClient {
        String lastBoost;
        String lastTheme;
        boolean fail;

        RecordingAdmin() {
            super(null, null, new DirectorMetrics(new SimpleMeterRegistry()));
        }

        @Override
        public String boost(int team, double multiplier, Integer seconds) {
            if (fail) {
                throw new IllegalStateException("game unreachable");
            }
            lastBoost = "boost team " + team + " x" + multiplier + " for " + seconds + "s";
            return "Applied";
        }

        @Override
        public String applyPresetTheme(String presetId) {
            if (fail) {
                throw new IllegalStateException("game unreachable");
            }
            lastTheme = "theme " + presetId;
            return "Theme applied";
        }
    }

    /** Records what the creative specialist was asked to apply, without the A2A call. */
    static class RecordingMesh extends MeshClient {
        String kind;
        String brief;

        RecordingMesh() {
            super(null, null, null, null, new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @Override
        public String applyTheme(String kind, String brief) {
            this.kind = kind;
            this.brief = brief;
            return "Applied: Neon Racers vs Pixel Kings.";
        }
    }

    private static Proposal boostProposal(String raceId) {
        return Proposal.boost(raceId, 2, 2.5, 30, "Team 2 is behind. Boost?");
    }

    private static ProposalService service(RecordingAdmin admin, CurrentRace race) {
        return service(admin, race, new RecordingMesh());
    }

    private static ProposalService service(RecordingAdmin admin, CurrentRace race, MeshClient mesh) {
        return new ProposalService(admin, race, new DirectorMetrics(new SimpleMeterRegistry()), mesh);
    }

    private static String idOf(ProposalService service) {
        return service.pending().get(0).proposal().id();
    }

    @Test
    void onlyOnePendingProposalAtATime() {
        RecordingAdmin admin = new RecordingAdmin();
        ProposalService service = service(admin, new CurrentRace());
        assertTrue(service.propose(boostProposal("race-1")));
        assertFalse(service.propose(boostProposal("race-2")));
        assertEquals(1, service.pending().size());
    }

    @Test
    void approveExecutesThroughTheAdminToolset() {
        RecordingAdmin admin = new RecordingAdmin();
        CurrentRace currentRace = new CurrentRace();
        currentRace.update("race-1");
        ProposalService service = service(admin, currentRace);
        service.propose(boostProposal("race-1"));

        Optional<ProposalService.ProposalView> view = service.approve(idOf(service));
        assertEquals(ProposalService.Status.EXECUTED, view.get().status());
        assertEquals("Applied", view.get().result());
        assertEquals("boost team 2 x2.5 for 30s", admin.lastBoost);
        assertTrue(service.pending().isEmpty());
    }

    @Test
    void approvingForAnEndedRaceIsRejected() {
        RecordingAdmin admin = new RecordingAdmin();
        CurrentRace currentRace = new CurrentRace();
        currentRace.update("race-1");
        ProposalService service = service(admin, currentRace);
        service.propose(boostProposal("race-1"));

        // The race ended and a new one began before the operator clicked approve.
        currentRace.update("race-2");
        Optional<ProposalService.ProposalView> view = service.approve(idOf(service));
        assertEquals(ProposalService.Status.DISMISSED, view.get().status());
        assertTrue(view.get().result().contains("already ended"), view.get().result());
        assertNull(admin.lastBoost);
    }

    @Test
    void failingExecutionIsRecordedNotThrown() {
        RecordingAdmin admin = new RecordingAdmin();
        admin.fail = true;
        CurrentRace currentRace = new CurrentRace();
        currentRace.update("race-1");
        ProposalService service = service(admin, currentRace);
        service.propose(boostProposal("race-1"));

        Optional<ProposalService.ProposalView> view = service.approve(idOf(service));
        assertEquals(ProposalService.Status.FAILED, view.get().status());
        assertEquals("game unreachable", view.get().result());
    }

    @Test
    void dismissDropsAPendingProposal() {
        RecordingAdmin admin = new RecordingAdmin();
        ProposalService service = service(admin, new CurrentRace());
        service.propose(boostProposal("race-1"));
        String id = idOf(service);

        assertEquals(ProposalService.Status.DISMISSED, service.dismiss(id).get().status());
        assertTrue(service.pending().isEmpty());
        assertNull(admin.lastBoost);
        // A decided proposal cannot be decided again.
        assertEquals(ProposalService.Status.DISMISSED, service.approve(id).get().status());
    }

    @Test
    void approvingAThemeAppliesThePreset() {
        RecordingAdmin admin = new RecordingAdmin();
        CurrentRace currentRace = new CurrentRace();
        currentRace.update("race-1");
        ProposalService service = service(admin, currentRace);
        service.propose(Proposal.theme("race-1", "atlanta", "Next heat: Aquarium vs Coca-Cola?"));

        Optional<ProposalService.ProposalView> view = service.approve(idOf(service));
        assertEquals(ProposalService.Status.EXECUTED, view.get().status());
        assertEquals("Theme applied", view.get().result());
        assertEquals("theme atlanta", admin.lastTheme);
        assertNull(admin.lastBoost);
    }

    @Test
    void aThemeApprovedMidRaceWaitsForTheFinish() {
        // The game refuses a restyle while a heat runs, so approving the card only queues it.
        RecordingAdmin admin = new RecordingAdmin();
        CurrentRace currentRace = new CurrentRace();
        currentRace.update("race-1", "STARTED");
        ProposalService service = service(admin, currentRace);
        service.propose(Proposal.theme("race-1", "atlanta", "Aquarium for the next heat?").defer());

        Optional<ProposalService.ProposalView> view = service.approve(idOf(service));
        assertEquals(ProposalService.Status.QUEUED, view.get().status());
        assertEquals("Applies when the race finishes", view.get().result());
        assertNull(admin.lastTheme);
        assertTrue(service.hasDeferred());
        // A queued card does not hold the console: the next proposal may still appear.
        assertTrue(service.pending().isEmpty());
    }

    @Test
    void theFinishAppliesTheQueuedTheme() {
        RecordingAdmin admin = new RecordingAdmin();
        CurrentRace currentRace = new CurrentRace();
        currentRace.update("race-1", "STARTED");
        ProposalService service = service(admin, currentRace);
        service.propose(Proposal.theme("race-1", "atlanta", "Aquarium for the next heat?").defer());
        service.approve(idOf(service));

        currentRace.update("race-1", "FINISHED");
        List<ProposalService.ProposalView> applied = service.applyDeferred();
        assertEquals(1, applied.size());
        assertEquals(ProposalService.Status.EXECUTED, applied.get(0).status());
        assertEquals("Theme applied", applied.get(0).result());
        assertEquals("theme atlanta", admin.lastTheme);
        assertFalse(service.hasDeferred());
    }

    @Test
    void aDeferredThemeApprovedBetweenHeatsAppliesAtOnce() {
        RecordingAdmin admin = new RecordingAdmin();
        CurrentRace currentRace = new CurrentRace();
        currentRace.update("race-1", "FINISHED");
        ProposalService service = service(admin, currentRace);
        service.propose(Proposal.theme("race-1", "atlanta", "Aquarium for the next heat?").defer());

        Optional<ProposalService.ProposalView> view = service.approve(idOf(service));
        assertEquals(ProposalService.Status.EXECUTED, view.get().status());
        assertEquals("theme atlanta", admin.lastTheme);
        assertFalse(service.hasDeferred());
    }

    @Test
    void aFailingDeferredThemeIsRecorded() {
        RecordingAdmin admin = new RecordingAdmin();
        admin.fail = true;
        CurrentRace currentRace = new CurrentRace();
        currentRace.update("race-1", "STARTED");
        ProposalService service = service(admin, currentRace);
        service.propose(Proposal.theme("race-1", "atlanta", "Aquarium?").defer());
        service.approve(idOf(service));

        currentRace.update("race-1", "FINISHED");
        List<ProposalService.ProposalView> applied = service.applyDeferred();
        assertEquals(ProposalService.Status.FAILED, applied.get(0).status());
        assertEquals("game unreachable", applied.get(0).result());
    }

    @Test
    void approvingAnAiThemeRunsTheCreativeSpecialist() {
        RecordingAdmin admin = new RecordingAdmin();
        RecordingMesh mesh = new RecordingMesh();
        CurrentRace currentRace = new CurrentRace();
        currentRace.update("race-1");
        ProposalService service = service(admin, currentRace, mesh);
        service.propose(Proposal.aiTheme("race-1", "ai-theme", "a retro arcade tournament",
            "Rockets for the next heat."));

        Optional<ProposalService.ProposalView> view = service.approve(idOf(service));
        assertEquals(ProposalService.Status.EXECUTED, view.get().status());
        assertEquals("Applied: Neon Racers vs Pixel Kings.", view.get().result());
        assertEquals("ai-theme", mesh.kind);
        assertEquals("a retro arcade tournament", mesh.brief);
        assertNull(admin.lastTheme);
    }
}
