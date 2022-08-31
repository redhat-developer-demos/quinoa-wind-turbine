package org.acme;

import io.smallrye.mutiny.Multi;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Collections.unmodifiableList;

final class Utils {
    private static final Logger LOG = Logger.getLogger(Utils.class);
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

    static <T> Multi<T> withPing(Multi<T> stream, T pingValue) {
        return Multi.createBy().merging()
                .streams(
                        stream.onOverflow().buffer(5000),
                        Multi.createFrom().ticks().every(Duration.ofSeconds(30))
                                .onOverflow().drop()
                                .onItem().transform(x -> pingValue)
                );
    }

    private static Set<List<String>> cartesianProduct(Set<String>... sets) {
        if (sets.length < 2)
            throw new IllegalArgumentException(
                    "Can't have a product of fewer than two sets (got " +
                            sets.length + ")");

        return cartesianProduct(0, sets);
    }

    private static Set<List<String>> cartesianProduct(int index, Set<String>... sets) {
        Set<List<String>> ret = new HashSet<>();
        if (index == sets.length) {
            ret.add(new ArrayList<>());
        } else {
            for (String str : sets[index]) {
                for (List<String> set : cartesianProduct(index+1, sets)) {
                    set.add(str);
                    ret.add(set);
                }
            }
        }
        return ret;
    }
}
