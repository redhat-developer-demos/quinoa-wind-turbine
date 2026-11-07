package org.racecreative.theme;

/**
 * One built-in theme of the game, as the admin MCP tool describes it. Only the fields the
 * creative director uses are kept: the id to apply and the label to show.
 */
public record ThemeOption(String id, String label) {
}
