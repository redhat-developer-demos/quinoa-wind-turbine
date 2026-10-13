package org.acme;

import io.quarkus.kafka.client.serialization.ObjectMapperSerde;
import org.acme.GameResource.GameEvent;
import org.acme.PowerResource.Power;
import org.acme.RaceState.PlayerState;
import org.acme.RaceState.Status;
import org.acme.RaceState.TeamState;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.acme.RaceTopology.GAME_EVENTS_TOPIC;
import static org.acme.RaceTopology.POWER_TOPIC;
import static org.acme.RaceTopology.RACE_KEY;
import static org.acme.RaceTopology.RACE_PROGRESS_TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaceTopologyTest {

    // With 15 per tap and 10 taps per player, each player adds 150 to what their team needs to finish
    private static final GameConfig.RaceRules RULES = new GameConfig.RaceRules(15, 10);
    private static final Duration SNAPSHOT_INTERVAL = Duration.ofMillis(250);
    private static final Instant T0 = Instant.parse("2026-10-12T10:00:00Z");

    private TopologyTestDriver driver;
    private TestInputTopic<String, Power> power;
    private TestInputTopic<String, GameEvent> gameEvents;
    private TestOutputTopic<String, RaceState> progress;
    private TestOutputTopic<String, GameEvent> finishEvents;

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "race-topology-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        driver = new TopologyTestDriver(RaceTopology.build(RULES, SNAPSHOT_INTERVAL), props, T0);
        power = driver.createInputTopic(POWER_TOPIC, new StringSerializer(),
            new ObjectMapperSerde<>(Power.class).serializer());
        gameEvents = driver.createInputTopic(GAME_EVENTS_TOPIC, new StringSerializer(),
            new ObjectMapperSerde<>(GameEvent.class).serializer());
        progress = driver.createOutputTopic(RACE_PROGRESS_TOPIC, new StringDeserializer(),
            new ObjectMapperSerde<>(RaceState.class).deserializer());
        finishEvents = driver.createOutputTopic(GAME_EVENTS_TOPIC, new StringDeserializer(),
            new ObjectMapperSerde<>(GameEvent.class).deserializer());
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    @Test
    void computesTotalsAndDistancePerTeam() {
        reset("race-1", 0);
        power("ada", 1, 0, "race-1", 10);
        power("bob", 1, 0, "race-1", 10);
        power("cy", 2, 0, "race-1", 10);
        start(1_000);
        power("ada", 1, 30, "race-1", 1_100);
        power("bob", 1, 45, "race-1", 1_200);
        power("cy", 2, 75, "race-1", 1_300);

        RaceState race = snapshot();
        assertEquals("race-1", race.raceId());
        assertEquals(Status.STARTED, race.status());
        assertEquals(List.of(
            new TeamState(2, 1, 1, 75, 50.0, null),
            new TeamState(1, 2, 2, 75, 25.0, null)), race.teams());
        assertEquals(List.of(
            new PlayerState("cy", 2, 75),
            new PlayerState("bob", 1, 45),
            new PlayerState("ada", 1, 30)), race.players());
    }

    @Test
    void powerOutsideTheRunningRaceOnlyRegistersThePlayer() {
        reset("race-1", 0);
        power("ada", 1, 50, "race-1", 100);
        start(1_000);
        power("ada", 1, 40, "race-0", 1_100);
        power("ada", 1, 20, null, 1_200);

        RaceState race = snapshot();
        assertEquals(List.of(new PlayerState("ada", 1, 0)), race.players());
        assertEquals(0, race.teams().getFirst().power());
    }

    @Test
    void finishesOnceAllTeamsCrossTheLine() {
        reset("race-1", 0);
        power("ada", 1, 0, "race-1", 10);
        power("cy", 2, 0, "race-1", 10);
        start(1_000);
        power("ada", 1, 100, "race-1", 2_000);
        power("cy", 2, 150, "race-1", 3_000);
        assertTrue(finishEvents.isEmpty());

        power("ada", 1, 60, "race-1", 5_000);
        GameEvent finish = finishEvents.readValue();
        assertEquals(GameEvent.FINISH, finish.type());
        assertEquals("race-1", finish.raceId());
        assertEquals(2, finish.data().get("winner"));

        RaceState race = lastProgress();
        assertEquals(Status.FINISHED, race.status());
        assertEquals(2, race.winner());
        assertEquals(List.of(
            new TeamState(2, 1, 1, 150, 100.0, 2_000L),
            new TeamState(1, 2, 1, 160, 100.0, 4_000L)), race.teams());

        power("ada", 1, 60, "race-1", 6_000);
        driver.advanceWallClockTime(SNAPSHOT_INTERVAL);
        assertTrue(finishEvents.isEmpty());
        assertTrue(progress.isEmpty());
    }

    @Test
    void resetInTheMiddleOfARaceStartsANewRace() {
        reset("race-1", 0);
        power("ada", 1, 0, "race-1", 10);
        power("cy", 2, 0, "race-1", 10);
        start(1_000);
        power("ada", 1, 100, "race-1", 2_000);

        reset("race-2", 3_000);
        RaceState afterReset = lastProgress();
        assertEquals("race-2", afterReset.raceId());
        assertEquals(Status.WAITING, afterReset.status());
        assertEquals(List.of(), afterReset.teams());
        assertEquals(List.of(), afterReset.players());

        start(4_000);
        power("ada", 1, 100, "race-1", 4_100);
        power("cy", 2, 30, "race-2", 4_200);
        assertEquals(List.of(new PlayerState("cy", 2, 30), new PlayerState("ada", 1, 0)), snapshot().players());
    }

    @Test
    void pauseStopsTheRaceClock() {
        reset("race-1", 0);
        power("ada", 1, 0, "race-1", 10);
        start(1_000);
        pause(3_000);
        assertEquals(Status.PAUSED, lastProgress().status());
        power("ada", 1, 150, "race-1", 5_000);
        start(10_000);
        power("ada", 1, 150, "race-1", 11_000);

        RaceState race = lastProgress();
        assertEquals(Status.FINISHED, race.status());
        assertEquals(3_000L, race.teams().getFirst().finishTimeMs());
        assertEquals(3_000L, race.elapsedMs());
    }

    @Test
    void supportsAnyNumberOfTeams() {
        reset("race-1", 0);
        for (int team = 1; team <= 4; team++) {
            power("player-" + team, team, 0, "race-1", 10);
        }
        start(1_000);
        for (int team = 1; team <= 4; team++) {
            power("player-" + team, team, 30 * team, "race-1", 1_000 + team);
        }

        List<Integer> positions = snapshot().teams().stream().map(TeamState::id).toList();
        assertEquals(List.of(4, 3, 2, 1), positions);
    }

    @Test
    void publishesSnapshotsOnlyWhenTheRaceChanges() {
        reset("race-1", 0);
        lastProgress();
        driver.advanceWallClockTime(SNAPSHOT_INTERVAL);
        assertTrue(progress.isEmpty());

        // Phones send zero power every few seconds to stay registered
        power("ada", 1, 0, "race-1", 10);
        driver.advanceWallClockTime(SNAPSHOT_INTERVAL);
        assertEquals(1, progress.readValuesToList().size());
        power("ada", 1, 0, "race-1", 3_010);
        driver.advanceWallClockTime(SNAPSHOT_INTERVAL);
        assertTrue(progress.isEmpty());
    }

    @Test
    void ignoresFinishEventsFromOutsideTheTopology() {
        reset("race-1", 0);
        power("ada", 1, 0, "race-1", 10);
        start(1_000);
        gameEvents.pipeInput(RACE_KEY, new GameEvent(GameEvent.FINISH, Map.of("raceId", "race-1")), T0.plusMillis(2_000));

        RaceState race = lastProgress();
        assertEquals(Status.STARTED, race.status());
        assertNull(race.winner());
        assertTrue(finishEvents.readValuesToList().stream().noneMatch(e -> e.raceId() == null));
    }

    private void reset(String raceId, long at) {
        gameEvents.pipeInput(RACE_KEY, new GameEvent(GameEvent.RESET, Map.of("raceId", raceId)), T0.plusMillis(at));
    }

    private void start(long at) {
        gameEvents.pipeInput(RACE_KEY, new GameEvent(GameEvent.START), T0.plusMillis(at));
    }

    private void pause(long at) {
        gameEvents.pipeInput(RACE_KEY, new GameEvent(GameEvent.PAUSE), T0.plusMillis(at));
    }

    private void power(String player, int team, int quantity, String raceId, long at) {
        power.pipeInput(RACE_KEY, new Power(quantity, player, team, raceId), T0.plusMillis(at));
    }

    /**
     * Waits for the next timed snapshot and returns it.
     */
    private RaceState snapshot() {
        driver.advanceWallClockTime(SNAPSHOT_INTERVAL);
        return lastProgress();
    }

    private RaceState lastProgress() {
        List<RaceState> states = progress.readValuesToList();
        assertTrue(!states.isEmpty(), "no race snapshot published");
        return states.getLast();
    }
}
