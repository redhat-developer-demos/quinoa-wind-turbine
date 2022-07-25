package org.acme;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.state.KeyValueBytesStoreSupplier;
import org.apache.kafka.streams.state.Stores;

@ApplicationScoped
public class TopologyProducer {
    
    private static final String USER_ACTIONS_TOPIC = "user-actions";
    public static final String COUNT_USER_ACTIONS_STORE = "countUserActionsStore"; 

    @Produces
    public Topology getTopCharts() {

        KeyValueBytesStoreSupplier storeSupplier = Stores.persistentKeyValueStore(COUNT_USER_ACTIONS_STORE);
        final StreamsBuilder builder = new StreamsBuilder();
     
        final KStream<String, Integer> userActionsEvents = builder.stream(
                USER_ACTIONS_TOPIC, Consumed.with(Serdes.String(), Serdes.Integer()));

            userActionsEvents
                .groupByKey(Grouped.with(Serdes.String(), Serdes.Integer()))
                .count(
                    Materialized.<String, Long> as(storeSupplier)
                        .withKeySerde(Serdes.String())
                        .withValueSerde(Serdes.Long())
                )
                ;

        return builder.build();

    }
}