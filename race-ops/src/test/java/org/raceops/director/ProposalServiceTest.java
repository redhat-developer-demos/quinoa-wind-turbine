package org.raceops.director;

import org.junit.jupiter.api.Test;
import org.raceops.mcp.AdminRaceClient;
import org.raceops.monitor.CurrentRace;

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
            super(null, null);
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

    private static Proposal boostProposal(String raceId) {
        return Proposal.boost(raceId, 2, 2.5, 30, "Team 2 is behind. Boost?");
    }

    private static String idOf(ProposalService service) {
        return service.pending().get(0).proposal().id();
    }

    @Test
    void onlyOnePendingProposalAtATime() {
        RecordingAdmin admin = new RecordingAdmin();
        ProposalService service = new ProposalService(admin, new CurrentRace());
        assertTrue(service.propose(boostProposal("race-1")));
        assertFalse(service.propose(boostProposal("race-2")));
        assertEquals(1, service.pending().size());
    }

    @Test
    void approveExecutesThroughTheAdminToolset() {
        RecordingAdmin admin = new RecordingAdmin();
        CurrentRace currentRace = new CurrentRace();
        currentRace.update("race-1");
        ProposalService service = new ProposalService(admin, currentRace);
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
        ProposalService service = new ProposalService(admin, currentRace);
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
        ProposalService service = new ProposalService(admin, currentRace);
        service.propose(boostProposal("race-1"));

        Optional<ProposalService.ProposalView> view = service.approve(idOf(service));
        assertEquals(ProposalService.Status.FAILED, view.get().status());
        assertEquals("game unreachable", view.get().result());
    }

    @Test
    void dismissDropsAPendingProposal() {
        RecordingAdmin admin = new RecordingAdmin();
        ProposalService service = new ProposalService(admin, new CurrentRace());
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
        ProposalService service = new ProposalService(admin, currentRace);
        service.propose(Proposal.theme("race-1", "atlanta", "Next heat: Aquarium vs Coca-Cola?"));

        Optional<ProposalService.ProposalView> view = service.approve(idOf(service));
        assertEquals(ProposalService.Status.EXECUTED, view.get().status());
        assertEquals("Theme applied", view.get().result());
        assertEquals("theme atlanta", admin.lastTheme);
        assertNull(admin.lastBoost);
    }
}
