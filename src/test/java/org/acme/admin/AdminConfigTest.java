package org.acme.admin;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import org.acme.game.GameConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminConfigTest {

    // printf 'developer:Quarkus:developer' | shasum
    private static final String DEVELOPER_DIGEST = "12d921fb82a482e7742b5368ca8b80e855f26959";

    @Test
    void digestUsesTheEmbeddedRealmFormat() {
        assertEquals(DEVELOPER_DIGEST, GameConfig.Admin.digest("developer", "developer"));
    }

    @Test
    void checksTheDigestWhenNoPasswordIsSet() {
        GameConfig.Admin admin = admin(Optional.empty(), DEVELOPER_DIGEST.toUpperCase());

        assertTrue(admin.accepts("developer", "developer"));
        assertFalse(admin.accepts("developer", "wrong"));
        assertFalse(admin.accepts("someone-else", "developer"));
    }

    @Test
    void plainPasswordReplacesTheDigest() {
        GameConfig.Admin admin = admin(Optional.of("new-password"), DEVELOPER_DIGEST);

        assertTrue(admin.accepts("developer", "new-password"));
        assertFalse(admin.accepts("developer", "developer"));
    }

    private static GameConfig.Admin admin(Optional<String> password, String digest) {
        return new GameConfig.Admin() {
            @Override
            public String username() {
                return "developer";
            }

            @Override
            public Optional<String> password() {
                return password;
            }

            @Override
            public String passwordDigest() {
                return digest;
            }
        };
    }
}
