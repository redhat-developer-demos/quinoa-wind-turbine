package org.acme.game;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Cookie;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import org.acme.admin.AdminSession;
import org.acme.commentary.Commentary;
import org.acme.commentary.CommentaryFeed;
import org.acme.race.RaceState;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

// Runs on the default test configuration, where OIDC and AI are off. That is the state this test checks.
@QuarkusTest
class OptionalServicesDisabledTest {
    @Inject
    CommentaryFeed commentary;

    @Inject
    GameConfig gameConfig;

    @Test
    void gameAndTemplateCommentaryWorkWithoutOidcOrAi() {
        var config = ConfigProvider.getConfig();
        for (String flag : new String[] { "quarkus.oidc.tenant-enabled", "quarkus.keycloak.devservices.enabled",
                "game.ai.enabled", "quarkus.langchain4j.openai.enable-integration" }) {
            assertFalse(config.getValue(flag, Boolean.class), flag);
        }
        given().get("/api/config").then().statusCode(200).body("aiEnabled", is(false));
        Cookie admin = AdminSession.login();
        String previousRace = state().raceId();
        event(admin, "reset");
        String raceId = await().atMost(Duration.ofSeconds(20)).until(OptionalServicesDisabledTest::state,
            race -> !race.raceId().equals(previousRace)).raceId();
        try {
            var player = given().queryParam("team", 1).post("/api/game/assign")
                .then().statusCode(200).extract().as(GameResource.User.class);
            await().atMost(Duration.ofSeconds(20)).until(() -> state().players().size() == 1);
            event(admin, "start");
            await().atMost(Duration.ofSeconds(20)).until(() -> state().status() == RaceState.Status.STARTED);
            int power = gameConfig.tapPower() * gameConfig.tapsNeededPerPlayer();
            while (power > 0) {
                int batch = Math.min(power, GameConfig.MAX_QUANTITY);
                given().contentType(JSON).body(new PowerResource.Power(batch, player.name(), 1))
                    .post("/api/power").then().statusCode(204);
                power -= batch;
            }
            RaceState finished = await().atMost(Duration.ofSeconds(20)).until(OptionalServicesDisabledTest::state,
                race -> race.status() == RaceState.Status.FINISHED);
            assertEquals(1, finished.winner());
            Commentary line = await().atMost(Duration.ofSeconds(20)).until(() -> commentary.latest()
                .filter(value -> raceId.equals(value.raceId()) && "finish".equals(value.trigger())),
                java.util.Optional::isPresent).orElseThrow();
            assertEquals("template", line.source());
            assertEquals(gameConfig.teamName(1) + " wins the race!", line.text());
        } finally {
            event(admin, "reset");
        }
    }

    @Test
    void readOnlyMcpRemainsPublicAndAdminMcpStaysProtected() {
        var client = McpAssured.newConnectedStreamableClient();
        try {
            client.when().toolsCall("get_race_state", response -> assertFalse(response.isError()))
                .thenAssertResults();
        } finally {
            client.disconnect();
        }
        String initialize = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
            "capabilities":{},"clientInfo":{"name":"optional-services-test","version":"1"}}}
            """;
        given().contentType(JSON).body(initialize).post("/admin/mcp").then().statusCode(403);
        given().auth().oauth2("not-a-valid-token").contentType(JSON).body(initialize)
            .post("/admin/mcp").then().statusCode(403);
        given().cookie(AdminSession.login()).contentType(JSON).body(initialize)
            .post("/admin/mcp").then().statusCode(403);
    }

    private static RaceState state() {
        return given().get("/api/race/state").then().statusCode(200).extract().as(RaceState.class);
    }

    private static void event(Cookie admin, String type) {
        given().cookie(admin).contentType(JSON).body(Map.of("type", type))
            .post("/api/game/event").then().statusCode(204);
    }
}
