package org.raceanalyst.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the app and speaks to it the way race-ops's A2A client does: card first, then one
 * task. This is the test that catches wiring mistakes the unit tests cannot see — a missing
 * executor bean, a qualifier the SDK does not resolve, a route that never registered.
 */
@QuarkusTest
class AnalystEndpointTest {

    private static final String SNAPSHOTS = """
        {"base":{"raceId":"race-1","status":"STARTED","elapsedMs":1000,"teams":[
            {"id":1,"position":1,"players":2,"power":300,"distance":50.0},
            {"id":2,"position":2,"players":2,"power":100,"distance":35.0}],
          "players":[{"name":"ada","team":1,"generated":150},{"name":"bo","team":2,"generated":100},
                     {"name":"cy","team":2,"generated":10}]},
         "current":{"raceId":"race-1","status":"STARTED","elapsedMs":6000,"teams":[
            {"id":1,"position":1,"players":2,"power":340,"distance":55.0},
            {"id":2,"position":2,"players":2,"power":100,"distance":37.0}],
          "players":[{"name":"ada","team":1,"generated":180},{"name":"bo","team":2,"generated":100},
                     {"name":"cy","team":2,"generated":10}]}}
        """;

    @Test
    void servesItsCard() {
        given()
            .when().get("/.well-known/agent-card.json")
            .then().statusCode(200)
            .body("name", equalTo("Race Analyst"))
            .body("skills[0].id", equalTo("diagnose-engagement"));
    }

    @Test
    void answersOneAnalysisTask() throws Exception {
        String request = """
            {"jsonrpc":"2.0","id":"1","method":"SendMessage","params":{"message":{
              "role":"ROLE_USER","messageId":"msg-1","parts":[{"text":%s}]}}}
            """.formatted(quote(SNAPSHOTS));

        String response = given()
            .contentType("application/json")
            // The SDK rejects a missing version as 0.3 (the spec's backward-compat default)
            // and only serves 1.0; the A2A client sets this header itself.
            .header("A2A-Version", "1.0")
            .body(request)
            .when().post("/")
            .then().statusCode(200)
            .extract().asString();

        JsonNode task = new ObjectMapper().readTree(response).get("result").get("task");
        org.junit.jupiter.api.Assertions.assertTrue(task != null && task.has("status"),
            "RESULT >>> " + response);
        assertEquals("TASK_STATE_COMPLETED", task.get("status").get("state").asText(), response);
        JsonNode analysis = new ObjectMapper().readTree(
            task.get("artifacts").get(0).get("parts").get(0).get("text").asText());
        assertEquals("race-1", analysis.get("engagement").get("raceId").asText());
        assertTrue(analysis.get("diagnosis").asText().contains("Team 2"),
            analysis.get("diagnosis").asText());
    }

    private static String quote(String json) {
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") + "\"";
    }
}
