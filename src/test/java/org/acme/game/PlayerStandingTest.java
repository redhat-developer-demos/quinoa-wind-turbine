package org.acme.game;

import io.quarkiverse.mcp.server.ToolCallException;
import org.acme.game.PlayerSocket.PlayerMessage;
import org.acme.race.RaceState.PlayerStanding;
import org.acme.race.RaceState.PlayerState;
import org.acme.race.RaceState.Status;
import org.acme.race.RaceState.TeamState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import org.acme.race.RaceState;
import org.acme.admin.RaceTools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerStandingTest {

    private static final RaceState RACE = new RaceState("race-1", Status.STARTED, 1000, 0, null,
        List.of(new TeamState(2, 1, 1, 200, 10, null), new TeamState(1, 2, 2, 150, 5, null)),
        List.of(new PlayerState("cy", 2, 200), new PlayerState("ada", 1, 100), new PlayerState("bob", 1, 50)));

    @Test
    void ranksPlayersInsideTheirTeam() {
        assertEquals(Optional.of(new PlayerStanding("race-1", Status.STARTED, 2, 2, 1, 2, 100)), RACE.standing("ada"));
        assertEquals(Optional.of(new PlayerStanding("race-1", Status.STARTED, 2, 2, 2, 2, 50)), RACE.standing("bob"));
        assertEquals(Optional.of(new PlayerStanding("race-1", Status.STARTED, 1, 2, 1, 1, 200)), RACE.standing("cy"));
        assertEquals(Optional.empty(), RACE.standing("dee"));
    }

    @Test
    void findsAPlayerWithTheirOverallRank() {
        assertEquals(new RaceState.LeaderboardEntry(2, "ada", 1, 100), RaceTools.player(RACE, "Ada"));
        assertThrows(ToolCallException.class, () -> RaceTools.player(RACE, "dee"));
    }

    @Test
    void notifiesWhenThePlayerTakesTheLeadInTheirTeam() {
        PlayerStanding before = standing(Status.STARTED, 2, 2);
        PlayerStanding after = standing(Status.STARTED, 2, 1);
        assertEquals(List.of(new PlayerMessage.Standing(after),
                new PlayerMessage.Notification("You're your team's top generator!")),
            PlayerSocket.messages(before, after));
    }

    @Test
    void notifiesWhenTheTeamTakesTheLead() {
        PlayerStanding before = standing(Status.STARTED, 2, 2);
        PlayerStanding after = standing(Status.STARTED, 1, 2);
        assertEquals(List.of(new PlayerMessage.Standing(after), new PlayerMessage.Notification("Your team took the lead!")),
            PlayerSocket.messages(before, after));
    }

    @Test
    void staysQuietOutsideARunningRace() {
        PlayerStanding first = standing(Status.STARTED, 1, 1);
        assertEquals(List.of(new PlayerMessage.Standing(first)), PlayerSocket.messages(null, first));

        PlayerStanding paused = standing(Status.PAUSED, 1, 1);
        assertEquals(List.of(new PlayerMessage.Standing(paused)),
            PlayerSocket.messages(standing(Status.STARTED, 2, 2), paused));

        PlayerStanding nextRace = new PlayerStanding("race-2", Status.STARTED, 1, 2, 1, 2, 0);
        assertEquals(List.of(new PlayerMessage.Standing(nextRace)),
            PlayerSocket.messages(standing(Status.STARTED, 2, 2), nextRace));
    }

    private static PlayerStanding standing(Status status, int teamPosition, int rankInTeam) {
        return new PlayerStanding("race-1", status, teamPosition, 2, rankInTeam, 2, 100);
    }
}
