package org.acme;

import io.quarkus.test.junit.QuarkusTest;
import org.acme.PowerResource.Power;
import org.acme.RaceState.Status;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
class RaceResourceTest {

    @Test
    void racesOnTheServer() {
        String previousRace = state().raceId();
        sendEvent(event("reset"));
        RaceState reset = await().atMost(Duration.ofSeconds(60))
            .until(this::state, s -> !s.raceId().equals(previousRace) && s.status() == Status.WAITING);
        assertNotEquals(previousRace, reset.raceId());

        GameResource.User ada = assign(1);
        GameResource.User bob = assign(2);
        await().atMost(Duration.ofSeconds(10)).until(() -> state().players().size() == 2);

        sendEvent(event("start"));
        await().atMost(Duration.ofSeconds(10)).until(() -> state().status() == Status.STARTED);
        sendPower(new Power(150, ada.name(), 1));
        sendPower(new Power(30, bob.name(), 2));

        RaceState race = await().atMost(Duration.ofSeconds(10))
            .until(this::state, s -> s.players().stream().mapToLong(RaceState.PlayerState::generated).sum() == 180);
        assertEquals(List.of(1, 2), race.teams().stream().map(RaceState.TeamState::id).toList());

        List<RaceState.LeaderboardEntry> team2 = given().queryParam("team", 2)
            .get("/api/race/leaderboard").then().statusCode(200)
            .extract().jsonPath().getList(".", RaceState.LeaderboardEntry.class);
        assertEquals(List.of(new RaceState.LeaderboardEntry(1, bob.name(), 2, 30)), team2);
    }

    @Test
    void rejectsFinishFromClients() {
        given().cookie(AdminSession.login()).contentType(JSON).body(event("finish"))
            .post("/api/game/event")
            .then().statusCode(400);
    }

    private static Map<String, Object> event(String type) {
        return Map.of("type", type, "data", Map.of());
    }

    private RaceState state() {
        return given().get("/api/race/state").then().statusCode(200).extract().as(RaceState.class);
    }

    private static void sendEvent(Map<String, Object> event) {
        given().cookie(AdminSession.login()).contentType(JSON).body(event).post("/api/game/event").then().statusCode(204);
    }

    private static void sendPower(Power power) {
        given().contentType(JSON).body(power).post("/api/power").then().statusCode(204);
    }

    private static GameResource.User assign(int team) {
        return given().queryParam("team", team).post("/api/game/assign")
            .then().statusCode(200).extract().as(GameResource.User.class);
    }
}
