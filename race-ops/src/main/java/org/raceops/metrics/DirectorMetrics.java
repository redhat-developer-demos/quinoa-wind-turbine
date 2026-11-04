package org.raceops.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.raceops.model.RaceSnapshot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The director's own meters, on the same registry as the JVM, HTTP and model ones, so one
 * scrape of {@code /metrics} covers the whole app. Names are dotted in code and arrive as
 * snake_case in Prometheus: {@code race.ops.proposals.decided} is
 * {@code race_ops_proposals_decided_total}.
 *
 * The model's token counts are not counted here. The langchain4j integration registers
 * {@code gen_ai_client_token_usage_total} itself, tagged with the AI service, as soon as
 * micrometer is on the classpath and the OpenAI integration is enabled, which is what makes
 * the director's prompt and completion tokens visible.
 *
 * The bean starts with the app rather than on first use: the gauges are what a scrape needs
 * even when the director is idle, or when the watcher is off and nothing else touches it.
 */
@Startup
@ApplicationScoped
public class DirectorMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger pendingProposals = new AtomicInteger();
    private final AtomicInteger healthy = new AtomicInteger(1);
    /** Per-team progress in percent of the track, and how many players are on each team. */
    private final Map<Integer, AtomicReference<Double>> progress = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicInteger> players = new ConcurrentHashMap<>();

    @Inject
    public DirectorMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("race.ops.proposals.pending", pendingProposals, AtomicInteger::get)
            .description("Proposals waiting for the operator on the console")
            .register(registry);
        Gauge.builder("race.ops.director.healthy", healthy, AtomicInteger::get)
            .description("1 when the director's last call to the game worked, 0 when it failed")
            .register(registry);
    }

    /** A proposal the director just put on the console. */
    public void proposalProposed(String type) {
        counter("race.ops.proposals.proposed", "Proposals the director put on the console", "type", type)
            .increment();
    }

    /**
     * @param type    {@code boost} or {@code theme}
     * @param outcome {@code executed}, {@code failed}, {@code dismissed} (the operator said no)
     *                or {@code stale} (the race it belonged to already ended)
     */
    public void proposalDecided(String type, String outcome) {
        counter("race.ops.proposals.decided", "Proposals the operator or the race state settled",
            "type", type, "outcome", outcome)
            .increment();
    }

    public void pendingProposals(int count) {
        pendingProposals.set(count);
    }

    /** A race the director started on the join plateau. */
    public void raceStarted() {
        counter("race.ops.races.started", "Races the director started itself").increment();
    }

    /**
     * Feeds the live race gauges from an accepted snapshot: progress and headcount per team,
     * and how many snapshots arrived at all, split by status.
     */
    public void snapshot(RaceSnapshot snapshot) {
        counter("race.ops.race.snapshots", "Race snapshots the director accepted", "status", snapshot.status())
            .increment();
        if (snapshot.teams() == null) {
            return;
        }
        for (RaceSnapshot.Team team : snapshot.teams()) {
            progress.computeIfAbsent(team.id(), this::progressGauge).set(team.distance());
            players.computeIfAbsent(team.id(), this::playersGauge).set(team.players());
        }
    }

    /**
     * One call the director made on the game's admin MCP server. The timer carries the tool
     * and the outcome, so a failing tool shows up per tool instead of one error rate.
     */
    public void mcpCall(String tool, String outcome, long nanos) {
        Timer.builder("race.ops.mcp.calls")
            .description("Calls on the game's admin MCP server")
            .tag("tool", tool)
            .tag("outcome", outcome)
            .register(registry)
            .record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * @param healthy false while the director cannot act on the game (down, or rejecting the
     *                director's token), true once a call works again
     */
    public void directorHealthy(boolean healthy) {
        this.healthy.set(healthy ? 1 : 0);
    }

    private Counter counter(String name, String description, String... tags) {
        return Counter.builder(name).description(description).tags(tags).register(registry);
    }

    private AtomicReference<Double> progressGauge(int team) {
        AtomicReference<Double> value = new AtomicReference<>(0d);
        Gauge.builder("race.ops.race.progress", value, ref -> ref.get() == null ? 0d : ref.get())
            .description("Team progress in percent of the track")
            .tag("team", String.valueOf(team))
            .register(registry);
        return value;
    }

    private AtomicInteger playersGauge(int team) {
        AtomicInteger value = new AtomicInteger();
        Gauge.builder("race.ops.race.players", value, AtomicInteger::get)
            .description("Players on the team")
            .tag("team", String.valueOf(team))
            .register(registry);
        return value;
    }
}
