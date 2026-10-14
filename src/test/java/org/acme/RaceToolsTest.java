package org.acme;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import org.acme.RaceState.Status;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RaceToolsTest {

    private static final List<String> READ_TOOLS = List.of("get_race_state", "get_leaderboard", "get_player");
    private static final List<String> ADMIN_TOOLS = List.of("start_race", "pause_race", "reset_race");

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
            .resourcesRead(RaceTools.RACE_URI, response -> assertTrue(
                new JsonObject(response.contents().getFirst().asText().text()).containsKey("status")))
            .thenAssertResults();
        client.disconnect();
    }

    @Test
    void adminEndpointAsksForALogin() {
        String challenge = given().contentType(JSON).body(initialize().encode())
            .post("/admin/mcp")
            .then().statusCode(401)
            .extract().header("WWW-Authenticate");
        assertTrue(challenge.contains("resource_metadata=\"http://localhost:8081/.well-known/oauth-protected-resource/admin/mcp\""),
            challenge);
    }

    @Test
    void publishesProtectedResourceMetadata() {
        String metadata = given().get("/.well-known/oauth-protected-resource/admin/mcp")
            .then().statusCode(200).extract().asString();
        JsonObject json = new JsonObject(metadata);
        assertEquals("http://localhost:8081/admin/mcp", json.getString("resource"), metadata);
        assertEquals(authServerUrl(), json.getJsonArray("authorization_servers").getString(0));
    }

    @Test
    void playersCantControlTheRace() {
        given().auth().oauth2(token("player", "player")).contentType(JSON).body(initialize().encode())
            .post("/admin/mcp")
            .then().statusCode(403);
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
            .toolsCall("pause_race", response -> assertEquals("Race paused", response.firstContent().asText().text()))
            .thenAssertResults();
        await().atMost(Duration.ofSeconds(10)).until(() -> state().status() == Status.PAUSED);
        client.disconnect();
    }

    private RaceState state() {
        return given().get("/api/race/state").then().statusCode(200).extract().as(RaceState.class);
    }

    private static String authServerUrl() {
        return ConfigProvider.getConfig().getValue("quarkus.oidc.auth-server-url", String.class);
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
