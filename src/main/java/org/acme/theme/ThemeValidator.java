package org.acme.theme;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a theme before it is applied, so a bad admin entry or AI output keeps the current theme
 * instead of breaking the board. Team colors may be hex or CSS color names; contrast is only checked
 * when both are hex, since a name can't be compared without a lookup table.
 */
@ApplicationScoped
public class ThemeValidator {

    static final Set<String> ALLOWED_CARS = Set.of(
        "car-barbie", "car-blue-convertible", "car-classic-black", "car-grey", "car-orange", "car-red");
    static final Set<String> ALLOWED_LOGOS = Set.of("ibm");
    static final Pattern COLOR_PATTERN = Pattern.compile("#[0-9a-fA-F]{3}|#[0-9a-fA-F]{6}|[a-zA-Z]{3,20}");
    static final int MAX_NAME_LENGTH = 24;
    static final double MIN_COLOR_DISTANCE = 60;

    public List<String> validate(RaceTheme theme, int expectedTeams) {
        List<String> errors = new ArrayList<>();
        if (theme == null || theme.teams() == null || theme.teams().isEmpty()) {
            errors.add("A theme needs teams");
            return errors;
        }
        if (theme.teams().size() != expectedTeams) {
            errors.add("A theme needs exactly " + expectedTeams + " teams");
        }
        Set<Integer> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (RaceTheme.ThemeTeam team : theme.teams()) {
            if (!ids.add(team.id())) {
                errors.add("Duplicate team id " + team.id());
            }
            if (team.name() == null || team.name().isBlank()) {
                errors.add("A team name is blank");
            } else {
                if (team.name().length() > MAX_NAME_LENGTH) {
                    errors.add("Team name is longer than " + MAX_NAME_LENGTH + " characters: " + team.name());
                }
                if (!names.add(team.name().toLowerCase())) {
                    errors.add("Duplicate team name: " + team.name());
                }
            }
            if (team.color() == null || team.color().isBlank()) {
                errors.add("A team color is blank");
            }
            if (team.accent() != null && !COLOR_PATTERN.matcher(team.accent()).matches()) {
                errors.add("Invalid accent color: " + team.accent());
            }
            if (team.logo() != null && !ALLOWED_LOGOS.contains(team.logo())) {
                errors.add("Unknown logo: " + team.logo());
            }
            if (team.car() == null || !ALLOWED_CARS.contains(team.car())) {
                errors.add("Unknown car: " + team.car());
            }
        }
        if (theme.teams().size() == 2) {
            Double distance = colorDistance(theme.teams().get(0).color(), theme.teams().get(1).color());
            if (distance != null && distance < MIN_COLOR_DISTANCE) {
                errors.add("The two team colors are too similar to tell apart");
            }
        }
        return errors;
    }

    // Euclidean RGB distance between two colors, or null when either is not hex. This rejects nearly
    // identical colors while accepting distinct hues such as red and blue, which a luminance-contrast
    // check would wrongly fail because they can have similar brightness.
    private Double colorDistance(String a, String b) {
        int[] ca = parseHex(a);
        int[] cb = parseHex(b);
        if (ca == null || cb == null) {
            return null;
        }
        double dr = ca[0] - cb[0];
        double dg = ca[1] - cb[1];
        double db = ca[2] - cb[2];
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private int[] parseHex(String color) {
        if (color == null || !color.startsWith("#")) {
            return null;
        }
        String hex = color.substring(1);
        if (hex.length() == 3) {
            hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
        }
        if (hex.length() != 6) {
            return null;
        }
        try {
            return new int[] {
                Integer.parseInt(hex.substring(0, 2), 16),
                Integer.parseInt(hex.substring(2, 4), 16),
                Integer.parseInt(hex.substring(4, 6), 16),
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
