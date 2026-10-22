package org.acme.theme;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

import org.acme.theme.RaceTheme.ThemeTeam;
import org.acme.theme.RaceTheme.Track;
import org.acme.theme.RaceTheme.Track.ViewBox;

/**
 * Built-in themes an admin can apply without an LLM. Each is already valid and keeps two teams with
 * contrasting colors. A team's color paints the F1 car body and its optional accent paints the livery
 * stripes and wing elements (null keeps them white). A null track leaves the current track in place;
 * the classic preset sets a track.
 */
@ApplicationScoped
public class PresetThemes {

    // The hand-painted parkland track with its two hand-drawn lane paths.
    private static final Track PARKLAND = new Track("race-track-1.png", new ViewBox(777, 518), null, null, List.of(
        "M 745 243 Q 750 340 750 420 Q 695 515 560 470 Q 490 350 420 360 Q 350 350 310 420 Q 240 510 180 450 "
            + "L 80 270 Q 70 200 120 150 L 270 90 Q 310 70 350 90 L 410 110 Q 470 110 510 80 L 570 40 Q 600 20 640 20 "
            + "Q 670 30 700 50 Q 720 80 720 110 Q 740 180 750 320 ",
        "M 720 230 Q 735 335 720 430 Q 670 490 570 440 Q 505 335 420 330 Q 330 330 290 410 Q 240 490 190 415 "
            + "L 105 260 Q 90 195 150 160 L 280 110 Q 310 90 350 110 L 420 130 Q 480 130 530 100 L 590 60 Q 610 40 640 40 "
            + "Q 690 80 705 115 Q 720 180 720 320 "));

    // The painted Swiss Alps track. It has the same 3:2 shape as the parkland, and its two lane paths
    // follow the road in the same 777x518 space, shifted left on the start straight to sit on the grid.
    private static final Track SWISS_ALPS = new Track("swiss-alps-track.png",
        new ViewBox(777, 518), null, null, List.of(
        "M 733 243 Q 738 340 738 420 Q 695 515 560 470 Q 490 350 420 360 Q 350 350 310 420 Q 240 510 180 450 "
            + "L 80 270 Q 70 200 120 150 L 270 90 Q 310 70 350 90 L 410 110 Q 470 110 510 80 L 570 40 Q 600 20 640 20 "
            + "Q 670 30 700 50 Q 720 80 720 110 Q 728 180 738 320 ",
        "M 710 230 Q 725 335 710 430 Q 670 490 570 440 Q 505 335 420 330 Q 330 330 290 410 Q 240 490 190 415 "
            + "L 105 260 Q 90 195 150 160 L 280 110 Q 310 90 350 110 L 420 130 Q 480 130 530 100 L 590 60 Q 610 40 640 40 "
            + "Q 690 80 705 115 Q 710 180 710 320 "));

    public List<RaceTheme> all() {
        return List.of(
            new RaceTheme("classic", "Mountains vs Beach", List.of(
                new ThemeTeam(1, "Mountains", "#9e9e9e", "car-grey", null),
                new ThemeTeam(2, "Beach", "#29b6f6", "car-blue-convertible", null)), PARKLAND),
           new RaceTheme("ferrari-mercedes", "Ferrari vs Mercedes", List.of(
               new ThemeTeam(1, "Ferrari", "#dc0000", "car-red", "#ffffff", "ibm"),
               new ThemeTeam(2, "Mercedes", "#c0c7cc", "car-grey", "#00d2be")), SWISS_ALPS),
           new RaceTheme("forest-sun", "Forest vs Sun", List.of(
               new ThemeTeam(1, "Forest", "#2e7d32", "car-grey", "#fdd835"),
               new ThemeTeam(2, "Sunshine", "#fdd835", "car-orange", "#2e7d32")), null),
           new RaceTheme("royal-teal", "Royal vs Teal", List.of(
               new ThemeTeam(1, "Royal", "#6a1b9a", "car-barbie", "#ffd54f"),
               new ThemeTeam(2, "Lagoon", "#00acc1", "car-blue-convertible", null)), null),
           new RaceTheme("silver-arrows", "Silver vs Black", List.of(
               new ThemeTeam(1, "Silver", "#cfd8dc", "car-grey", "#00bfa5"),
               new ThemeTeam(2, "Shadow", "#212121", "car-classic-black", "#e53935")), null),
            new RaceTheme("fire-ice", "Fire vs Ice", List.of(
                new ThemeTeam(1, "Blaze", "#e53935", "car-red", null),
                new ThemeTeam(2, "Frost", "#42a5f5", "car-blue-convertible", null)), null),
            new RaceTheme("pink-black", "Pink vs Black", List.of(
                new ThemeTeam(1, "Hot Pink", "#ec407a", "car-barbie", null),
                new ThemeTeam(2, "Midnight", "#424242", "car-classic-black", null)), null),
            new RaceTheme("citrus", "Orange vs Lime", List.of(
                new ThemeTeam(1, "Tangerine", "#fb8c00", "car-orange", null),
                new ThemeTeam(2, "Lime", "#7cb342", "car-grey", null)), null));
    }
}
