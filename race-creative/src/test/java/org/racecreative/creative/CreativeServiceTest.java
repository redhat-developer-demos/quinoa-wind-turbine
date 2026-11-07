package org.racecreative.creative;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.racecreative.theme.ThemeGateway;
import org.racecreative.theme.ThemeOption;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The creative contract without a model, a game or a network: the deterministic proposal,
 * the approved-execution path, and the rejections that keep an unsafe request out.
 */
class CreativeServiceTest {

    private static final List<ThemeOption> PRESETS = List.of(
        new ThemeOption("classic", "Mountains vs Beach"),
        new ThemeOption("atlanta", "Aquarium vs Coca-Cola"),
        new ThemeOption("fire-ice", "Fire vs Ice"));

    private final ObjectMapper mapper = new ObjectMapper();

    private static final class StubGateway implements ThemeGateway {
        String suggested;
        String generated;

        @Override
        public List<ThemeOption> listThemes() {
            return PRESETS;
        }

        @Override
        public String suggestNames(String brief) {
            suggested = brief;
            return "{\"id\":\"ai-names\",\"name\":\"Generated\",\"teams\":["
                + "{\"id\":1,\"name\":\"Lisbon\"},{\"id\":2,\"name\":\"Sardines\"}]}";
        }

        @Override
        public String generateTheme(String brief) {
            generated = brief;
            return "{\"id\":\"ai-theme\",\"name\":\"Generated\",\"teams\":["
                + "{\"id\":1,\"name\":\"Neon Racers\"},{\"id\":2,\"name\":\"Pixel Kings\"}],"
                + "\"track\":null}";
        }
    }

    private CreativeService service(StubGateway gateway) {
        return new CreativeService(mapper, gateway, null, false);
    }

    @Test
    void withoutAModelItProposesARealPresetThatIsNotOn() throws Exception {
        JsonNode proposal = mapper.readTree(service(new StubGateway())
            .handle("{\"mode\":\"propose\",\"currentTheme\":\"classic\",\"event\":\"a seafood festival\"}"));
        assertEquals("preset", proposal.get("kind").asText());
        assertEquals("atlanta", proposal.get("presetId").asText());
        assertTrue(proposal.get("message").asText().contains("Aquarium"), proposal.toString());
    }

    @Test
    void theProposalNeverRepeatsTheCurrentTheme() throws Exception {
        JsonNode proposal = mapper.readTree(service(new StubGateway())
            .handle("{\"mode\":\"propose\",\"currentTheme\":\"atlanta\"}"));
        assertTrue(!"atlanta".equals(proposal.get("presetId").asText()), proposal.toString());
    }

    @Test
    void approvingAFullThemeRunsTheGeneratorWithTheBrief() throws Exception {
        StubGateway gateway = new StubGateway();
        JsonNode result = mapper.readTree(service(gateway)
            .handle("{\"mode\":\"apply\",\"kind\":\"ai-theme\",\"brief\":\"a retro arcade tournament\"}"));
        assertEquals("a retro arcade tournament", gateway.generated);
        assertEquals(null, gateway.suggested);
        assertEquals("ai-theme", result.get("kind").asText());
        assertEquals("Applied: Neon Racers vs Pixel Kings.", result.get("message").asText());
    }

    @Test
    void approvingTeamNamesRunsTheNamerWithTheBrief() throws Exception {
        StubGateway gateway = new StubGateway();
        JsonNode result = mapper.readTree(service(gateway)
            .handle("{\"mode\":\"apply\",\"kind\":\"ai-names\",\"brief\":\"a seafood festival in Lisbon\"}"));
        assertEquals("a seafood festival in Lisbon", gateway.suggested);
        assertEquals(null, gateway.generated);
        assertEquals("Applied: Lisbon vs Sardines.", result.get("message").asText());
    }

    @Test
    void presetsAreAppliedByTheCallerNotByTheCreativeAgent() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> service(new StubGateway())
                .handle("{\"mode\":\"apply\",\"kind\":\"preset\",\"presetId\":\"atlanta\"}"));
        assertTrue(error.getMessage().contains("applied by the caller"), error.getMessage());
    }

    @Test
    void anApplyWithoutABriefIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> service(new StubGateway()).handle("{\"mode\":\"apply\",\"kind\":\"ai-theme\"}"));
    }

    @Test
    void anUnknownModeIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> service(new StubGateway()).handle("{\"mode\":\"delete-everything\"}"));
        assertTrue(error.getMessage().contains("Unknown creative mode"), error.getMessage());
    }

    @Test
    void aPlainSentenceIsReadAsAProposeBrief() throws Exception {
        // An orchestrator in front of this app can ask in words. Only apply changes the game, so
        // prose is always a proposal, never an execution.
        StubGateway gateway = new StubGateway();
        JsonNode proposal = mapper.readTree(service(gateway)
            .handle("Propose the next heat's look for a developer conference in Salt Lake City."));
        assertEquals("preset", proposal.get("kind").asText());
        assertTrue(proposal.hasNonNull("message"), proposal.toString());
        assertEquals(null, gateway.suggested);
        assertEquals(null, gateway.generated);
    }

    @Test
    void anEmptyRequestIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> service(new StubGateway()).handle("  "));
    }
}
