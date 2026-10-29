package org.acme;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;

/**
 * The OpenAPI contract at /q/openapi is what reShapr imports to generate MCP tools, so it is a
 * demo-facing surface: the paths agents are offered and the title that names the service both
 * come from here. This pins the essentials, including the cheat endpoint that the raw
 * translation exposes and the reshaped plan must cherry-pick away.
 */
@QuarkusTest
class OpenApiSchemaTest {

    @Test
    void servesTheContractReShaprImports() {
        given()
            .accept(JSON)
            .when().get("/q/openapi")
            .then()
            .statusCode(200)
            .contentType(JSON)
            .body("info.title", is("Wind Turbine Racer"))
            .body("paths", allOf(
                hasKey("/api/race/state"),
                hasKey("/api/race/leaderboard"),
                hasKey("/api/game/status"),
                hasKey("/api/game/assign"),
                hasKey("/api/power"),
                hasKey("/api/cheat"),
                hasKey("/api/hall-of-fame"),
                hasKey("/api/config")))
            .body("paths.'/api/race/state'.get.summary", is("The current race state"))
            .body("paths.'/api/cheat'.get.summary", is("Secretly boost or sabotage a team"));
    }
}
