package org.acme;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

import org.acme.RaceTheme.ThemeTeam;

/**
 * Built-in themes an admin can apply without an LLM. Each is already valid, uses the existing car
 * sprites, and keeps two teams with contrasting colors.
 */
@ApplicationScoped
public class PresetThemes {

    public List<RaceTheme> all() {
        return List.of(
            new RaceTheme("classic", "Mountains vs Beach", List.of(
                new ThemeTeam(1, "Mountains", "#9e9e9e", "car-grey", null),
                new ThemeTeam(2, "Beach", "#29b6f6", "car-blue-convertible", null))),
            new RaceTheme("fire-ice", "Fire vs Ice", List.of(
                new ThemeTeam(1, "Blaze", "#e53935", "car-red", null),
                new ThemeTeam(2, "Frost", "#42a5f5", "car-blue-convertible", null))),
            new RaceTheme("pink-black", "Pink vs Black", List.of(
                new ThemeTeam(1, "Hot Pink", "#ec407a", "car-barbie", null),
                new ThemeTeam(2, "Midnight", "#424242", "car-classic-black", null))),
            new RaceTheme("citrus", "Orange vs Lime", List.of(
                new ThemeTeam(1, "Tangerine", "#fb8c00", "car-orange", null),
                new ThemeTeam(2, "Lime", "#7cb342", "car-grey", null))));
    }
}
