package org.acme;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.reactive.messaging.kafka.KafkaConsumerRebalanceListener;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.util.Collection;

/** One member of the shared commentary consumer group owns race partition zero. */
@ApplicationScoped
@Identifier("commentary")
public class CommentaryGenerator implements KafkaConsumerRebalanceListener {
    private static final Logger LOG = Logger.getLogger(CommentaryGenerator.class);
    private final CommentaryCoordinator coordinator;

    public CommentaryGenerator(GameConfig config, TemplateCommentator template, AiCommentator ai,
                               @Channel("commentary-out") Emitter<Commentary> output) {
        var settings = config.commentary();
        coordinator = new CommentaryCoordinator(new CommentaryTriggerDetector(settings.surgeWindow(),
            settings.surgeMultiplier(), (long) settings.surgeMinTaps() * config.tapPower()), template, ai,
            config.ai().enabled(), settings.interval(), settings.timeout(), settings.maxLength(),
            line -> output.send(RaceTopology.raceMessage(line).withNack(failure -> {
                LOG.error("Kafka rejected commentary", failure);
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            })));
    }

    @Incoming("commentary-race-in")
    public void snapshot(RaceState state) {
        coordinator.accept(state);
    }

    @Override
    public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        // Consume the latest snapshot as a baseline, rather than narrating the backlog after a restart.
        var ends = consumer.endOffsets(partitions);
        ends.forEach((partition, end) -> consumer.seek(partition, Math.max(0, end - 1)));
        coordinator.ownership(!partitions.isEmpty());
    }

    @Override
    public void onPartitionsRevoked(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        coordinator.ownership(false);
    }

    @Override
    public void onPartitionsLost(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        coordinator.ownership(false);
    }

    @PreDestroy
    void stop() {
        coordinator.close();
    }
}
