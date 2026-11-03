package org.acme.input;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Cookie;
import jakarta.inject.Inject;
import org.acme.admin.AdminSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class InputModeServiceTest {

    @Inject
    InputModeService inputModeService;

    @BeforeEach
    @AfterEach
    void resetModeAndRace() {
        inputModeService.clear();
        Cookie session = AdminSession.login();
        given().cookie(session).contentType(JSON).body(Map.of("type", "reset"))
            .post("/api/game/event").then().statusCode(204);
        await().atMost(Duration.ofSeconds(10)).until(this::gameStatus, is("reset"));
    }

    @Test
    void defaultModeIsConfiguredDefault() {
        assertEquals(InputMode.TAP, inputModeService.defaultMode());
        assertEquals(InputMode.TAP, inputModeService.current());
        given().get("/api/config")
            .then()
            .statusCode(200)
            .body("inputMode", is("tap"));
    }

    @Test
    void applyAndReadBack() {
        List<String> errors = inputModeService.apply(InputMode.SHAKE);
        assertTrue(errors.isEmpty(), "Valid mode should be applied with no errors");
        assertEquals(InputMode.SHAKE, inputModeService.current());

        given().get("/api/config")
            .then()
            .statusCode(200)
            .body("inputMode", is("shake"));

        errors = inputModeService.apply("both");
        assertTrue(errors.isEmpty());
        assertEquals(InputMode.BOTH, inputModeService.current());

        given().get("/api/config")
            .then()
            .statusCode(200)
            .body("inputMode", is("both"));
    }

    @Test
    void midRaceRejection() {
        Cookie session = AdminSession.login();
        given().cookie(session).contentType(JSON).body(Map.of("type", "start"))
            .post("/api/game/event").then().statusCode(204);
        await().atMost(Duration.ofSeconds(10)).until(this::gameStatus, is("start"));

        List<String> errors = inputModeService.apply(InputMode.SHAKE);
        assertFalse(errors.isEmpty(), "Should reject mode change mid-race");
        assertEquals(List.of("Input mode can only be changed between races"), errors);
        assertEquals(InputMode.TAP, inputModeService.current());
    }

    @Test
    void invalidModeRejection() {
        List<String> errors = inputModeService.apply("unknown_mode");
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("Unknown input mode"));
        assertEquals(InputMode.TAP, inputModeService.current());

        errors = inputModeService.apply((InputMode) null);
        assertFalse(errors.isEmpty());
        assertEquals(InputMode.TAP, inputModeService.current());
    }

    @Test
    void inputModeEventDoesNotDisturbGameStatus() {
        assertEquals("reset", gameStatus());

        List<String> errors = inputModeService.apply(InputMode.BOTH);
        assertTrue(errors.isEmpty());

        assertEquals("reset", gameStatus(), "input-mode event must not change last game event");
    }

    private String gameStatus() {
        return given().get("/api/game/status").then().statusCode(200)
            .extract().jsonPath().getString("type");
    }
}
