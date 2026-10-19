package org.acme;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.acme.RaceState.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class CommentaryCoordinatorTest {
    @Test
    void disabledAiUsesTemplatesWithoutCallingTheModel() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Fixture fixture = new Fixture(false, (trigger, race) -> {
            calls.incrementAndGet();
            return "Unused model line";
        }, Duration.ofSeconds(2))) {
            fixture.start();
            Commentary line = fixture.next();
            assertEquals("template", line.source());
            assertEquals("Mountains takes the lead!", line.text());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void validAiOutputIsNormalizedAndPreservesTriggerMetadata() throws Exception {
        try (Fixture fixture = new Fixture(true, (trigger, race) -> "  Mountains\n takes the lead.  ",
                Duration.ofSeconds(2))) {
            fixture.start();
            Commentary line = fixture.next();
            assertEquals("ai", line.source());
            assertEquals("Mountains takes the lead.", line.text());
            assertEquals("race", line.raceId());
            assertEquals("lead", line.trigger());
            assertEquals(1, line.snapshotAt());
            assertTrue(line.createdAt() > 0);
        }
    }

    @Test
    void blankOverlongAndFailedModelOutputFallBackToTheMatchingTemplate() throws Exception {
        List<Commentator> invalidModels = List.of(
            (trigger, race) -> " \n\t ",
            (trigger, race) -> "x".repeat(181),
            (trigger, race) -> { throw new IllegalStateException("Provider unavailable"); });
        for (Commentator ai : invalidModels) {
            try (Fixture fixture = new Fixture(true, ai, Duration.ofSeconds(2))) {
                fixture.start();
                Commentary line = fixture.next();
                assertEquals("template", line.source());
                assertEquals("Mountains takes the lead!", line.text());
            }
        }
    }

    @Test
    void timeoutPublishesOnceAndDoesNotStartAnotherModelWhileTheFirstIsRunning() throws Exception {
        BlockingModel model = new BlockingModel();
        try (Fixture fixture = new Fixture(true, model, Duration.ofMillis(120))) {
            fixture.start();
            model.awaitEntered();
            Commentary fallback = fixture.next();
            assertEquals("template", fallback.source());
            fixture.coordinator.accept(snapshot(STARTED, 2, 10, 20));
            Commentary next = fixture.next();
            assertEquals("template", next.source());
            assertEquals("Beach takes the lead!", next.text());
            assertNotEquals(fallback.id(), next.id());
            assertEquals(1, model.calls.get());
            assertEquals(1, model.maxInFlight.get());
            model.release.countDown();
            model.awaitExited();
            fixture.assertSilent();
        } finally {
            model.release.countDown();
        }
    }

    @Test
    void lateResultsAfterResetPauseOrOwnershipRevocationAreDiscarded() throws Exception {
        for (String action : List.of("reset", "pause", "revoke")) {
            BlockingModel model = new BlockingModel();
            try (Fixture fixture = new Fixture(true, model, Duration.ofSeconds(2))) {
                fixture.start();
                model.awaitEntered();
                switch (action) {
                    case "reset" -> fixture.coordinator.accept(new RaceState("next-race", WAITING,
                        0, 2, null, List.of(), List.of()));
                    case "pause" -> fixture.coordinator.accept(snapshot(PAUSED, 2, 10, 0));
                    case "revoke" -> fixture.coordinator.ownership(false);
                    default -> throw new AssertionError(action);
                }
                model.release.countDown();
                model.awaitExited();
                fixture.assertSilent();
            } finally {
                model.release.countDown();
            }
        }
    }

    @Test
    void finishBypassesABusyModelAndSuppressesItsLateRoutineResult() throws Exception {
        BlockingModel model = new BlockingModel();
        try (Fixture fixture = new Fixture(true, model, Duration.ofSeconds(2))) {
            fixture.start();
            model.awaitEntered();
            fixture.coordinator.accept(snapshot(FINISHED, 2, 100, 70));
            Commentary finish = fixture.next();
            assertEquals("finish", finish.trigger());
            assertEquals("Mountains wins the race!", finish.text());
            assertEquals("template", finish.source());
            assertEquals(1, model.calls.get());
            model.release.countDown();
            model.awaitExited();
            fixture.assertSilent();
        } finally {
            model.release.countDown();
        }
    }

    @Test
    void ownershipReassignmentEstablishesANewBaseline() throws Exception {
        try (Fixture fixture = new Fixture(false, (trigger, race) -> "unused", Duration.ofSeconds(2))) {
            fixture.start();
            fixture.next();
            fixture.coordinator.ownership(false);
            fixture.coordinator.accept(snapshot(STARTED, 2, 70, 50));
            fixture.coordinator.ownership(true);
            fixture.coordinator.accept(snapshot(STARTED, 3, 70, 50));
            fixture.assertSilent();
            fixture.coordinator.accept(snapshot(STARTED, 4, 80, 50));
            Commentary line = fixture.next();
            assertEquals("milestone", line.trigger());
            assertEquals("Mountains passes 75%.", line.text());
        }
    }

    private static RaceState snapshot(RaceState.Status status, long timestamp, double one, double two) {
        return new RaceState("race", status, timestamp * 1_000, timestamp,
            status == FINISHED ? 1 : null, List.of(
                new RaceState.TeamState(1, one >= two ? 1 : 2, 1, 0, one, null),
                new RaceState.TeamState(2, two >= one ? 1 : 2, 1, 0, two, null)), List.of());
    }

    private static final class Fixture implements AutoCloseable {
        final BlockingQueue<Commentary> output = new LinkedBlockingQueue<>();
        final CommentaryCoordinator coordinator;

        Fixture(boolean enabled, Commentator ai, Duration timeout) {
            coordinator = new CommentaryCoordinator(
                new CommentaryTriggerDetector(Duration.ofSeconds(5), 2, 150),
                new TemplateCommentator(id -> id == 1 ? "Mountains" : "Beach"), ai,
                enabled, Duration.ZERO, timeout, 180, output::add);
            coordinator.ownership(true);
            coordinator.accept(RaceState.waiting("race"));
        }

        void start() {
            coordinator.accept(snapshot(STARTED, 1, 10, 0));
        }

        Commentary next() throws InterruptedException {
            Commentary line = output.poll(3, TimeUnit.SECONDS);
            assertNotNull(line, "Expected a commentary line");
            return line;
        }

        void assertSilent() throws InterruptedException {
            assertNull(output.poll(200, TimeUnit.MILLISECONDS), "Unexpected commentary line");
        }

        @Override
        public void close() {
            coordinator.close();
        }
    }

    private static final class BlockingModel implements Commentator {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch exited = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();

        @Override
        public String comment(CommentaryTrigger trigger, RaceState race) {
            calls.incrementAndGet();
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release model");
                return "A late model line.";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                inFlight.decrementAndGet();
                exited.countDown();
            }
        }

        void awaitEntered() throws InterruptedException {
            assertTrue(entered.await(3, TimeUnit.SECONDS), "Expected a model request");
        }

        void awaitExited() throws InterruptedException {
            assertTrue(exited.await(3, TimeUnit.SECONDS), "Expected model request to complete");
        }
    }
}
