package org.acme;

import io.smallrye.mutiny.Multi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.unmodifiableList;

final class Utils {
    public static final List<String> NAMES;

    static  {
        try(final InputStream nameInputStream = Utils.class.getClassLoader().getResourceAsStream("names")) {
            if (nameInputStream == null) {
                throw new IOException("names list not found");
            }
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(nameInputStream))) {
                final List<String> names = new ArrayList<>();
                while(reader.ready()) {
                    names.add(reader.readLine());
                }
                NAMES = unmodifiableList(names);
            }

        } catch (IOException e) {
            throw new IllegalStateException("Error while loading name list", e);
        }
    }

    public static String getNameById(int id) {
        if (id >= NAMES.size()) {
            throw new IllegalArgumentException("This name id is too big: " + id + "/" + NAMES.size());
        }
        return NAMES.get(id);
    }

    static <T> Multi<T> withPing(Multi<T> stream, T pingValue, long intervalSeconds) {
        return Multi.createBy().merging()
                .streams(
                        stream,
                        Multi.createFrom().ticks().every(Duration.ofSeconds(intervalSeconds))
                                .onOverflow().drop()
                                .onItem().transform(x -> pingValue)
                );
    }
}
