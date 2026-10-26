package org.acme.theme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.Cookie;
import org.acme.commentary.OpenAiStub;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The AI theme endpoints against the local OpenAI stub, with AI enabled like AiCommentatorTest.
 * A generated theme only applies when it passes validation; anything else keeps the current theme,
 * so a bad model answer can never break the board.
 */
@Tag("optional")
@QuarkusTest
@TestProfile(ThemeAiTest.Enabled.class)
@QuarkusTestResource(value = OpenAiStub.class, restrictToAnnotatedClass = true)
class ThemeAiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String VALID_THEME = """
        {"name": "Derby Days", "teams": [
          {"id": 1, "name": "Stallions", "color": "#8b1a1a", "car": "car-red"},
          {"id": 2, "name": "Comets", "color": "#f4d35e", "car": "car-orange"}]}""";

    private static final String INVALID_THEME = """
        {"name": "Broken", "teams": [
          {"id": 1, "name": "Jets", "color": "#111111", "car": "car-flying"},
          {"id": 2, "name": "Sharks", "color": "#eeeeee", "car": "car-grey"}]}""";

    public static class Enabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("game.ai.enabled", "true");
        }
    }

    @Test
    void generatedThemeIsAppliedAndUsesTheNamedModel() throws Exception {
        Cookie session = login();
        OpenAiStub.respond(200, VALID_THEME, 0);

        given().cookie(session).contentType(JSON).body(Map.of("prompt", "a county fair"))
            .post("/api/theme/generate")
            .then().statusCode(200)
            .body("name", org.hamcrest.Matchers.equalTo("Derby Days"));

        awaitTeamNames("Stallions", "Comets");

        OpenAiStub.Request request = OpenAiStub.REQUESTS.take();
        assertEquals("POST", request.method());
        assertEquals("/v1/chat/completions", request.path());
        assertEquals("Bearer fake-key", request.authorization());
        JsonNode body = MAPPER.readTree(request.body());
        assertEquals("test-model", body.path("model").asText());
    }

    @Test
    void generatedThemeThatFailsValidationKeepsTheCurrentOne() {
        Cookie session = login();
        // A known theme first, so the test can tell a kept theme from the default.
        given().cookie(session).post("/api/theme/preset/forest-sun").then().statusCode(204);
        awaitTeamNames("Forest", "Sunshine");

        OpenAiStub.respond(200, INVALID_THEME, 0);

        given().cookie(session).contentType(JSON).body(Map.of("prompt", "a county fair"))
            .post("/api/theme/generate")
            .then().statusCode(422).body("errors", hasItem("Unknown car: car-flying"));

        awaitTeamNames("Forest", "Sunshine");
    }

    @Test
    void unparseableModelOutputAnswers502() {
        Cookie session = login();
        OpenAiStub.respond(200, "I would rather not design a theme today.", 0);

        given().cookie(session).contentType(JSON).body(Map.of("prompt", "a county fair"))
            .post("/api/theme/generate")
            .then().statusCode(502).body("errors", hasItem("The model did not return a usable theme"));
    }

    @Test
    void suggestedNamesApplyOntoTheCurrentColors() {
        Cookie session = login();
        // Start from a preset, so the test can tell the kept colors apart from the defaults.
        given().cookie(session).post("/api/theme/preset/classic").then().statusCode(204);
        awaitTeamNames("Mountains", "Beach");
        String colors = given().get("/api/config").then().extract().jsonPath().getString("teams.color.toString()");

        OpenAiStub.respond(200, "{\"team1\": \"Clams\", \"team2\": \"Mussels\"}", 0);
        given().cookie(session).contentType(JSON).body(Map.of("prompt", "a seafood festival"))
            .post("/api/theme/suggest-names")
            .then().statusCode(200)
            .body("team1", org.hamcrest.Matchers.equalTo("Clams"))
            .body("team2", org.hamcrest.Matchers.equalTo("Mussels"));

        awaitTeamNames("Clams", "Mussels");
        // The names changed, the preset's colors stayed.
        assertEquals(colors, given().get("/api/config").then().extract()
            .jsonPath().getString("teams.color.toString()"));
    }

    @Test
    void blankPromptIsRejectedWithoutCallingTheModel() {
        Cookie session = login();
        given().cookie(session).contentType(JSON).body(Map.of("prompt", "  "))
            .post("/api/theme/generate")
            .then().statusCode(422)
            .body("errors", hasItem("A prompt is required, such as a city, event or audience"));
        given().cookie(session).contentType(JSON).body(Map.of("prompt", ""))
            .post("/api/theme/suggest-names")
            .then().statusCode(422);
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
}
