package org.acme;

import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Bounds generation independently of snapshot frequency and discards results after lifecycle changes. */
final class CommentaryCoordinator implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(CommentaryCoordinator.class);
    private final CommentaryTriggerDetector detector;
    private final Commentator template;
    private final Commentator ai;
    private final boolean aiEnabled;
    private final long intervalMs;
    private final long timeoutMs;
    private final int maxLength;
    private final Consumer<Commentary> output;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("commentary-timer").factory());
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
    private RaceState latest;
    private CommentaryTrigger pending;
    private Request active;
    private boolean modelBusy;
    private boolean closed;
    private boolean owner;
    private long epoch;
    private long lastStarted;

    CommentaryCoordinator(CommentaryTriggerDetector detector, Commentator template, Commentator ai,
                          boolean aiEnabled, Duration interval, Duration timeout, int maxLength,
                          Consumer<Commentary> output) {
        if (interval.isNegative() || timeout.isNegative() || timeout.isZero() || maxLength < 32) {
            throw new IllegalArgumentException("Commentary needs a nonnegative interval, positive timeout and max length >=32");
        }
        this.detector = detector;
        this.template = template;
        this.ai = ai;
        this.aiEnabled = aiEnabled;
        this.intervalMs = interval.toMillis();
        this.timeoutMs = Math.max(1, timeout.toMillis());
        this.maxLength = maxLength;
        this.output = output;
        timer.scheduleWithFixedDelay(this::flush, 50, 50, TimeUnit.MILLISECONDS);
    }

    synchronized void ownership(boolean owner) {
        this.owner = owner;
        invalidate();
        latest = null;
        detector.reset();
        lastStarted = 0;
    }

    synchronized void accept(RaceState race) {
        if (closed || !owner) return;
        if (latest != null && Objects.equals(latest.raceId(), race.raceId())
                && race.updatedAt() <= latest.updatedAt()) return;
        if (latest == null || !Objects.equals(latest.raceId(), race.raceId())
                || latest.status() != race.status() && race.status() != RaceState.Status.STARTED) {
            invalidate();
        }
        latest = race;
        var triggers = detector.accept(race);
        if (race.status() == RaceState.Status.PAUSED || race.status() == RaceState.Status.WAITING) return;
        triggers.stream().max(Comparator.comparingInt(CommentaryTrigger::priority)).ifPresent(trigger -> {
            if (pending == null || trigger.priority() >= pending.priority()) pending = trigger;
        });
        if (pending != null && pending.type().equals("finish")) flush();
    }

    private void invalidate() {
        epoch++;
        pending = null;
        if (active != null && active.deadline != null) active.deadline.cancel(false);
        active = null;
    }

    private synchronized void flush() {
        if (closed || !owner || pending == null || latest == null) return;
        boolean finish = pending.type().equals("finish");
        if (!finish && (active != null || System.currentTimeMillis() - lastStarted < intervalMs)) return;
        Request request = new Request(pending, latest, epoch);
        pending = null;
        lastStarted = System.currentTimeMillis();
        if (!aiEnabled || modelBusy) {
            // A finish must not wait behind an outstanding request, even after that request's timeout.
            publish(request, template.comment(request.trigger, request.race), "template");
            return;
        }
        modelBusy = true;
        active = request;
        request.deadline = timer.schedule(() -> complete(request, null), timeoutMs, TimeUnit.MILLISECONDS);
        worker.submit(() -> {
            String text = null;
            try {
                text = ai.comment(request.trigger, request.race);
            } catch (RuntimeException e) {
                // Don't log provider exception messages, which may include requests or credentials.
                LOG.debugf("Commentary uses template after %s", e.getClass().getSimpleName());
            } finally {
                synchronized (this) {
                    modelBusy = false;
                    complete(request, text);
                }
            }
        });
    }

    private synchronized void complete(Request request, String text) {
        if (active != request) return;
        active = null;
        request.deadline.cancel(false);
        String normalized = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        boolean valid = !normalized.isBlank() && normalized.codePointCount(0, normalized.length()) <= maxLength;
        publish(request, valid ? normalized : template.comment(request.trigger, request.race), valid ? "ai" : "template");
    }

    private void publish(Request request, String text, String source) {
        if (closed || !owner || request.epoch != epoch) return;
        if (!request.trigger.type().equals("finish") && latest.status() != RaceState.Status.STARTED) return;
        // Templates containing long player names have the same display limit as model output.
        int end = text.offsetByCodePoints(0, Math.min(maxLength, text.codePointCount(0, text.length())));
        try {
            output.accept(new Commentary(request.trigger.id(), request.trigger.raceId(), request.trigger.type(),
                request.trigger.snapshotAt(), System.currentTimeMillis(), text.substring(0, end), source));
        } catch (RuntimeException e) {
            LOG.error("Cannot publish commentary", e);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        invalidate();
        timer.shutdownNow();
        worker.shutdownNow();
    }

    private static final class Request {
        final CommentaryTrigger trigger;
        final RaceState race;
        final long epoch;
        ScheduledFuture<?> deadline;

        Request(CommentaryTrigger trigger, RaceState race, long epoch) {
            this.trigger = trigger;
            this.race = race;
            this.epoch = epoch;
        }
    }
}
