package org.racecreative.theme;

import java.util.List;

/**
 * The game's theme toolset as this app uses it. An interface, not the MCP client itself, so
 * the proposal rules can be tested without a game, a broker or a model.
 *
 * Only {@link #listThemes()} is read-only. The two AI calls apply the theme inside the game
 * the moment they return, so they are only ever reached on the approved-execution path.
 */
public interface ThemeGateway {

    /**
     * @return the presets the game offers, never empty
     */
    List<ThemeOption> listThemes();

    /**
     * Applies a name-only theme, keeping colors, cars and track.
     *
     * @param brief the event, location or audience to name the teams for
     */
    String suggestNames(String brief);

    /**
     * Applies a full theme: both names, colors and cars.
     *
     * @param brief what the theme is for
     */
    String generateTheme(String brief);
}
