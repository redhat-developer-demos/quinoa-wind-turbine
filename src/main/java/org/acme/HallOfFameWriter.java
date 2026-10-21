package org.acme;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.kafka.KafkaConsumerRebalanceListener;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.RaceState.Status;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import java.time.Instant;
import java.util.Collection;

/**
 * Captures a finished race into the hall of fame. One member of a shared consumer group owns race partition zero and
 * writes it, so five replicas don't each record the same finish. {@link HallOfFameStore#record} is idempotent per race
 * as a backstop for the brief window when a rebalance leaves two owners.
 */
@ApplicationScoped
@Identifier("hall-of-fame")
public class HallOfFameWriter implements KafkaConsumerRebalanceListener {

    private final HallOfFameStore store;
    private final GameConfig config;
    private volatile boolean owner;

    public HallOfFameWriter(HallOfFameStore store, GameConfig config) {
        this.store = store;
        this.config = config;
    }

    @Incoming("hall-of-fame-race-in")
    @Blocking
    public void snapshot(RaceState state) {
        if (!owner || state == null || state.status() != Status.FINISHED) {
            return;
        }
        store.record(HallOfFameEntry.from(state, config, Instant.now().toEpochMilli(),
            config.hallOfFame().topPlayers()));
    }

    @Override
    public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        owner = !partitions.isEmpty();
    }

    @Override
    public void onPartitionsRevoked(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        owner = false;
    }

    @Override
    public void onPartitionsLost(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        owner = false;
    }
}
