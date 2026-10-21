package org.acme;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * A look for the race that an admin can apply between races: team names, colors and cars. It is
 * broadcast as a theme game event so every replica and every connected client switches together.
 * The track stays the painted default for now; track theming comes with the generated-track work.
 */
@RegisterForReflection
public record RaceTheme(String id, String name, List<ThemeTeam> teams) {

    @RegisterForReflection
    public record ThemeTeam(int id, String name, String color, String car, String carTint) {
    }
}
