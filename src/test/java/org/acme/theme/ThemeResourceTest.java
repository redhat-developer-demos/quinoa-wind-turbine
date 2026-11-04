package org.acme.theme;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Cookie;
import org.acme.game.GameResource;
import org.acme.game.PowerResource.Power;
import org.acme.race.RaceState;
import org.acme.race.RaceState.Status;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void applyingAThemeAfterAFinishResetsTheFinishedRaceFirst() {
        Cookie session = login();
        String finished = finishARace();

        given().cookie(session).post("/api/theme/preset/ferrari-mercedes").then().statusCode(204);

        awaitTeamNames("Ferrari", "Mercedes");
        // The reset clears the board the new names must never land on: no winner and no players
        // from the heat that just ended.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            RaceState state = raceState();
            assertNotEquals(finished, state.raceId());
            assertEquals(Status.WAITING, state.status());
            assertNull(state.winner());
            assertTrue(state.players().isEmpty(), "the previous heat's leaderboard should be cleared");
            assertEquals("reset", status());
        });
    }

    /**
     * Resets, starts and finishes a race with one player on each team. Team 1 has one player, so it
     * needs 1500 power (tap-power 15 x taps-needed 100) to finish; each power event is capped at
     * 200, hence the eight events.
     */
    private static String finishARace() {
        resetToNewRace();
        GameResource.User player = assign(1);
        assign(2);
        await().atMost(Duration.ofSeconds(10)).until(() -> raceState().players().size() == 2);

        given().cookie(login()).contentType(JSON).body(Map.of("type", "start"))
            .post("/api/game/event").then().statusCode(204);
        await().atMost(Duration.ofSeconds(10)).until(() -> raceState().status() == Status.STARTED);

        for (int i = 0; i < 8; i++) {
            given().contentType(JSON).body(new Power(200, player.name(), 1))
                .post("/api/power").then().statusCode(204);
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> raceState().status() == Status.FINISHED);
        return raceState().raceId();
    }

    private static void resetToNewRace() {
        String previous = raceState().raceId();
        given().cookie(login()).contentType(JSON).body(Map.of("type", "reset"))
            .post("/api/game/event").then().statusCode(204);
        await().atMost(Duration.ofSeconds(60))
            .until(ThemeResourceTest::raceState,
                s -> !s.raceId().equals(previous) && s.status() == Status.WAITING);
    }

    private static GameResource.User assign(int team) {
        return given().queryParam("team", team).post("/api/game/assign")
            .then().statusCode(200).extract().as(GameResource.User.class);
    }

    private static RaceState raceState() {
        return given().get("/api/race/state").then().statusCode(200).extract().as(RaceState.class);
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
