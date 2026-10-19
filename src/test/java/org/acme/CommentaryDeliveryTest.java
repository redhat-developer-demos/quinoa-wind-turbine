package org.acme;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.websockets.next.BasicWebSocketConnector;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.restassured.http.Cookie;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CommentaryDeliveryTest {
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(20);

    @TestHTTPResource("/")
    URI baseUri;

    @Inject
    CommentaryFeed feed;

    @Inject
    GameConfig config;

    @Inject
    @Channel("commentary-out")
    Emitter<Commentary> commentaryOut;

    @Test
    void generatedCommentaryReachesBothDashboardsAndReconnectsWithoutDuplicates() throws Exception {
        given().get("/api/features").then().statusCode(200).body("aiEnabled", is(false));
        Cookie admin = AdminSession.login();
        String raceId = reset(admin);
        List<JsonObject> firstMessages = new CopyOnWriteArrayList<>();
        List<JsonObject> secondMessages = new CopyOnWriteArrayList<>();
        WebSocketClientConnection first = connect(admin, firstMessages);
        WebSocketClientConnection second = null;
        try {
            second = connect(admin, secondMessages);
            awaitRace(firstMessages, raceId);
            awaitRace(secondMessages, raceId);

            GameResource.User player = assign(1);
            assign(2);
            await().atMost(DELIVERY_TIMEOUT).until(() -> state().players().size() == 2);
            event(admin, "start");
            await().atMost(DELIVERY_TIMEOUT).until(() -> state().status() == RaceState.Status.STARTED);

            int milestonePower = (int) Math.ceil((long) config.tapPower() * config.tapsNeededPerPlayer() * 0.25);
            int firstPower = Math.min(config.tapPower(), milestonePower - 1);
            sendPower(player, firstPower);
            Commentary firstLead = awaitLine(firstMessages, raceId, "lead");
            Commentary secondLead = awaitLine(secondMessages, raceId, "lead");
            assertEquals(firstLead, secondLead);
            assertEquals("template", firstLead.source());
            assertEquals("Mountains takes the lead!", firstLead.text());

            for (int remaining = milestonePower - firstPower; remaining > 0;) {
                int batch = Math.min(PowerResource.MAX_QUANTITY, remaining);
                sendPower(player, batch);
                remaining -= batch;
            }
            Commentary firstMilestone = awaitLine(firstMessages, raceId, "milestone");
            Commentary secondMilestone = awaitLine(secondMessages, raceId, "milestone");
            assertEquals(firstMilestone, secondMilestone);
            assertEquals("Mountains passes 25%.", firstMilestone.text());
            assertEquals("template", firstMilestone.source());
            assertNotEquals(firstLead.id(), firstMilestone.id());

            first.closeAndAwait();
            first = null;
            List<JsonObject> reconnectedMessages = new CopyOnWriteArrayList<>();
            first = connect(admin, reconnectedMessages);
            Commentary replay = awaitLine(reconnectedMessages, raceId, "milestone");
            assertEquals(firstMilestone, replay);

            commentaryOut.send(firstMilestone).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertNoExtraLine(reconnectedMessages, secondMessages, firstMilestone.id());

            String nextRace = reset(admin);
            assertNotEquals(raceId, nextRace);
            awaitRace(reconnectedMessages, nextRace);
            awaitRace(secondMessages, nextRace);
            await().atMost(DELIVERY_TIMEOUT).until(() -> feed.latest().isEmpty());

            Commentary stale = new Commentary(firstMilestone.id() + ":stale", raceId, "milestone",
                System.currentTimeMillis(), System.currentTimeMillis(), "Old race commentary", "template");
            commentaryOut.send(stale).toCompletableFuture().get(10, TimeUnit.SECONDS);
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertTrue(feed.latest().isEmpty());
                assertEquals(0, count(reconnectedMessages, stale.id()));
                assertEquals(0, count(secondMessages, stale.id()));
            });
        } finally {
            if (first != null) first.closeAndAwait();
            if (second != null) second.closeAndAwait();
            event(admin, "reset");
        }
    }

    private WebSocketClientConnection connect(Cookie admin, List<JsonObject> messages) {
        return BasicWebSocketConnector.create().baseUri(baseUri).path("/ws/dashboard")
            .addHeader("Cookie", admin.getName() + "=" + admin.getValue())
            .addHeader("Origin", baseUri.getScheme() + "://" + baseUri.getAuthority())
            .onTextMessage((connection, message) -> messages.add(new JsonObject(message)))
            .connectAndAwait();
    }

    private static Commentary awaitLine(List<JsonObject> messages, String raceId, String trigger) {
        return await().atMost(DELIVERY_TIMEOUT).until(() -> messages.stream()
                .filter(message -> "commentary".equals(message.getString("type")))
                .map(message -> message.getJsonObject("commentary").mapTo(Commentary.class))
                .filter(line -> raceId.equals(line.raceId()) && trigger.equals(line.trigger()))
                .findFirst(), java.util.Optional::isPresent)
            .orElseThrow();
    }

    private static void awaitRace(List<JsonObject> messages, String raceId) {
        await().atMost(DELIVERY_TIMEOUT).until(() -> messages.stream().anyMatch(message ->
            "race".equals(message.getString("type"))
                && raceId.equals(message.getJsonObject("race").getString("raceId"))));
    }

    private static void assertNoExtraLine(List<JsonObject> first, List<JsonObject> second, String id) {
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertEquals(1, count(first, id));
            assertEquals(1, count(second, id));
        });
    }

    private static long count(List<JsonObject> messages, String id) {
        return messages.stream().filter(message -> "commentary".equals(message.getString("type")))
            .filter(message -> id.equals(message.getJsonObject("commentary").getString("id"))).count();
    }

    private static RaceState state() {
        return given().get("/api/race/state").then().statusCode(200).extract().as(RaceState.class);
    }

    private static String reset(Cookie admin) {
        String previous = state().raceId();
        event(admin, "reset");
        return await().atMost(Duration.ofSeconds(60)).until(CommentaryDeliveryTest::state,
            race -> !race.raceId().equals(previous) && race.status() == RaceState.Status.WAITING).raceId();
    }

    private static void event(Cookie admin, String type) {
        given().cookie(admin).contentType(JSON).body(Map.of("type", type, "data", Map.of()))
            .post("/api/game/event").then().statusCode(204);
    }

    private static GameResource.User assign(int team) {
        return given().queryParam("team", team).post("/api/game/assign")
            .then().statusCode(200).extract().as(GameResource.User.class);
    }

    private static void sendPower(GameResource.User player, int quantity) {
        given().contentType(JSON).body(new PowerResource.Power(quantity, player.name(), 1))
            .post("/api/power").then().statusCode(204);
    }
}
