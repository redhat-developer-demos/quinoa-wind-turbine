package org.acme.theme;

import org.acme.theme.RaceTheme.ThemeTeam;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeValidatorTest {

    private final ThemeValidator validator = new ThemeValidator();

    private RaceTheme theme(ThemeTeam... teams) {
        return new RaceTheme("t", "Test", List.of(teams), null);
    }

    private ThemeTeam team(int id, String name, String color, String car) {
        return new ThemeTeam(id, name, color, car, null);
    }

    @Test
    void acceptsAValidTheme() {
        List<String> errors = validator.validate(theme(
            team(1, "Falcons", "#c8102e", "car-red"),
            team(2, "Peaches", "#ffa270", "car-orange")), 2);
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void acceptsAnAccentColor() {
        List<String> errors = validator.validate(theme(
            new ThemeTeam(1, "Falcons", "#c8102e", "car-red", "#fdd835"),
            new ThemeTeam(2, "Peaches", "#ffa270", "car-orange", "gold")), 2);
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void rejectsAnInvalidAccentColor() {
        assertFalse(validator.validate(theme(
            new ThemeTeam(1, "Falcons", "#c8102e", "car-red", "url(#x)"),
            team(2, "Peaches", "#ffa270", "car-orange")), 2).isEmpty());
    }

    @Test
    void acceptsAKnownLogoAndRejectsAnUnknownOne() {
        assertTrue(validator.validate(theme(
            new ThemeTeam(1, "Falcons", "#c8102e", "car-red", null, "ibm"),
            team(2, "Peaches", "#ffa270", "car-orange")), 2).isEmpty());
        assertTrue(validator.validate(theme(
            new ThemeTeam(1, "Falcons", "#c8102e", "car-red", null, "redhat"),
            team(2, "Peaches", "#ffa270", "car-orange")), 2).isEmpty());
        assertFalse(validator.validate(theme(
            new ThemeTeam(1, "Falcons", "#c8102e", "car-red", null, "<script>"),
            team(2, "Peaches", "#ffa270", "car-orange")), 2).isEmpty());
    }

    @Test
    void everyPresetIsValid() {
        for (RaceTheme preset : new PresetThemes().all()) {
            List<String> errors = validator.validate(preset, 2);
            assertTrue(errors.isEmpty(), preset.id() + ": " + errors);
        }
    }

    @Test
    void acceptsCssColorNamesWithoutAContrastCheck() {
        List<String> errors = validator.validate(theme(
            team(1, "Mountains", "grey", "car-grey"),
            team(2, "Beach", "skyblue", "car-blue-convertible")), 2);
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void rejectsTheWrongTeamCount() {
        assertFalse(validator.validate(theme(team(1, "Solo", "#c8102e", "car-red")), 2).isEmpty());
    }

    @Test
    void rejectsABlankName() {
        assertFalse(validator.validate(theme(
            team(1, " ", "#c8102e", "car-red"),
            team(2, "Peaches", "#ffa270", "car-orange")), 2).isEmpty());
    }

    @Test
    void rejectsAnOverlongName() {
        assertFalse(validator.validate(theme(
            team(1, "A really really really long team name", "#c8102e", "car-red"),
            team(2, "Peaches", "#ffa270", "car-orange")), 2).isEmpty());
    }

    @Test
    void rejectsAnUnknownCar() {
        assertFalse(validator.validate(theme(
            team(1, "Falcons", "#c8102e", "car-spaceship"),
            team(2, "Peaches", "#ffa270", "car-orange")), 2).isEmpty());
    }

    @Test
    void rejectsColorsThatAreTooSimilar() {
        assertFalse(validator.validate(theme(
            team(1, "Red", "#ff0000", "car-red"),
            team(2, "Also Red", "#fe0000", "car-orange")), 2).isEmpty());
    }
}
