package org.acme;

import io.restassured.http.Cookie;

import static io.restassured.RestAssured.given;

/**
 * Logs in to the dashboard through the real form login. The admin paths only accept the form mechanism, so
 * {@code @TestSecurity} identities don't reach them.
 */
final class AdminSession {

    private AdminSession() {
    }

    static Cookie login() {
        return given().redirects().follow(false)
            .formParam("j_username", "developer")
            .formParam("j_password", "developer")
            .post("/j_security_check")
            .then().statusCode(302)
            .extract().detailedCookie("quarkus-credential");
    }
}
