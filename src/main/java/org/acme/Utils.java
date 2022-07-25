package org.acme;

import io.smallrye.mutiny.Multi;

import java.time.Duration;

final class Utils {
    static <T> Multi<T> withPing(Multi<T> stream, T pingValue) {
        return Multi.createBy().merging()
                .streams(
                        stream,
                        Multi.createFrom().ticks().every(Duration.ofSeconds(30))
                                .onItem().transform(x -> pingValue)
                );
    }
}
