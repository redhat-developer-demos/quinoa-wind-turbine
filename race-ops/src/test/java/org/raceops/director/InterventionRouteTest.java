package org.raceops.director;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The supervised route is read from the specialists that ran, not from the model's wording.
 * These are the transition rules the director relies on, kept here so they never depend on a
 * live model.
 */
class InterventionRouteTest {

    @Test
    void aDiagnosisAloneIsTheBoostRoute() {
        assertEquals(DirectorDecisions.Route.BOOST, DirectorDecisions.route(true, false));
    }

    @Test
    void aCreativeAnswerIsTheThemeRouteEvenWhenTheAnalystRanFirst() {
        // Mid-race the analyst runs first, so both flags are set on a quitter path. The
        // model chose a theme, and that choice wins.
        assertEquals(DirectorDecisions.Route.THEME, DirectorDecisions.route(true, true));
        assertEquals(DirectorDecisions.Route.THEME, DirectorDecisions.route(false, true));
    }

    @Test
    void nothingRanSoTheDirectorFallsBack() {
        assertEquals(DirectorDecisions.Route.NONE, DirectorDecisions.route(false, false));
    }

    @Test
    void theRequestCarriesThePhaseAndTheFacts() {
        String request = DirectorDecisions.interventionRequest("middle of the race", "{\"raceId\":\"race-1\"}");
        assertTrue(request.startsWith("phase: middle of the race"), request);
        assertTrue(request.contains("race-1"), request);
    }

    @Test
    void theStandingOrderChangesWithThePhase() {
        // Between heats the scheduled proposal must stay reliable; mid-race the diagnosis
        // must come first. Both reach the planner as supervisorContext, the only steering
        // channel a declarative supervisor offers.
        String midRace = DirectorDecisions.supervisorContext("middle of the race");
        String between = DirectorDecisions.supervisorContext(DirectorDecisions.BETWEEN_HEATS);
        assertTrue(midRace.contains("Call the Race Analyst first"), midRace);
        assertTrue(midRace.contains("return done"), midRace);
        assertTrue(between.contains("standing order"), between);
        assertNotEquals(midRace, between);
    }

    @Test
    void aRealCollapseKeepsTheNextHeatLook() {
        BoostPolicy.Situation collapse = new BoostPolicy.Situation(2, 18.0, BoostPolicy.QUITTER_SHARE);
        assertEquals(DirectorDecisions.Route.THEME,
            DirectorDecisions.guardRoute(DirectorDecisions.Route.THEME, collapse));
    }

    @Test
    void softTappingOverturnsTheNextHeatLook() {
        // The spike's failure: the model called the creative director while the team still
        // tapped. The policy turns that into the boost, with the analyst's diagnosis as the
        // reason, so the race still gets the card it needs.
        BoostPolicy.Situation softTapping = new BoostPolicy.Situation(2, 16.9, 0.0);
        assertEquals(DirectorDecisions.Route.BOOST,
            DirectorDecisions.guardRoute(DirectorDecisions.Route.THEME, softTapping));
    }

    @Test
    void theBoostRouteIsLeftAlone() {
        BoostPolicy.Situation collapse = new BoostPolicy.Situation(2, 18.0, 0.5);
        assertEquals(DirectorDecisions.Route.BOOST,
            DirectorDecisions.guardRoute(DirectorDecisions.Route.BOOST, collapse));
        assertEquals(DirectorDecisions.Route.NONE,
            DirectorDecisions.guardRoute(DirectorDecisions.Route.NONE, collapse));
    }

    @Test
    void aDeferredThemeKeepsEverythingButTheWait() {
        Proposal theme = Proposal.theme("race-1", "atlanta", "Next heat: Aquarium vs Coca-Cola?");
        Proposal deferred = theme.defer();
        assertTrue(deferred.deferred());
        assertFalse(theme.deferred());
        assertEquals(theme.id(), deferred.id());
        assertEquals(theme.presetId(), deferred.presetId());
        assertEquals(theme.message(), deferred.message());
    }
}
