package org.acme.game;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.websockets.next.BasicWebSocketConnector;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.vertx.core.json.JsonObject;
import org.acme.race.RaceState.Status;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.acme.admin.AdminSession;
import org.acme.race.RaceState;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class PlayerSocketTest {

    @TestHTTPResource("/")
    URI baseUri;

    @Test
    void playsOverTheSocket() {
        String previousRace = state().raceId();
        sendEvent("reset");
        await().atMost(Duration.ofSeconds(60))
            .until(this::state, s -> !s.raceId().equals(previousRace) && s.status() == Status.WAITING);

        try (Player ada = connect(1001, 1); Player bob = connect(1002, 2)) {
            // Opening the connection registers the player
            await().atMost(Duration.ofSeconds(10)).until(() -> state().players().size() == 2);
            ada.awaitStanding(s -> s.getInteger("teamPlayers") == 1);

            sendEvent("start");
            ada.awaitMessage(m -> m.getString("type").equals("status")
                && m.getJsonObject("event").getString("type").equals("start"));

            ada.send(150);
            ada.send(GameConfig.MAX_QUANTITY + 1);
            bob.send(30);

            await().atMost(Duration.ofSeconds(10)).until(() -> totalGenerated(state()) == 180);
            JsonObject adaStanding = ada.awaitStanding(s -> s.getLong("generated") == 150);
            assertEquals(1, adaStanding.getInteger("teamPosition"));
            assertEquals(2, adaStanding.getInteger("teams"));
            assertEquals(1, adaStanding.getInteger("rankInTeam"));
            assertEquals(2, bob.awaitStanding(s -> s.getLong("generated") == 30).getInteger("teamPosition"));
        }
    }

    @Test
    void closedPlayersDontRegisterAfterAReset() {
        connect(1004, 1).close();
        String previousRace = state().raceId();
        sendEvent("reset");
        await().atMost(Duration.ofSeconds(60)).until(() -> !state().raceId().equals(previousRace));

        try (Player dee = connect(1005, 2)) {
            String deeName = Utils.getNameById(1005);
            RaceState race = await().atMost(Duration.ofSeconds(10))
                .until(this::state, s -> s.players().stream().anyMatch(p -> p.name().equals(deeName)));
            assertEquals(List.of(deeName), race.players().stream().map(RaceState.PlayerState::name).toList());
        }
    }

    @Test
    void rejectsAnInvalidTeam() {
        assertThrows(RuntimeException.class, () -> connect(1003, GameConfig.TEAMS + 1));
    }

    @Test
    void rejectsAnUnknownPlayerId() {
        assertThrows(RuntimeException.class, () -> connect(Utils.NAMES.size(), 1));
    }

    private Player connect(int id, int team) {
        List<JsonObject> messages = new CopyOnWriteArrayList<>();
        WebSocketClientConnection connection = BasicWebSocketConnector.create()
            .baseUri(baseUri)
            .path("/ws/player/" + id + "?team=" + team)
            .onTextMessage((c, message) -> messages.add(new JsonObject(message)))
            .connectAndAwait();
        return new Player(connection, messages);
    }

    private record Player(WebSocketClientConnection connection, List<JsonObject> messages) implements AutoCloseable {

        void send(int quantity) {
            connection.sendTextAndAwait(new JsonObject().put("quantity", quantity).encode());
        }

        JsonObject awaitMessage(Predicate<JsonObject> matches) {
            return await().atMost(Duration.ofSeconds(10))
                .until(() -> messages.stream().filter(matches).findFirst(), Optional::isPresent)
                .orElseThrow();
        }

        JsonObject awaitStanding(Predicate<JsonObject> matches) {
            return awaitMessage(m -> m.getString("type").equals("standing")
                && matches.test(m.getJsonObject("standing"))).getJsonObject("standing");
        }

        @Override
        public void close() {
            connection.closeAndAwait();
        }
    }

    private static long totalGenerated(RaceState state) {
        return state.players().stream().mapToLong(RaceState.PlayerState::generated).sum();
    }

    private RaceState state() {
        return given().get("/api/race/state").then().statusCode(200).extract().as(RaceState.class);
    }

    private static void sendEvent(String type) {
        given().cookie(AdminSession.login()).contentType(JSON).body(Map.of("type", type, "data", Map.of()))
            .post("/api/game/event").then().statusCode(204);
    }
}
