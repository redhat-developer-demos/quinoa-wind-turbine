package org.acme;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static io.restassured.http.ContentType.TEXT;


@QuarkusTest
class PowerResourceTest {

  @Test
  void testGenerate() {
    PowerResource.Power power = new PowerResource.Power(20, "test", 2);

    given()
      .accept(TEXT)
      .contentType(JSON)
      .body(power)
      .when().post("api/power/")
      .then()
      .statusCode(204);
  }

  @Test
  void testGenerateTooManyTeams() {
    PowerResource.Power power = new PowerResource.Power(20, "test", 3);

    given()
      .accept(TEXT)
      .contentType(JSON)
      .body(power)
      .when().post("api/power/")
      .then()
      .statusCode(500);
  }
}
