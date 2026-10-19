package org.acme;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Map;

@ConfigMapping(prefix = "game")
public interface GameConfig {

    /**
     * Power sent by one tap. Must match TAP_POWER in the web UI Config.js.
     */
    @WithDefault("15")
    int tapPower();

    /**
     * Taps each player needs for their team to reach the finish line.
     */
    @WithDefault("100")
    int tapsNeededPerPlayer();

    Race race();

    Admin admin();

    Ai ai();

    Commentary commentary();

    Map<Integer, Team> teams();

    interface Team {
        String name();
        String color();
        String car();
    }

    default String teamName(int team) {
        Team configured = teams().get(team);
        return configured == null ? "Team " + team : configured.name();
    }

    interface Ai {
        @WithDefault("false")
        boolean enabled();
    }

    interface Commentary {
        @WithDefault("3s")
        Duration interval();

        @WithDefault("2s")
        Duration timeout();

        @WithDefault("180")
        int maxLength();

        @WithDefault("5s")
        Duration surgeWindow();

        @WithDefault("2")
        double surgeMultiplier();

        @WithDefault("10")
        int surgeMinTaps();
    }

    interface Race {
        /**
         * How often the topology publishes a race snapshot when something changed.
         */
        @WithDefault("250ms")
        Duration snapshotInterval();
    }

    /**
     * The dashboard login. Set GAME_ADMIN_USERNAME and GAME_ADMIN_PASSWORD to change it.
     */
    interface Admin {
        @WithDefault("developer")
        String username();

        /**
         * Plain-text password. When set, it replaces the password digest.
         */
        Optional<String> password();

        /**
         * Hex SHA-1 of "username:Quarkus:password", the format of the embedded realm this replaced.
         */
        String passwordDigest();

        default boolean accepts(String username, String password) {
            if (!username().equals(username)) {
                return false;
            }
            return password().isPresent()
                ? constantTimeEquals(password().get(), password)
                : constantTimeEquals(passwordDigest().toLowerCase(), digest(username, password));
        }

        static String digest(String username, String password) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-1")
                    .digest((username + ":Quarkus:" + password).getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(hash);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        private static boolean constantTimeEquals(String expected, String actual) {
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
        }
    }

    default RaceRules rules() {
        return new RaceRules(tapPower(), tapsNeededPerPlayer());
    }

    record RaceRules(int tapPower, int tapsNeededPerPlayer) {

        /**
         * Distance covered by a team, in percent of the track, capped at 100.
         */
        double distance(long power, int players) {
            if (players == 0) {
                return 0;
            }
            return Math.min(100, power * 100.0 / ((long) tapPower * tapsNeededPerPlayer * players));
        }
    }
}
