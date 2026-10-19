package org.acme;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.acme.RaceState.Status.FINISHED;
import static org.acme.RaceState.Status.STARTED;

/** Compares snapshots in delivery order. Call from one serialized consumer. */
public final class CommentaryTriggerDetector {
    private final long surgeWindowMs;
    private final double surgeMultiplier;
    private final long minSurgePower;
    private boolean initialized;
    private String raceId;
    private long updatedAt;
    private Integer leader;
    private final Map<Integer, Integer> milestones = new HashMap<>();
    private boolean finished;
    private long windowStartedAt;
    private Map<String, Long> windowBaseline = Map.of();
    private Map<String, Long> precedingWindow = Map.of();
    private boolean hasPrecedingWindow;

    public CommentaryTriggerDetector(Duration surgeWindow, double surgeMultiplier, long minSurgePower) {
        if (surgeWindow == null || surgeWindow.toMillis() <= 0
                || !Double.isFinite(surgeMultiplier) || surgeMultiplier <= 0 || minSurgePower <= 0) {
            throw new IllegalArgumentException("Surge window and thresholds must be positive");
        }
        this.surgeWindowMs = surgeWindow.toMillis();
        this.surgeMultiplier = surgeMultiplier;
        this.minSurgePower = minSurgePower;
    }

    public List<CommentaryTrigger> accept(RaceState state) {
        if (!initialized || !Objects.equals(raceId, state.raceId())) {
            baseline(state);
            return List.of();
        }
        if (state.updatedAt() <= updatedAt) {
            return List.of();
        }
        updatedAt = state.updatedAt();
        if (finished) {
            return List.of();
        }
        if (state.status() == FINISHED) {
            finished = true;
            return state.winner() == null ? List.of()
                : List.of(trigger(state, "finish", state.winner(), null, null));
        }
        if (state.status() != STARTED) {
            if (state.status() == RaceState.Status.WAITING) {
                windowBaseline = totals(state);
                windowStartedAt = state.elapsedMs();
            }
            return List.of();
        }
        if (state.elapsedMs() == windowStartedAt) {
            windowBaseline = totals(state);
        }

        List<CommentaryTrigger> triggers = new ArrayList<>();
        Integer nextLeader = uniqueLeader(state);
        if (nextLeader != null && !nextLeader.equals(leader)) {
            triggers.add(trigger(state, "lead", nextLeader, null, null));
            leader = nextLeader;
        }
        for (RaceState.TeamState team : state.teams()) {
            int crossed = milestone(team.distance());
            if (crossed > milestones.getOrDefault(team.id(), 0)) {
                milestones.put(team.id(), crossed);
                triggers.add(trigger(state, "milestone", team.id(), crossed, null));
            }
        }
        checkSurges(state, triggers);
        return List.copyOf(triggers);
    }

    public void reset() {
        initialized = false;
        raceId = null;
        updatedAt = 0;
        leader = null;
        milestones.clear();
        finished = false;
        windowStartedAt = 0;
        windowBaseline = Map.of();
        precedingWindow = Map.of();
        hasPrecedingWindow = false;
    }

    private void baseline(RaceState state) {
        initialized = true;
        raceId = state.raceId();
        updatedAt = state.updatedAt();
        leader = uniqueLeader(state);
        milestones.clear();
        state.teams().forEach(team -> milestones.put(team.id(), milestone(team.distance())));
        finished = state.status() == FINISHED;
        windowStartedAt = state.elapsedMs();
        windowBaseline = totals(state);
        precedingWindow = Map.of();
        hasPrecedingWindow = false;
    }

    private void checkSurges(RaceState state, List<CommentaryTrigger> triggers) {
        long duration = state.elapsedMs() - windowStartedAt;
        if (duration < surgeWindowMs) {
            return;
        }
        Map<String, Long> current = totals(state);
        Map<String, Long> increases = new HashMap<>();
        current.forEach((name, power) -> {
            if (windowBaseline.containsKey(name)) {
                increases.put(name, Math.max(0, power - windowBaseline.get(name)));
            }
        });
        // Missing an entire window makes the two increases incomparable.
        if (duration < surgeWindowMs * 2 && hasPrecedingWindow) {
            RaceState.PlayerState selected = null;
            long largest = -1;
            for (RaceState.PlayerState player : state.players()) {
                Long before = precedingWindow.get(player.name());
                Long increase = increases.get(player.name());
                if (before != null && increase != null && increase >= minSurgePower
                        && increase >= before * surgeMultiplier
                        && (increase > largest || (increase == largest
                            && selected != null && player.name().compareTo(selected.name()) < 0))) {
                    selected = player;
                    largest = increase;
                }
            }
            if (selected != null) {
                triggers.add(trigger(state, "surge", selected.team(), null, selected.name()));
            }
        }
        hasPrecedingWindow = duration < surgeWindowMs * 2;
        precedingWindow = increases;
        windowBaseline = current;
        windowStartedAt = state.elapsedMs();
    }

    private static Map<String, Long> totals(RaceState state) {
        Map<String, Long> totals = new HashMap<>();
        state.players().forEach(player -> totals.put(player.name(), player.generated()));
        return totals;
    }

    private static Integer uniqueLeader(RaceState state) {
        double max = 0;
        Integer leader = null;
        for (RaceState.TeamState team : state.teams()) {
            if (team.distance() > max) {
                max = team.distance();
                leader = team.id();
            } else if (team.distance() == max) {
                leader = null;
            }
        }
        return leader;
    }

    private static int milestone(double distance) {
        return distance >= 75 ? 75 : distance >= 50 ? 50 : distance >= 25 ? 25 : 0;
    }

    private static CommentaryTrigger trigger(RaceState state, String type, int team,
                                              Integer milestone, String player) {
        String suffix = switch (type) {
            case "finish" -> "";
            case "milestone" -> ":" + team + ":" + milestone;
            default -> ":" + state.updatedAt() + ":" + team + (player == null ? "" : ":" + player);
        };
        return new CommentaryTrigger(state.raceId() + ":" + type + suffix, state.raceId(), type,
            state.updatedAt(), team, milestone, player);
    }
}
