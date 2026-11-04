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

    private static double[] firstPoint(String lane) {
        String[] parts = lane.trim().split(" ");
        return new double[] { Double.parseDouble(parts[1]), Double.parseDouble(parts[2]) };
    }

    private static double[] lastPoint(String lane) {
        String[] parts = lane.trim().split(" ");
        return new double[] { Double.parseDouble(parts[parts.length - 2]), Double.parseDouble(parts[parts.length - 1]) };
    }
}
