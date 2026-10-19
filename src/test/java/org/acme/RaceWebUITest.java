package org.acme;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkiverse.quinoa.testing.QuinoaTestProfiles;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;

@QuarkusTest
@TestProfile(QuinoaTestProfiles.Enable.class)
@WithPlaywright
@Tag("e2e")
public class RaceWebUITest {
    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/dashboard")
    URL dashboard;

    @TestHTTPResource("/")
    URL gameController;

    @Test
    void fourPlayersFinishWithCommentaryAndReset() {
        List<BrowserContext> players = new ArrayList<>();
        Page page = context.newPage();
        page.onPageError(error -> System.err.println("Browser error: " + error));
        try {
            Assertions.assertEquals(200, page.navigate(dashboard.toString()).status());
            page.fill("input[name=j_username]", "developer");
            page.fill("input[name=j_password]", "developer");
            page.click("button[type=submit]");
            page.waitForURL(dashboard.toString());
            page.navigate(dashboard.toString());
            page.waitForSelector("[aria-label='Race commentary']", new Page.WaitForSelectorOptions()
                .setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED));
            Assertions.assertEquals("Quarkus Race Demo", page.title());
            page.evaluate("async () => { const r = await fetch('/api/game/event', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({type:'reset'})}); if (!r.ok) throw new Error('reset failed'); }");
            await().atMost(Duration.ofSeconds(15)).until(() -> race().status() == RaceState.Status.WAITING);

            List<Page> phones = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                BrowserContext player = context.browser().newContext();
                players.add(player);
                Page phone = player.newPage();
                phones.add(phone);
                Assertions.assertEquals(200, phone.navigate(gameController.toString()).status());
                phone.locator(".button" + (i % 2 + 1)).click();
                assertThat(phone.locator("#user-name")).isVisible();
            }
            await().atMost(Duration.ofSeconds(15)).until(() -> race().players().size() == 4);
            page.evaluate("""
                () => {
                  const ticker = document.querySelector('[aria-label="Race commentary"]');
                  window.finishObservations = [];
                  window.finishObserver = new MutationObserver(() => {
                    if (ticker.textContent.includes('wins')) {
                      window.finishObservations.push([1, 2].map(id =>
                        parseFloat(getComputedStyle(document.querySelector('#car' + id)).offsetDistance)));
                    }
                  });
                  window.finishObserver.observe(ticker, {childList:true, characterData:true, subtree:true});
                }
                """);
            page.locator("#play").click();
            for (Page phone : phones) {
                assertThat(phone.locator("#generator")).isVisible();
            }
            for (int taps = 0; taps < 150 && race().status() != RaceState.Status.FINISHED; taps++) {
                for (Page phone : phones) {
                    if (race().status() == RaceState.Status.FINISHED) break;
                    try {
                        phone.locator("#generator").click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(2000));
                    } catch (PlaywrightException failure) {
                        if (race().status() != RaceState.Status.FINISHED) throw failure;
                    }
                }
            }
            RaceState finished = await().atMost(Duration.ofSeconds(30))
                .until(RaceWebUITest::race, s -> s.status() == RaceState.Status.FINISHED);
            Assertions.assertEquals(4, finished.players().size());
            assertThat(page.getByText("won the game", new Page.GetByTextOptions().setExact(false))).isVisible();
            var ticker = page.locator("[aria-label='Race commentary']");
            assertThat(ticker).containsText("wins");
            String winnerName = given().get("/api/config").then().extract()
                .path("teams[" + (finished.winner() - 1) + "].name");
            assertThat(ticker).hasText(winnerName + " wins the race!");
            Number visibleDistance = (Number) page.evaluate("team => window.finishObservations[0][team - 1]", finished.winner());
            Assertions.assertTrue(visibleDistance.doubleValue() >= 99.99,
                "Winning car must cross the finish line before the win announcement: " + visibleDistance);
            String finishLine = ticker.innerText();
            page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/test-screenshots/commentary-finish.png")));
            page.reload();
            assertThat(ticker).hasText(finishLine);
            page.locator("#reset").click();
            await().atMost(Duration.ofSeconds(15)).until(() -> !race().raceId().equals(finished.raceId()));
            assertThat(ticker).isEmpty();
            assertThat(page.getByText("won the game", new Page.GetByTextOptions().setExact(false))).hasCount(0);
            page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/test-screenshots/commentary-reset.png")));
        } catch (RuntimeException | AssertionError failure) {
            System.err.println("Dashboard URL: " + page.url() + "; content: " + page.locator("body").innerText());
            page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/test-screenshots/commentary-failure.png")));
            throw failure;
        } finally {
            players.forEach(BrowserContext::close);
            page.close();
        }
    }

    private static RaceState race() {
        return given().get("/api/race/state").then().statusCode(200).extract().as(RaceState.class);
    }
}
