package org.acme.game;

import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardStreamTest {
    @Test
    void closeCancelsQuietStreamsWithoutWaitingForAnotherItem() {
        verifyClose(false);
    }

    @Test
    void closeBeforeSubscriptionIsReplayedAndCancelsTheLiveStream() {
        verifyClose(true);
    }

    private static void verifyClose(boolean beforeSubscription) {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        Multi<DashboardSocket.DashboardMessage> quiet = Multi.createFrom()
            .<DashboardSocket.DashboardMessage>emitter(ignored -> {})
            .onCancellation().invoke(() -> cancelled.set(true));
        if (beforeSubscription) closed.complete(null);
        DashboardSocket.untilClosed(quiet, closed).subscribe().with(
            item -> { throw new AssertionError("Close signal must not be sent as a message"); },
            failure -> { throw new AssertionError(failure); }, () -> completed.set(true));
        if (!beforeSubscription) closed.complete(null);
        assertTrue(completed.get());
        assertTrue(cancelled.get());
    }
}
