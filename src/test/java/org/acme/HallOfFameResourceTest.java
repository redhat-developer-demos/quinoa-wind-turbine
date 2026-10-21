package org.acme;

import io.quarkus.test.junit.QuarkusTest;
import org.acme.PowerResource.Power;
import org.acme.RaceState.Status;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class HallOfFameResourceTest {

    @Test
    void finishedRaceAppears() {
        String raceId = resetToNewRace();
        GameResource.User ada = assign(1);
        assign(2);
        await().atMost(Duration.ofSeconds(10)).until(() -> state().players().size() == 2);

        sendEvent(event("start"));
        await().atMost(Duration.ofSeconds(10)).until(() -> state().status() == Status.STARTED);

        // Team 1 has one player, so it needs 1500 power (tap-power 15 x taps-needed 100 x 1 player) to finish.
        // Each power event is capped at 200, so send enough events to cross the line.
        for (int i = 0; i < 8; i++) {
            sendPower(new Power(200, ada.name(), 1));
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> state().status() == Status.FINISHED);

        HallOfFameEntry entry = await().atMost(Duration.ofSeconds(30))
            .until(() -> hallOfFame().stream().filter(e -> e.raceId().equals(raceId)).findFirst().orElse(null),
                Objects::nonNull);
        assertEquals(Integer.valueOf(1), entry.winner());
        assertTrue(entry.elapsedMs() > 0, "the race time should be recorded");
        assertEquals(2, entry.teams().size());
        assertTrue(entry.topPlayers().stream().anyMatch(p -> p.name().equals(ada.name())),
            "the race's top generator should be kept");
    }

    @Test
    void resetRaceDoesNotAppear() {
        String raceId = resetToNewRace();
        // The race is reset but never started or finished, so it produces no FINISHED snapshot and must not appear.
        assertFalse(hallOfFame().stream().anyMatch(e -> e.raceId().equals(raceId)),
            "a race that never finished must not be in the hall of fame");
    }

    private static String resetToNewRace() {
        String previous = state().raceId();
        sendEvent(event("reset"));
        return await().atMost(Duration.ofSeconds(60))
            .until(HallOfFameResourceTest::state, s -> !s.raceId().equals(previous) && s.status() == Status.WAITING)
            .raceId();
    }

    private static List<HallOfFameEntry> hallOfFame() {
        return given().get("/api/hall-of-fame").then().statusCode(200)
            .extract().jsonPath().getList(".", HallOfFameEntry.class);
    }

    private static RaceState state() {
        return given().get("/api/race/state").then().statusCode(200).extract().as(RaceState.class);
    }

    private static Map<String, Object> event(String type) {
        return Map.of("type", type, "data", Map.of());
    }

    private static void sendEvent(Map<String, Object> event) {
        given().cookie(AdminSession.login()).contentType(JSON).body(event).post("/api/game/event")
            .then().statusCode(204);
    }

    private static void sendPower(Power power) {
        given().contentType(JSON).body(power).post("/api/power").then().statusCode(204);
    }

    private static GameResource.User assign(int team) {
        return given().queryParam("team", team).post("/api/game/assign")
            .then().statusCode(200).extract().as(GameResource.User.class);
    }
}
