package org.acme;

import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import io.smallrye.mutiny.subscription.Cancellable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.acme.RaceState.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class CommentaryFeedTest {
    @Test
    void replayWaitsForTheCurrentRaceSnapshotAndThenReachesLiveAndReconnectClients() {
        try (Fixture fixture = new Fixture()) {
            fixture.game("current", "start");
            Commentary line = line("current", "lead", 20);
            fixture.feed.receive(line);
            assertTrue(fixture.feed.latest().isEmpty());
            assertTrue(fixture.delivered.isEmpty());

            fixture.race("current", STARTED, 20);
            assertEquals(List.of(line), fixture.delivered);
            assertEquals(line, fixture.feed.latest().orElseThrow());
            fixture.race("current", STARTED, 21);
            fixture.game("current", "start");
            assertEquals(List.of(line), fixture.delivered);
        }
    }

    @Test
    void commentaryAheadOfStartAndResumeWaitsForItsSourceSnapshot() {
        try (Fixture fixture = new Fixture()) {
            fixture.game("current", "start");
            fixture.race("current", WAITING, 10);
            Commentary start = line("current", "lead", 20);
            fixture.feed.receive(start);
            assertTrue(fixture.delivered.isEmpty());
            fixture.race("current", STARTED, 15);
            assertTrue(fixture.delivered.isEmpty());
            fixture.race("current", STARTED, 20);
            assertEquals(List.of(start), fixture.delivered);

            fixture.game("current", "pause");
            fixture.race("current", PAUSED, 30);
            Commentary resumed = line("current", "milestone", 40);
            fixture.feed.receive(resumed);
            fixture.game("current", "start");
            assertTrue(fixture.feed.latest().isEmpty());
            fixture.race("current", STARTED, 35);
            assertEquals(List.of(start), fixture.delivered);
            fixture.race("current", STARTED, 40);
            assertEquals(List.of(start, resumed), fixture.delivered);
            assertEquals(resumed, fixture.feed.latest().orElseThrow());
        }
    }

    @Test
    void delayedPrePauseCommentaryNeverBecomesVisibleAfterResume() {
        try (Fixture fixture = new Fixture()) {
            fixture.game("current", "start");
            fixture.race("current", STARTED, 10);
            fixture.game("current", "pause");
            fixture.race("current", PAUSED, 30);
            fixture.feed.receive(line("current", "lead", 25));
            fixture.game("current", "start");
            fixture.race("current", STARTED, 40);
            assertTrue(fixture.feed.latest().isEmpty());
            assertTrue(fixture.delivered.isEmpty());

            fixture.feed.receive(line("current", "milestone", 30));
            assertTrue(fixture.delivered.isEmpty());
            Commentary fresh = line("current", "milestone", 40);
            fixture.feed.receive(fresh);
            assertEquals(List.of(fresh), fixture.delivered);
        }
    }

    @Test
    void duplicatesOlderSnapshotsAndOtherRacesDoNotProduceDelivery() {
        try (Fixture fixture = new Fixture()) {
            fixture.game("current", "start");
            fixture.race("current", STARTED, 30);
            Commentary current = line("current", "milestone", 25);
            fixture.feed.receive(current);
            fixture.feed.receive(current);
            fixture.feed.receive(line("current", "lead", 20));
            fixture.feed.receive(line("old", "finish", 50));
            assertEquals(List.of(current), fixture.delivered);
            assertEquals(current, fixture.feed.latest().orElseThrow());

            fixture.game("next", "reset");
            assertTrue(fixture.feed.latest().isEmpty());
            fixture.race("next", WAITING, 40);
            fixture.feed.receive(line("current", "finish", 60));
            assertTrue(fixture.feed.latest().isEmpty());
            assertEquals(List.of(current), fixture.delivered);
        }
    }

    @Test
    void gamePauseImmediatelyHidesLatestAndFinishReplacesRoutineCommentary() {
        try (Fixture fixture = new Fixture()) {
            fixture.game("current", "start");
            fixture.race("current", STARTED, 10);
            Commentary lead = line("current", "lead", 10);
            fixture.feed.receive(lead);
            fixture.game("current", "pause");
            assertTrue(fixture.feed.latest().isEmpty());
            fixture.race("current", PAUSED, 20);
            fixture.game("current", "start");
            fixture.race("current", FINISHED, 30);
            assertTrue(fixture.feed.latest().isEmpty());
            Commentary finish = line("current", "finish", 30);
            fixture.feed.receive(finish);
            assertEquals(List.of(lead, finish), fixture.delivered);
            assertEquals(finish, fixture.feed.latest().orElseThrow());
        }
    }

    @Test
    void closingSubscriptionsStopsReconciliation() {
        try (Fixture fixture = new Fixture()) {
            fixture.game("current", "start");
            fixture.feed.receive(line("current", "lead", 20));
            fixture.feed.stop();
            fixture.race("current", STARTED, 20);
            assertTrue(fixture.delivered.isEmpty());
        }
    }

    private static Commentary line(String raceId, String trigger, long timestamp) {
        return new Commentary(raceId + ":" + trigger + ":" + timestamp, raceId, trigger,
            timestamp, timestamp + 1, "Team 1 commentary", "template");
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicReference<RaceState> state = new AtomicReference<>(
            new RaceState("previous", STARTED, 0, 10, null, List.of(), List.of()));
        final AtomicReference<String> currentRace = new AtomicReference<>("previous");
        final AtomicReference<String> status = new AtomicReference<>("start");
        final BroadcastProcessor<RaceState> raceEvents = BroadcastProcessor.create();
        final BroadcastProcessor<GameResource.GameEvent> gameEvents = BroadcastProcessor.create();
        final List<Commentary> delivered = new ArrayList<>();
        final CommentaryFeed feed = new CommentaryFeed(state::get, currentRace::get, status::get,
            raceEvents, gameEvents);
        final Cancellable subscription;

        Fixture() {
            feed.watchRace();
            subscription = feed.updates().subscribe().with(delivered::add);
        }

        void race(String raceId, RaceState.Status raceStatus, long timestamp) {
            RaceState next = new RaceState(raceId, raceStatus, 0, timestamp,
                raceStatus == FINISHED ? 1 : null, List.of(), List.of());
            state.set(next);
            raceEvents.onNext(next);
        }

        void game(String raceId, String type) {
            currentRace.set(raceId);
            status.set(type);
            gameEvents.onNext(new GameResource.GameEvent(type, Map.of("raceId", raceId)));
        }

        @Override
        public void close() {
            subscription.cancel();
            feed.stop();
        }
    }
}
