package org.raceanalyst.analytics;

import java.util.Comparator;
import java.util.Optional;

/**
 * Turns the engagement report into the one-line answer a human operator cannot produce
 * mid-talk: is the trailing team behind because it is small, because it quit, or because
 * its players tap softer? Plain rules, no model — the director's model only phrases the
 * answer, it does not invent the numbers.
 */
public final class Diagnosis {

    /** Below this, the race is level: no story to tell. */
    private static final double LEVEL_POINTS = 1.0;

    /** Above this share of stalled tappers, quitting is the cause worth naming. */
    private static final double QUIT_SHARE = 0.3;

    private Diagnosis() {
    }

    public static String of(EngagementReport report) {
        if (report == null || report.teams() == null || report.teams().size() < 2) {
            return "Not enough teams to compare.";
        }
        Optional<EngagementReport.TeamEngagement> leader = report.teams().stream()
            .max(Comparator.comparingDouble(EngagementReport.TeamEngagement::distance));
        Optional<EngagementReport.TeamEngagement> trailing = report.teams().stream()
            .min(Comparator.comparingDouble(EngagementReport.TeamEngagement::distance));
        if (leader.isEmpty() || trailing.isEmpty() || leader.get().team() == trailing.get().team()) {
            return "The field is level.";
        }
        EngagementReport.TeamEngagement ahead = leader.get();
        EngagementReport.TeamEngagement behind = trailing.get();
        double deficit = ahead.distance() - behind.distance();
        if (deficit < LEVEL_POINTS) {
            return "The field is level: %.1f points between the teams.".formatted(deficit);
        }
        if (behind.tappers() == 0) {
            return "Team %d is %.1f points behind and nobody on it has tapped yet."
                .formatted(behind.team(), deficit);
        }
        double stalledShare = (double) behind.stalled() / behind.tappers();
        double activeShare = (double) behind.active() / behind.tappers();
        if (stalledShare >= QUIT_SHARE) {
            return "Team %d is %.1f points behind because %d of its %d players stopped tapping, "
                .formatted(behind.team(), deficit, behind.stalled(), behind.tappers())
                + "and quitters still count in the team average.";
        }
        if (behind.tappers() < ahead.tappers()) {
            return "Team %d is %.1f points behind with %d tappers against %d."
                .formatted(behind.team(), deficit, behind.tappers(), ahead.tappers());
        }
        if (activeShare < 0.5) {
            return "Team %d is %.1f points behind because only %d of its %d players are still tapping."
                .formatted(behind.team(), deficit, behind.active(), behind.tappers());
        }
        return "Team %d is %.1f points behind: its %d players generate less power per tap than the leader's."
            .formatted(behind.team(), deficit, behind.tappers());
    }
}
