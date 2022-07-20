package org.acme;

import java.util.List;
import java.util.Random;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class UsernameGenerator {
    
    private static List<String> names = List.of("Ada", "Grace", "James", "Bjarne", "Dennis", "Guido", "Alan", "Brendan", "Margaret", "Adele", "Ida");
    private static Random r = new Random();

    public String getName() {
        return names.get(r.nextInt(names.size())) + Integer.toString(r.nextInt(10000));
    }

}
