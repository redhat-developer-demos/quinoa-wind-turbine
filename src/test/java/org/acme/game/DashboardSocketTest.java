package org.acme.game;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.websockets.next.BasicWebSocketConnector;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DashboardSocketTest {

    @TestHTTPResource("/")
    URI baseUri;

    @Test
    void adminReceivesTheRace() {
        List<JsonObject> messages = new CopyOnWriteArrayList<>();
        WebSocketClientConnection connection = connector()
            .addHeader("Cookie", "quarkus-credential=" + login())
            .addHeader("Origin", baseUri.getScheme() + "://" + baseUri.getAuthority())
            .onTextMessage((c, message) -> messages.add(new JsonObject(message)))
            .connectAndAwait();
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> !messages.isEmpty());
            JsonObject first = messages.getFirst();
            assertTrue(first.getString("type").equals("race") && first.getJsonObject("race").containsKey("raceId"),
                first::encode);
        } finally {
            connection.closeAndAwait();
        }
    }

    @Test
    void anonymousIsRejected() {
        assertThrows(RuntimeException.class, () -> connector().connectAndAwait());
    }

    @Test
    void otherSitesAreRejected() {
        assertThrows(RuntimeException.class, () -> connector()
            .addHeader("Cookie", "quarkus-credential=" + login())
            .addHeader("Origin", "https://attacker.example")
            .connectAndAwait());
    }

    @Test
    void comparesOriginWithHost() {
        assertTrue(DashboardSocket.SameOriginCheck.sameHost("http://localhost:8080", "localhost:8080"));
        assertTrue(DashboardSocket.SameOriginCheck.sameHost("https://race.example.com", "race.example.com"));
        assertTrue(!DashboardSocket.SameOriginCheck.sameHost("https://race.example.com", "localhost:8080"));
        assertTrue(!DashboardSocket.SameOriginCheck.sameHost("not a uri", "localhost:8080"));
    }

    private BasicWebSocketConnector connector() {
        return BasicWebSocketConnector.create().baseUri(baseUri).path("/ws/dashboard");
    }

    private static String login() {
        return given().redirects().follow(false)
            .formParam("j_username", "developer")
            .formParam("j_password", "developer")
            .post("/j_security_check")
            .then().statusCode(302)
            .extract().cookie("quarkus-credential");
    }
}
