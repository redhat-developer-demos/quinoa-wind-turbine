package org.acme;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class CheatResourceTest {

    @Test
    void triggersABoostForATeam() {
        given().get("/api/cheat?team=1")
            .then().statusCode(200).body(containsString("team 1"));
    }

    @Test
    void rejectsAMissingTeam() {
        given().get("/api/cheat")
            .then().statusCode(400);
    }
}

@QuarkusTest
@TestProfile(CheatKeyTest.Keyed.class)
class CheatKeyTest {

    public static class Keyed implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("game.cheat.key", "s3cret");
        }
    }

    @Test
    void requiresTheConfiguredKey() {
        given().get("/api/cheat?team=1").then().statusCode(403);
        given().get("/api/cheat?team=1&key=s3cret").then().statusCode(200);
    }
}
