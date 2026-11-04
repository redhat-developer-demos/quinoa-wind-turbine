package org.acme.theme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The painted venue tracks depend on hand-traced lane paths, which no other test can see and which
 * only show up as wrong on a screen during a race.
 */
class PresetThemesTest {

    @Test
    void theKubeConPresetPaintsTheWinterTrackWithTwoClosedLanes() {
        RaceTheme theme = new PresetThemes().all().stream()
            .filter(preset -> preset.id().equals("kubecon-slc"))
            .findFirst().orElseThrow(() -> new AssertionError("the kubecon-slc preset is missing"));

        assertEquals("KubeCon Salt Lake City", theme.name());
        RaceTheme.Track track = theme.track();
        assertNotNull(track);
        assertEquals("kubcon-salt-lake-city-race-track-2026-winter.png", track.image());
        assertEquals(new RaceTheme.Track.ViewBox(777, 518), track.viewBox());
        assertEquals(2, track.lanes().size());

        for (String lane : track.lanes()) {
            assertTrue(lane.startsWith("M "), lane);
            // Each lane is a closed loop: it ends on the point it starts from, so a finished car stops
            // on the start/finish line rather than short of it.
            assertArrayEquals(firstPoint(lane), lastPoint(lane), lane);
        }
        assertNotEquals(track.lanes().get(0), track.lanes().get(1));
    }

    /**
     * Blue and purple passed the validator's 60-point floor at 95 but read as one color on a
     * projector, so this preset is held to a wider gap. Blue and white sit around 254, and the
     * test also pins the winter kit: the cars are the ski variant, not the slick tyres.
     */
    @Test
    void theKubeConCarsStayApartAndRideSkis() {
        RaceTheme theme = new PresetThemes().all().stream()
            .filter(preset -> preset.id().equals("kubecon-slc"))
            .findFirst().orElseThrow();

        String first = theme.teams().get(0).color();
        String second = theme.teams().get(1).color();
        assertTrue(rgbDistance(first, second) > 200,
            () -> first + " and " + second + " are too close to tell apart at a distance");
        theme.teams().forEach(team ->
            assertEquals("car-ski", team.car(), team.name() + " should ride the winter kit"));
    }

    private static double rgbDistance(String a, String b) {
        int[] ca = rgb(a);
        int[] cb = rgb(b);
        return Math.sqrt(Math.pow(ca[0] - cb[0], 2) + Math.pow(ca[1] - cb[1], 2) + Math.pow(ca[2] - cb[2], 2));
    }

    private static int[] rgb(String hex) {
        return new int[] {
            Integer.parseInt(hex.substring(1, 3), 16),
            Integer.parseInt(hex.substring(3, 5), 16),
            Integer.parseInt(hex.substring(5, 7), 16),
        };
    }

    private static double[] firstPoint(String lane) {
        String[] parts = lane.trim().split(" ");
        return new double[] { Double.parseDouble(parts[1]), Double.parseDouble(parts[2]) };
    }

    private static double[] lastPoint(String lane) {
        String[] parts = lane.trim().split(" ");
        return new double[] { Double.parseDouble(parts[parts.length - 2]), Double.parseDouble(parts[parts.length - 1]) };
    }
}
