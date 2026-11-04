package org.raceops.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.raceops.director.DirectorHealth;
import org.raceops.director.Proposal;
import org.raceops.director.ProposalService;
import org.raceops.mcp.AdminRaceClient;
import org.raceops.model.RaceSnapshot;
import org.raceops.monitor.CurrentRace;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The meters exist and move with what the director does. The Prometheus output itself is
 * quarkus-micrometer's job; these assert the names, tags and values a dashboard queries.
 */
class DirectorMetricsTest {

    /** The admin toolset without a game: an override per tool, no MCP client. */
    private static class StubAdmin extends AdminRaceClient {
        boolean fail;

        StubAdmin(DirectorMetrics metrics) {
            super(null, null, metrics);
        }

        @Override
        public String boost(int team, double multiplier, Integer seconds) {
            if (fail) {
                throw new IllegalStateException("game unreachable");
            }
            return "Applied";
        }
    }

    private static ProposalService service(DirectorMetrics metrics) {
        CurrentRace race = new CurrentRace();
        race.update("race-1");
        return new ProposalService(new StubAdmin(metrics), race, metrics);
    }

    @Test
    void aProposalIsCountedFromPendingToExecuted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DirectorMetrics metrics = new DirectorMetrics(registry);
        ProposalService service = service(metrics);

        service.propose(Proposal.boost("race-1", 2, 2.5, 30, "Team 2 is behind. Boost?"));

        assertEquals(1.0, registry.get("race.ops.proposals.proposed").tag("type", "boost").counter().count());
        assertEquals(1.0, registry.get("race.ops.proposals.pending").gauge().value());

        service.approve(service.pending().get(0).proposal().id());

        assertEquals(1.0, registry.get("race.ops.proposals.decided")
            .tags("type", "boost", "outcome", "executed").counter().count());
        assertEquals(0.0, registry.get("race.ops.proposals.pending").gauge().value());
    }

    @Test
    void aFailedExecutionCountsAsFailed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DirectorMetrics metrics = new DirectorMetrics(registry);
        StubAdmin admin = new StubAdmin(metrics);
        admin.fail = true;
        CurrentRace race = new CurrentRace();
        race.update("race-1");
        ProposalService service = new ProposalService(admin, race, metrics);
        service.propose(Proposal.theme("race-1", "atlanta", "Next heat: Aquarium vs Coca-Cola?"));

        service.approve(service.pending().get(0).proposal().id());

        assertEquals(1.0, registry.get("race.ops.proposals.decided")
            .tags("type", "theme", "outcome", "failed").counter().count());
        assertEquals(0.0, registry.get("race.ops.proposals.pending").gauge().value());
    }

    @Test
    void aStaleProposalIsCountedApartFromADismissal() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DirectorMetrics metrics = new DirectorMetrics(registry);
        CurrentRace race = new CurrentRace();
        race.update("race-1");
        ProposalService service = new ProposalService(new StubAdmin(metrics), race, metrics);
        service.propose(Proposal.boost("race-1", 2, 2.5, 30, "Team 2 is behind. Boost?"));
        String id = service.pending().get(0).proposal().id();

        // The race ended before the operator clicked approve.
        race.update("race-2");
        service.approve(id);

        assertEquals(1.0, registry.get("race.ops.proposals.decided")
            .tags("type", "boost", "outcome", "stale").counter().count());
    }

    @Test
    void snapshotsFeedTheLiveRaceGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DirectorMetrics metrics = new DirectorMetrics(registry);

        metrics.snapshot(new RaceSnapshot("race-1", "STARTED", 1000, 5, null,
            List.of(new RaceSnapshot.Team(1, 1, 3, 900, 42.5, null),
                new RaceSnapshot.Team(2, 2, 2, 700, 30.0, null)),
            List.of()));

        assertEquals(42.5, registry.get("race.ops.race.progress").tag("team", "1").gauge().value());
        assertEquals(2.0, registry.get("race.ops.race.players").tag("team", "2").gauge().value());
        assertEquals(1.0, registry.get("race.ops.race.snapshots").tag("status", "STARTED").counter().count());

        // A second snapshot moves the same gauges instead of registering new ones.
        metrics.snapshot(new RaceSnapshot("race-1", "FINISHED", 2000, 6, 1,
            List.of(new RaceSnapshot.Team(1, 1, 3, 900, 100.0, 2000L),
                new RaceSnapshot.Team(2, 2, 2, 700, 80.0, null)),
            List.of()));

        assertEquals(100.0, registry.get("race.ops.race.progress").tag("team", "1").gauge().value());
        assertEquals(2, registry.find("race.ops.race.progress").gauges().size());
    }

    @Test
    void anMcpCallIsTimedWithItsToolAndOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DirectorMetrics metrics = new DirectorMetrics(registry);

        metrics.mcpCall("start_race", "success", TimeUnit.MILLISECONDS.toNanos(25));

        var timer = registry.get("race.ops.mcp.calls")
            .tags("tool", "start_race", "outcome", "success").timer();
        assertEquals(1, timer.count());
        assertEquals(25.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    void theHealthGaugeFollowsTheDirectorsLastCall() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DirectorHealth health = new DirectorHealth(new DirectorMetrics(registry));
        assertEquals(1.0, registry.get("race.ops.director.healthy").gauge().value());

        health.failed("start_race", new IllegalStateException("game unreachable"));
        assertEquals(0.0, registry.get("race.ops.director.healthy").gauge().value());

        health.succeeded();
        assertEquals(1.0, registry.get("race.ops.director.healthy").gauge().value());
    }
}
