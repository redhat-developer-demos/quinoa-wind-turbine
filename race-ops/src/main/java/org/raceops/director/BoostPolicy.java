package org.raceops.director;

import org.raceops.analytics.EngagementReport;
import org.raceops.model.RaceSnapshot;

import java.util.Optional;

/**
 * When a boost proposal is warranted: a team far enough behind, in the middle of the race.
 * The diagnosis of *why* the team is behind (fewer tappers versus quitters) comes from the
 * engagement report and is carried to the director's rationale, not to this rule.
 */
public final class BoostPolicy {

    /**
     * Only between these fractions of the race: earlier is opening noise, later is a
     * decided outcome. The leader sets the race's progress; the trailing team must also
     * have moved, or the gap is just start scatter.
     */
    static final double EARLIEST = 25;
    static final double LATEST = 75;
    static final double TRAILING_MIN = 10;

    /**
     * The situation that triggers a proposal.
     *
     * @param team        the trailing team
     * @param deficit     how far behind, in percent of the track
     * @param stalledShare the share of that team's tappers who stopped tapping
     */
    public record Situation(int team, double deficit, double stalledShare) {
    }

    private BoostPolicy() {
    }

    /**
     * @param deficitPercent the configured threshold, percent of the track
     */
    public static Optional<Situation> evaluate(RaceSnapshot snapshot, EngagementReport engagement,
                                               int deficitPercent) {
        if (snapshot == null || engagement == null || !"STARTED".equals(snapshot.status())) {
            return Optional.empty();
        }
        RaceSnapshot.Team leader = snapshot.leader();
        if (leader == null || snapshot.teams() == null || snapshot.teams().size() < 2) {
            return Optional.empty();
        }
        if (leader.distance() < EARLIEST || leader.distance() > LATEST) {
            return Optional.empty();
        }
        // The trailing team is every team that is not the leader — correct for the two-team
        // race the game runs today; with more teams this should pick the last one instead.
        RaceSnapshot.Team trailing = snapshot.teams().stream()
            .filter(t -> t.id() != leader.id())
            .findFirst().orElse(null);
        if (trailing == null || trailing.distance() < TRAILING_MIN) {
            return Optional.empty();
        }
        double deficit = leader.distance() - trailing.distance();
        if (deficit < deficitPercent) {
            return Optional.empty();
        }
        double stalledShare = engagement.teams() == null ? 0
            : engagement.teams().stream()
                .filter(t -> t.team() == trailing.id())
                .mapToDouble(t -> t.tappers() == 0 ? 0 : (double) t.stalled() / t.tappers())
                .findFirst().orElse(0);
        return Optional.of(new Situation(trailing.id(), deficit, stalledShare));
    }
}
