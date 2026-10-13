package org.acme;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Cookie;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class AdminLoginTest {

    private static final Map<String, Object> PAUSE = Map.of("type", "pause", "data", Map.of());

    @Test
    void adminCanLogInAndSendGameEvents() {
        Cookie session = given().redirects().follow(false)
            .formParam("j_username", "developer")
            .formParam("j_password", "developer")
            .post("/j_security_check")
            .then().statusCode(302).header("Location", endsWith("/"))
            .extract().detailedCookie("quarkus-credential");
        assertNotNull(session);

        given().cookie(session).contentType(JSON).body(PAUSE)
            .post("/api/game/event")
            .then().statusCode(204);
    }

    @Test
    void wrongPasswordIsRejected() {
        given().redirects().follow(false)
            .formParam("j_username", "developer")
            .formParam("j_password", "wrong")
            .post("/j_security_check")
            .then().statusCode(302).header("Location", endsWith("/login-error"));
    }

    @Test
    void gameEventsNeedALogin() {
        given().redirects().follow(false).contentType(JSON).body(PAUSE)
            .post("/api/game/event")
            .then().statusCode(302).header("Location", endsWith("/login"));
    }
}
