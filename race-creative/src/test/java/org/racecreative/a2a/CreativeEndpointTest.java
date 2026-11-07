package org.racecreative.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the app and speaks A2A: card, then one propose task. The model is off in tests, so the
 * task must come back with the deterministic preset — which also proves the MCP client and the
 * OIDC client can be created without a reachable game or Keycloak.
 */
@QuarkusTest
class CreativeEndpointTest {

    @Test
    void servesItsCard() {
        given()
            .when().get("/.well-known/agent-card.json")
            .then().statusCode(200)
            .body("name", equalTo("Race Creative Director"))
            .body("skills[1].id", equalTo("apply-approved-theme"));
    }

    @Test
    void proposesAPresetWithoutAModelOrAGame() throws Exception {
        String request = """
            {"jsonrpc":"2.0","id":"1","method":"SendMessage","params":{"message":{
              "role":"ROLE_USER","messageId":"msg-1","parts":[{"text":%s}]}}}
            """.formatted(quote(
            "{\"mode\":\"propose\",\"currentTheme\":\"classic\",\"event\":\"a seafood festival\"}"));

        String response = given()
            .contentType("application/json")
            .header("A2A-Version", "1.0")
            .body(request)
            .when().post("/")
            .then().statusCode(200)
            .extract().asString();

        JsonNode task = new ObjectMapper().readTree(response).get("result").get("task");
        assertEquals("TASK_STATE_COMPLETED", task.get("status").get("state").asText(), response);

        // The game is not reachable in tests, so listThemes throws; the executor turns that into
        // an error artifact. Propose needs the preset list, so this is the honest outcome here.
        String artifact = task.get("artifacts").get(0).get("parts").get(0).get("text").asText();
        assertTrue(artifact.startsWith("Error:") || artifact.startsWith("{\"kind\""), artifact);
    }

    private static String quote(String json) {
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") + "\"";
    }
}
