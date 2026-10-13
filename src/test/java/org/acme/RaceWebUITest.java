package org.acme;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@QuarkusTest
@TestProfile(QuinoaTestProfiles.Enable.class)
@WithPlaywright
@Tag("e2e")
public class RaceWebUITest {

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    public static final Page.WaitForSelectorOptions LONG_TIMEOUT_OPTIONS = new Page.WaitForSelectorOptions().setTimeout(200000);

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/dashboard")
    URL dashboard;

    @TestHTTPResource("/")
    URL gameController;

    @Test
    void testWith10Players() throws InterruptedException {
        final Page page = context.newPage();
        Response response = page.navigate(dashboard.toString());
        Assertions.assertEquals("OK", response.statusText());

        page.waitForLoadState();

        String title = page.title();
        Assertions.assertEquals("Quarkus Race Demo", title);

        final int TEAM_PLAYERS_COUNT = 10 / 2;
        final CountDownLatch latch1 = new CountDownLatch(TEAM_PLAYERS_COUNT);
        final CountDownLatch latch2 = new CountDownLatch(TEAM_PLAYERS_COUNT);
        final CountDownLatch latchDone1 = new CountDownLatch(TEAM_PLAYERS_COUNT);
        final CountDownLatch latchDone2 = new CountDownLatch(TEAM_PLAYERS_COUNT);
        final List<GameResource.User> users = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < TEAM_PLAYERS_COUNT; i++) {
            EXECUTOR.execute(new Player(latch1, users, latchDone1, true, 1));
        }
        for (int i = 0; i < TEAM_PLAYERS_COUNT; i++) {
            EXECUTOR.execute(new Player(latch2, users, latchDone2, true, 2));
        }
        latch1.await();
        latch2.await();
        page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/test-screenshots/race-ui-test-1.png")));
        System.out.println("Clicking on 'play'");
        final ElementHandle playEl = page.waitForSelector("#play");
        playEl.click();
        System.out.println("Clicking on 'play' done");
        page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/test-screenshots/race-ui-test-2.png")));
        latchDone1.await();
        System.out.println("Middle");
        page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/test-screenshots/race-ui-test-4.png")));
        latchDone2.await();
        page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/test-screenshots/race-ui-test-5.png")));
        System.out.println("End");
    }

    private class Player implements Runnable {

        private CountDownLatch latchAssign;
        private final List<GameResource.User> users;
        private CountDownLatch latchDone;
        private boolean playInBrowser;
        private int team;

        public Player(CountDownLatch latchAssign, List<GameResource.User> users, CountDownLatch latchDone,
                boolean playInBrowser, int team) {
            this.latchAssign = latchAssign;
            this.users = users;
            this.latchDone = latchDone;
            this.playInBrowser = playInBrowser;
            this.team = team;
        }

        @Override public void run() {
            final Playwright playwright = Playwright.create();
            Browser browser = playwright.chromium().launch((new BrowserType.LaunchOptions()).setArgs(
                    List.of("--headless", "--disable-gpu", "--no-sandbox")));
            BrowserContext gameControllerContext = browser.newContext();
            final Page controllerPage = gameControllerContext.newPage();
            Response controllerResponse = controllerPage.navigate(gameController.toString());
            Assertions.assertEquals("OK", controllerResponse.statusText());
            controllerPage.waitForLoadState();

            final ElementHandle teamBtn = controllerPage.waitForSelector(".button" + team, LONG_TIMEOUT_OPTIONS);
            teamBtn.click();

            final ElementHandle userNameEl = controllerPage.waitForSelector("#user-name", LONG_TIMEOUT_OPTIONS);
            final String userName = userNameEl.innerText();
            users.add(new GameResource.User(-1, userName, team));
            System.out.println(userName + " joined team " + team);
            latchAssign.countDown();
            if(!playInBrowser) {
                playwright.close();
                return;
            }
            final ElementHandle generatorEl = controllerPage.waitForSelector("#generator", LONG_TIMEOUT_OPTIONS);
            System.out.println("Generator open for " + userName);
            for (int j = 0; j < 150; j++) {
                generatorEl.click();
            }
            playwright.close();
            latchDone.countDown();
        }
    }
}