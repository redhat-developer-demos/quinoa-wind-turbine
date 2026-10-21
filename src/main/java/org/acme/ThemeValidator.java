package org.acme;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks a theme before it is applied, so a bad admin entry or AI output keeps the current theme
 * instead of breaking the board. Team colors may be hex or CSS color names; contrast is only checked
 * when both are hex, since a name can't be compared without a lookup table.
 */
@ApplicationScoped
public class ThemeValidator {

    static final Set<String> ALLOWED_CARS = Set.of(
        "car-barbie", "car-blue-convertible", "car-classic-black", "car-grey", "car-orange", "car-red");
    static final int MAX_NAME_LENGTH = 24;
    static final double MIN_CONTRAST = 1.2;

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
            if (team.car() == null || !ALLOWED_CARS.contains(team.car())) {
                errors.add("Unknown car: " + team.car());
            }
        }
        if (theme.teams().size() == 2) {
            Double contrast = contrastRatio(theme.teams().get(0).color(), theme.teams().get(1).color());
            if (contrast != null && contrast < MIN_CONTRAST) {
                errors.add("The two team colors are too similar to tell apart");
            }
        }
        return errors;
    }

    // WCAG contrast ratio between two colors, or null when either is not a hex color.
    private Double contrastRatio(String a, String b) {
        Double la = relativeLuminance(a);
        Double lb = relativeLuminance(b);
        if (la == null || lb == null) {
            return null;
        }
        double lighter = Math.max(la, lb);
        double darker = Math.min(la, lb);
        return (lighter + 0.05) / (darker + 0.05);
    }

    private Double relativeLuminance(String color) {
        int[] rgb = parseHex(color);
        if (rgb == null) {
            return null;
        }
        double[] linear = new double[3];
        for (int i = 0; i < 3; i++) {
            double c = rgb[i] / 255.0;
            linear[i] = c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
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
