package org.acme;

import java.security.SecureRandom;
import java.util.List;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class UsernameGenerator {
    
    private final List<String> names = List.of("Ada", "Grace", "James", "Bjarne", "Dennis", "Guido", "Alan", "Brendan", "Margaret", "Adele", "Ida", "Lira", "Finnegan", "Bruns", "Bowie", "Coyne", "Fontaine", "Rincon", "East", "Willett", "Ivy", "Jameson", "Batista", "Rinehart", "Callaway", "Whipple", "Harms", "Humphreys", "Paulsen", "Colby", "Haggerty", "Seibert", "Mccloskey", "Laney", "Stepp", "Gaytan", "Betz", "Leigh", "Mears");
    private final SecureRandom random = new SecureRandom();

    public String getName() {
        return names.get(random.nextInt(names.size())) + "-" + names.get(random.nextInt(names.size()));
    }

}
