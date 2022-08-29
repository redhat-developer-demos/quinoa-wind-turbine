package org.acme;

import io.smallrye.mutiny.Multi;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Collections.shuffle;
import static java.util.Collections.unmodifiableList;

final class Utils {

    private static final Set<String> NAMES = Set.of("Ada", "Grace", "James", "Bjarne", "Dennis", "Guido", "Alan", "Brendan", "Margaret", "Adele", "Ida", "Lira", "Finnegan", "Bruns", "Bowie", "Coyne", "Fontaine", "Rincon", "East", "Willett", "Ivy", "Jameson", "Batista", "Rinehart", "Callaway", "Whipple", "Harms", "Humphreys", "Paulsen", "Colby", "Haggerty", "Seibert", "Mccloskey", "Laney", "Stepp", "Gaytan", "Betz", "Leigh", "Mears");
    public static final List<String> COMBINED_NAMES;

    static  {
        List<String> prod = cartesianProduct(NAMES, NAMES).stream()
                .filter(s -> !Objects.equals(s.get(0), s.get(1)))
                .map(s -> String.join("-", s.get(0), s.get(1)))
                .collect(Collectors.toList());
        shuffle(prod);
        COMBINED_NAMES = unmodifiableList(prod);
    }

    public static String getNameById(int id) {
        if (id >= COMBINED_NAMES.size()) {
            throw new IllegalArgumentException("This name id is too big: " + id + "/" + COMBINED_NAMES.size());
        }
        return COMBINED_NAMES.get(id);
    }

    static <T> Multi<T> withPing(Multi<T> stream, T pingValue) {
        return Multi.createBy().merging()
                .streams(
                        stream.onOverflow().buffer(500000),
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
