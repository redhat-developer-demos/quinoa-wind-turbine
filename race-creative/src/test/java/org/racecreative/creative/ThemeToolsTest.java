package org.racecreative.creative;

import org.junit.jupiter.api.Test;
import org.racecreative.theme.ThemeGateway;
import org.racecreative.theme.ThemeOption;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The only tool the creative model can call: it must show real ids, because the model echoes
 * them back and the guardrail rejects anything the game does not offer.
 */
class ThemeToolsTest {

    private static final class StubGateway implements ThemeGateway {
        @Override
        public List<ThemeOption> listThemes() {
            return List.of(new ThemeOption("classic", "Mountains vs Beach"),
                new ThemeOption("atlanta", "Aquarium vs Coca-Cola"));
        }

        @Override
        public String suggestNames(String brief) {
            throw new AssertionError("the tool must not apply names");
        }

        @Override
        public String generateTheme(String brief) {
            throw new AssertionError("the tool must not apply a theme");
        }
    }

    @Test
    void listsTheRealPresetsAsJson() {
        String json = new ThemeTools(new StubGateway()).listThemes();
        assertEquals("[{\"id\":\"classic\",\"label\":\"Mountains vs Beach\"},"
            + "{\"id\":\"atlanta\",\"label\":\"Aquarium vs Coca-Cola\"}]", json);
    }
}
