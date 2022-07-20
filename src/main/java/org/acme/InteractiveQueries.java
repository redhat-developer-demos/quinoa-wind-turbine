package org.acme;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;

import static org.apache.kafka.streams.StoreQueryParameters.fromNameAndType;

import java.util.Set;
import java.util.TreeSet;

@ApplicationScoped
public class InteractiveQueries {
    
    @Inject
    KafkaStreams streams;
    
    public Set<UserScore> getScores() {
        final KeyValueIterator<String, Long> all = getUserActionsCount().all();
        
        final Set<UserScore> userScores = new TreeSet<>();
        all.forEachRemaining(kv -> {
            userScores.add(new UserScore(kv.key, kv.value));
        });

        return userScores;
    }

    // Gets the state store 
    private ReadOnlyKeyValueStore<String, Long> getUserActionsCount() {
        while (true) {
            try {
                return streams.store(fromNameAndType(TopologyProducer.COUNT_USER_ACTIONS_STORE, QueryableStoreTypes.keyValueStore()));
            } catch (InvalidStateStoreException e) {
                // ignore, store not ready yet
            }
        }
    }

}
