package org.acme.theme;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * A look for the race that an admin can apply between races: team names, colors, cars and an optional
 * track. It is broadcast as a theme game event so every replica and every connected client switches
 * together. A null track keeps the current one.
 */
@RegisterForReflection
public record RaceTheme(String id, String name, List<ThemeTeam> teams, Track track) {

    @RegisterForReflection
    public record ThemeTeam(int id, String name, String color, String car, String carTint) {
    }

    /**
     * A track for the dashboard. A painted track has an {@code image} plus either hand-drawn lane path
     * strings ({@code lanes}) or a {@code centerline} of points the UI offsets into lanes. A generated
     * track has only a {@code centerline} and is drawn as vector turf and kerbs.
     */
    @RegisterForReflection
    public record Track(String image, ViewBox viewBox, List<List<Integer>> centerline,
                        Integer laneSpacing, List<String> lanes) {

        @RegisterForReflection
        public record ViewBox(int width, int height) {
        }
    }
}
