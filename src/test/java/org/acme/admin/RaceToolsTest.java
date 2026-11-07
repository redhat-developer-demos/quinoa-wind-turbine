package org.acme.admin;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.acme.input.InputModeService;
import org.acme.race.RaceState.Status;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.acme.commentary.CommentaryStyleService;
import org.acme.race.RaceState;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(RaceToolsTest.OidcEnabled.class)
class RaceToolsTest {

    public static class OidcEnabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.oidc.tenant-enabled", "true");
        }
    }

    private static final List<String> READ_TOOLS =
        List.of("get_race_state", "get_leaderboard", "get_player", "get_hall_of_fame", "get_active_theme");
    private static final List<String> ADMIN_TOOLS = List.of("start_race", "pause_race", "reset_race", "boost_team",
        "list_preset_themes", "apply_theme", "set_input_mode", "set_commentary_style", "suggest_team_names",
        "generate_theme", "reset_hall_of_fame");
    private static final List<String> READ_PROMPTS = List.of("race_briefing", "race_recap");

    @Inject
    InputModeService inputModes;

    @Inject
    CommentaryStyleService commentaryStyles;

    @AfterEach
    void restoreDefaults() {
        inputModes.clear();
        commentaryStyles.reset();
    }

    @Test
    void readToolsNeedNoLogin() {
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
        client.when()
            .toolsList(page -> assertEquals(READ_TOOLS.stream().sorted().toList(),
                page.tools().stream().map(McpAssured.ToolInfo::name).sorted().toList()))
            .toolsCall("get_race_state", response -> {
                JsonObject race = new JsonObject(response.firstContent().asText().text());
                assertTrue(race.containsKey("raceId"), race::encode);
            })
            .toolsCall("get_player", Map.of("name", "Nobody"), response -> assertTrue(response.isError()))
            .toolsCall("get_hall_of_fame", response -> assertFalse(response.isError()))
            .toolsCall("get_active_theme", response -> {
                JsonObject theme = new JsonObject(response.firstContent().asText().text());
                // The configured default look is its own theme id, not the "classic" preset.
                assertEquals("default", theme.getString("id"), theme::encode);
                assertEquals(2, theme.getJsonArray("teams").size(), theme::encode);
            })
            .resourcesRead(RaceTools.RACE_URI, response -> assertTrue(
                new JsonObject(response.contents().getFirst().asText().text()).containsKey("status")))
            .promptsList(page -> assertEquals(READ_PROMPTS,
                page.prompts().stream().map(McpAssured.PromptInfo::name).sorted().toList()))
            .promptsGet("race_briefing")
            .withArguments(Map.of("style", "analyst", "focus", "Beach"))
            .withAssert(response -> {
                assertEquals(2, response.messages().size());
                String instructions = response.messages().get(0).content().asText().text();
                assertTrue(instructions.contains("technical race analyst"), instructions);
                assertTrue(instructions.contains("Focus on Beach"), instructions);
                assertTrue(response.messages().get(1).content().asText().text().contains("teamNames"));
            })
            .send()
            .promptsGet("race_recap")
            .withAssert(response -> {
                assertEquals(2, response.messages().size());
                String instructions = response.messages().get(0).content().asText().text();
                assertTrue(instructions.contains("wind turbine race"), instructions);
            })
            .send()
            .thenAssertResults();
        client.disconnect();
    }

    @Test
    void adminEndpointAsksForALogin() {
        String challenge = given().contentType(JSON).body(initialize().encode())
            .post("/admin/mcp")
            .then().statusCode(401)
            .extract().header("WWW-Authenticate");
        assertTrue(challenge.contains("resource_metadata=\"" + testBase()
            + "/.well-known/oauth-protected-resource/admin/mcp\""), challenge);
    }

    @Test
    void publishesProtectedResourceMetadata() {
        String metadata = given().get("/.well-known/oauth-protected-resource/admin/mcp")
            .then().statusCode(200).extract().asString();
        JsonObject json = new JsonObject(metadata);
        assertEquals(testBase() + "/admin/mcp", json.getString("resource"), metadata);
        assertEquals(authServerUrl(), json.getJsonArray("authorization_servers").getString(0));
    }

    @Test
    void playersCantControlTheRace() {
        given().auth().oauth2(token("player", "player")).contentType(JSON).body(initialize().encode())
            .post("/admin/mcp")
            .then().statusCode(403);
    }

    @Test
    void dashboardSessionCannotReplaceABearerToken() {
        given().cookie(AdminSession.login()).contentType(JSON).body(initialize().encode())
            .post("/admin/mcp").then().statusCode(401);
    }

    @Test
    void acceptsAnonymousDynamicClientRegistration() {
        JsonObject registration = new JsonObject()
            .put("client_name", "race-tools-test")
            .put("redirect_uris", List.of("http://localhost:33419/callback"))
            .put("token_endpoint_auth_method", "none")
            .put("grant_types", List.of("authorization_code"))
            .put("response_types", List.of("code"));
        String clientId = given().contentType(JSON).body(registration.encode())
            .post(authServerUrl() + "/clients-registrations/openid-connect")
            .then().statusCode(201).extract().path("client_id");
        assertTrue(clientId != null && !clientId.isBlank());
    }

    @Test
    void adminControlsTheRace() {
        String token = token("developer", "developer");
        String payload = token.split("\\.")[1];
        JsonObject claims = new JsonObject(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
        assertTrue("wind-turbine".equals(claims.getValue("aud"))
            || claims.getValue("aud") instanceof io.vertx.core.json.JsonArray audiences
                && audiences.contains("wind-turbine"), claims::encode);
        assertTrue(claims.getJsonObject("realm_access").getJsonArray("roles").contains("admin"), claims::encode);
        McpStreamableTestClient client = McpAssured.newStreamableClient()
            .setMcpPath("/admin/mcp")
            .setAdditionalHeaders(message -> MultiMap.caseInsensitiveMultiMap().add("Authorization", "Bearer " + token))
            .build()
            .connect();
        String previousRace = state().raceId();
        client.when()
            .toolsList(page -> assertEquals(
                READ_TOOLS.size() + ADMIN_TOOLS.size(), page.size()))
            .toolsCall("reset_race", response -> assertEquals("Race reset", response.firstContent().asText().text()))
            .thenAssertResults();
        RaceState reset = await().atMost(Duration.ofSeconds(60))
            .until(this::state, s -> !s.raceId().equals(previousRace) && s.status() == Status.WAITING);
        assertNotEquals(previousRace, reset.raceId());

        client.when()
            .toolsCall("start_race", response -> assertEquals("Race started", response.firstContent().asText().text()))
            .thenAssertResults();
        await().atMost(Duration.ofSeconds(10)).until(() -> state().status() == Status.STARTED);

        client.when()
            .toolsCall("boost_team", Map.of("team", 1), response ->
                assertTrue(response.firstContent().asText().text().contains("team 1"),
                    () -> response.firstContent().asText().text()))
            .thenAssertResults();

        client.when()
            .toolsCall("pause_race", response -> assertEquals("Race paused", response.firstContent().asText().text()))
            .thenAssertResults();
        await().atMost(Duration.ofSeconds(10)).until(() -> state().status() == Status.PAUSED);

        // The AI theme tools exist on the admin server but AI is off in this profile, so they explain that
        // instead of calling a model.
        client.when()
            .toolsCall("suggest_team_names", Map.of("prompt", "a seafood festival"), response -> {
                assertTrue(response.isError(), () -> response.firstContent().asText().text());
                assertTrue(response.firstContent().asText().text().contains("AI is disabled"),
                    () -> response.firstContent().asText().text());
            })
            .toolsCall("generate_theme", Map.of("prompt", "a retro arcade tournament"), response -> {
                assertTrue(response.isError(), () -> response.firstContent().asText().text());
                assertTrue(response.firstContent().asText().text().contains("AI is disabled"),
                    () -> response.firstContent().asText().text());
            })
            .toolsCall("reset_hall_of_fame", response -> {
                assertFalse(response.isError(), () -> response.firstContent().asText().text());
                assertTrue(response.firstContent().asText().text().toLowerCase().contains("hall of fame"),
                    () -> response.firstContent().asText().text());
            })
            .thenAssertResults();
        client.disconnect();
    }

    @Test
    void setInputModeIsAdminOnly() {
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
        client.when()
            .toolsList(page -> assertFalse(
                page.tools().stream().anyMatch(tool -> "set_input_mode".equals(tool.name()))))
            .thenAssertResults();
        client.disconnect();
    }

    @Test
    void adminSwitchesTheInputModeBetweenRaces() {
        McpStreamableTestClient client = adminClient();
        resetRace(client);

        client.when()
            .toolsList(page -> assertTrue(
                page.tools().stream().anyMatch(tool -> "set_input_mode".equals(tool.name()))))
            .toolsCall("set_input_mode", Map.of("mode", "shake"), response -> {
                assertFalse(response.isError(), () -> response.firstContent().asText().text());
                assertEquals("Input mode set to shake", response.firstContent().asText().text());
            })
            .thenAssertResults();
        given().get("/api/config").then().statusCode(200).body("inputMode", is("shake"));

        client.when()
            .toolsCall("set_input_mode", Map.of("mode", "BOTH"), response ->
                assertEquals("Input mode set to both", response.firstContent().asText().text()))
            .thenAssertResults();
        given().get("/api/config").then().statusCode(200).body("inputMode", is("both"));
        client.disconnect();
    }

    @Test
    void inputModeIsRejectedDuringARace() {
        McpStreamableTestClient client = adminClient();
        resetRace(client);
        client.when()
            .toolsCall("start_race", response -> assertFalse(response.isError()))
            .thenAssertResults();
        await().atMost(Duration.ofSeconds(10)).until(RaceToolsTest::gameStatus, is("start"));

        client.when()
            .toolsCall("set_input_mode", Map.of("mode", "shake"), response -> {
                assertFalse(response.isError(), () -> response.firstContent().asText().text());
                assertTrue(response.firstContent().asText().text().contains("rejected"),
                    () -> response.firstContent().asText().text());
                assertTrue(response.firstContent().asText().text().contains("between races"),
                    () -> response.firstContent().asText().text());
            })
            .thenAssertResults();
        given().get("/api/config").then().statusCode(200).body("inputMode", is("tap"));

        resetRace(client);
        client.disconnect();
    }

    @Test
    void invalidInputModeIsAnError() {
        McpStreamableTestClient client = adminClient();
        resetRace(client);
        client.when()
            .toolsCall("set_input_mode", Map.of("mode", "blow"), response -> {
                assertTrue(response.isError(), () -> response.firstContent().asText().text());
                assertTrue(response.firstContent().asText().text().contains("Unknown input mode"),
                    () -> response.firstContent().asText().text());
            })
            .thenAssertResults();
        given().get("/api/config").then().statusCode(200).body("inputMode", is("tap"));
        client.disconnect();
    }

    @Test
    void adminPromptsIncludeTheThemeBrief() {
        McpStreamableTestClient client = adminClient();
        client.when()
            .promptsList(page -> assertEquals(List.of("race_briefing", "race_recap", "theme_idea"),
                page.prompts().stream().map(McpAssured.PromptInfo::name).sorted().toList()))
            .promptsGet("theme_idea")
            .withArguments(Map.of("event", "KubeCon Salt Lake City", "audience", "platform engineers"))
            .withAssert(response -> {
                String brief = response.messages().get(0).content().asText().text();
                assertTrue(brief.contains("KubeCon Salt Lake City"), brief);
                assertTrue(brief.contains("kubecon-slc"), brief);
                assertTrue(brief.contains("apply_theme"), brief);
            })
            .send()
            .thenAssertResults();
        client.disconnect();
    }

    @Test
    void adminSwitchesTheCommentaryStyleBetweenRaces() {
        McpStreamableTestClient client = adminClient();
        resetRace(client);
        client.when()
            .toolsCall("set_commentary_style", Map.of("style", "analyst"), response -> {
                assertFalse(response.isError(), () -> response.firstContent().asText().text());
                assertEquals("Commentary style set to analyst", response.firstContent().asText().text());
            })
            .thenAssertResults();
        // The style reaches the replicas through a game event, so the config endpoint catches up
        // asynchronously like the theme does.
        await().atMost(Duration.ofSeconds(10)).until(() ->
            given().get("/api/config").then().statusCode(200).extract().jsonPath().getString("commentaryStyle"),
            is("analyst"));
        client.disconnect();
    }

    @Test
    void commentaryStyleIsRejectedDuringARace() {
        McpStreamableTestClient client = adminClient();
        resetRace(client);
        client.when()
            .toolsCall("start_race", response -> assertFalse(response.isError()))
            .thenAssertResults();
        await().atMost(Duration.ofSeconds(10)).until(RaceToolsTest::gameStatus, is("start"));

        client.when()
            .toolsCall("set_commentary_style", Map.of("style", "hype"), response -> {
                assertFalse(response.isError(), () -> response.firstContent().asText().text());
                assertTrue(response.firstContent().asText().text().contains("between races"),
                    () -> response.firstContent().asText().text());
            })
            .thenAssertResults();
        given().get("/api/config").then().statusCode(200).body("commentaryStyle", is("broadcast"));

        resetRace(client);
        client.disconnect();
    }

    @Test
    void invalidCommentaryStyleIsAnError() {
        McpStreamableTestClient client = adminClient();
        resetRace(client);
        client.when()
            .toolsCall("set_commentary_style", Map.of("style", "shouty"), response -> {
                assertTrue(response.isError(), () -> response.firstContent().asText().text());
                assertTrue(response.firstContent().asText().text().contains("Unknown commentary style"),
                    () -> response.firstContent().asText().text());
            })
            .thenAssertResults();
        given().get("/api/config").then().statusCode(200).body("commentaryStyle", is("broadcast"));
        client.disconnect();
    }

    private McpStreamableTestClient adminClient() {
        String token = token("developer", "developer");
        return McpAssured.newStreamableClient()
            .setMcpPath("/admin/mcp")
            .setAdditionalHeaders(message -> MultiMap.caseInsensitiveMultiMap().add("Authorization", "Bearer " + token))
            .build()
            .connect();
    }

    private static void resetRace(McpStreamableTestClient client) {
        client.when()
            .toolsCall("reset_race", response -> assertEquals("Race reset", response.firstContent().asText().text()))
            .thenAssertResults();
        await().atMost(Duration.ofSeconds(10)).until(RaceToolsTest::gameStatus, is("reset"));
    }

    private static String gameStatus() {
        return given().get("/api/game/status").then().statusCode(200).extract().jsonPath().getString("type");
    }

    private RaceState state() {
        return given().get("/api/race/state").then().statusCode(200).extract().as(RaceState.class);
    }

    private static String authServerUrl() {
        return ConfigProvider.getConfig().getValue("quarkus.oidc.auth-server-url", String.class);
    }

    /**
     * The base URL the test client actually uses. The test port is random when the suite passes
     * {@code -Dquarkus.http.test-port=0}, so the advertised URLs cannot be hardcoded.
     */
    private static String testBase() {
        return io.restassured.RestAssured.baseURI + ":" + io.restassured.RestAssured.port;
    }

    private static String token(String username, String password) {
        return given().formParam("grant_type", "password")
            .formParam("client_id", "mcp-agent")
            .formParam("username", username)
            .formParam("password", password)
            .post(authServerUrl() + "/protocol/openid-connect/token")
            .then().statusCode(200)
            .extract().path("access_token");
    }

    private static JsonObject initialize() {
        return new JsonObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
            .put("params", new JsonObject()
                .put("protocolVersion", "2025-06-18")
                .put("capabilities", new JsonObject())
                .put("clientInfo", new JsonObject().put("name", "test").put("version", "1")));
    }
}
