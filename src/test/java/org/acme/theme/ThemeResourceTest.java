package org.acme.theme;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Cookie;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The theme endpoints against the default profile, where AI is off. Theme application is
 * asynchronous (the theme travels as a game event through Kafka before every replica switches),
 * so the assertions that read the theme back wait for it.
 */
@QuarkusTest
class ThemeResourceTest {

    @Test
    void presetAppliesAndSurvivesReset() {
        Cookie session = login();
        given().cookie(session).post("/api/theme/preset/ferrari-mercedes")
            .then().statusCode(204);
        awaitTeamNames("Ferrari", "Mercedes");

        given().cookie(session).contentType(JSON).body(Map.of("type", "reset"))
            .post("/api/game/event")
            .then().statusCode(204);
        await().atMost(Duration.ofSeconds(10))
            .until(() -> status(), is("reset"));
        awaitTeamNames("Ferrari", "Mercedes");
    }

    @Test
    void invalidThemeIsRejectedAndKeepsTheCurrentOne() {
        Cookie session = login();
        given().cookie(session).post("/api/theme/preset/forest-sun").then().statusCode(204);
        awaitTeamNames("Forest", "Sunshine");

        List<Map<String, Object>> threeTeams = List.of(
            Map.of("id", 1, "name", "One", "color", "#111111", "car", "car-red"),
            Map.of("id", 2, "name", "Two", "color", "#eeeeee", "car", "car-grey"),
            Map.of("id", 3, "name", "Three", "color", "#555555", "car", "car-orange"));
        given().cookie(session).contentType(JSON)
            .body(Map.of("id", "bad", "name", "Bad", "teams", threeTeams))
            .post("/api/theme")
            .then().statusCode(422).body("errors", hasItem("A theme needs exactly 2 teams"));

        // The theme applied before the rejected entry is still served.
        awaitTeamNames("Forest", "Sunshine");
    }

    @Test
    void aiEndpointsAnswer503WhenDisabled() {
        Cookie session = login();
        given().cookie(session).contentType(JSON).body(Map.of("prompt", "a seafood festival"))
            .post("/api/theme/generate")
            .then().statusCode(503).body("errors", hasItem("AI is disabled; set game.ai.enabled=true to use it"));
        given().cookie(session).contentType(JSON).body(Map.of("prompt", "a seafood festival"))
            .post("/api/theme/suggest-names")
            .then().statusCode(503);
    }

    @Test
    void themesAreRejectedMidRace() {
        Cookie session = login();
        given().cookie(session).contentType(JSON).body(Map.of("type", "reset"))
            .post("/api/game/event").then().statusCode(204);
        await().atMost(Duration.ofSeconds(10)).until(() -> status(), is("reset"));

        // A known theme before the race, so the test can tell a kept theme from the default.
        given().cookie(session).post("/api/theme/preset/forest-sun").then().statusCode(204);
        awaitTeamNames("Forest", "Sunshine");

        given().cookie(session).contentType(JSON).body(Map.of("type", "start"))
            .post("/api/game/event").then().statusCode(204);
        await().atMost(Duration.ofSeconds(10)).until(() -> status(), is("start"));

        given().cookie(session).post("/api/theme/preset/classic")
            .then().statusCode(422).body("errors", hasItem("Themes can only be applied between races"));
        awaitTeamNames("Forest", "Sunshine");

        // Leave the game between races for whichever test runs next.
        given().cookie(session).contentType(JSON).body(Map.of("type", "reset"))
            .post("/api/game/event").then().statusCode(204);
        await().atMost(Duration.ofSeconds(10)).until(() -> status(), is("reset"));
    }

    private static Cookie login() {
        Cookie session = given().redirects().follow(false)
            .formParam("j_username", "developer")
            .formParam("j_password", "developer")
            .post("/j_security_check")
            .then().statusCode(302).header("Location", endsWith("/"))
            .extract().detailedCookie("quarkus-credential");
        assertNotNull(session);
        return session;
    }

    private static void awaitTeamNames(String team1, String team2) {
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            List<String> names = given().get("/api/config")
                .then().statusCode(200).extract().jsonPath().getList("teams.name");
            return names != null && names.size() == 2
                && names.get(0).equals(team1) && names.get(1).equals(team2);
        });
    }

    private static String status() {
        return given().get("/api/game/status").then().statusCode(200)
            .extract().jsonPath().getString("type");
    }
}
