import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import io.quarkiverse.quinoa.testing.QuarkusPlaywrightManager;
import io.quarkiverse.quinoa.testing.QuinoaTestProfiles;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@QuarkusTest
@TestProfile(QuinoaTestProfiles.Enable.class)
@QuarkusTestResource(QuarkusPlaywrightManager.class)
public class RaceWebUITest {

    public static final int NB_PLAYERS = 10;
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(NB_PLAYERS);
    public static final Page.WaitForSelectorOptions LONG_TIMEOUT_OPTIONS = new Page.WaitForSelectorOptions().setTimeout(200000);

    @QuarkusPlaywrightManager.InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/dashboard")
    URL dashboard;

    @TestHTTPResource("/")
    URL gameController;

    @Test
    void testWith500Players() throws InterruptedException {
        final Page page = context.newPage();
        Response response = page.navigate(dashboard.toString());
        Assertions.assertEquals("OK", response.statusText());

        page.waitForLoadState();

        String title = page.title();
        Assertions.assertEquals("Quarkus Race Demo", title);

        final CountDownLatch latch1 = new CountDownLatch(NB_PLAYERS / 2);
        final CountDownLatch latch2 = new CountDownLatch(NB_PLAYERS / 2);
        final CountDownLatch latchDone1 = new CountDownLatch(NB_PLAYERS);
        final CountDownLatch latchDone2 = new CountDownLatch(NB_PLAYERS);
        for (int i = 0; i < NB_PLAYERS / 2; i++) {
            EXECUTOR.execute(new Player(latch1, latchDone1));
        }
        for (int i = 0; i < NB_PLAYERS / 2; i++) {
            EXECUTOR.execute(new Player(latch2, latchDone2));
        }
        latch1.await();
        page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/test-screenshots/race-ui-test-1.png")));
        System.out.println("Clicking on 'play'");
        final ElementHandle playEl = page.waitForSelector("#play");
        playEl.click();
        System.out.println("Clicking on 'play' done");
        page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/test-screenshots/race-ui-test-2.png")));
        latchDone1.await();
        page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/test-screenshots/race-ui-test-4.png")));
        latchDone2.await();
        page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target/test-screenshots/race-ui-test-5.png")));
    }

    private class Player implements Runnable {

        private CountDownLatch latchAssign;
        private CountDownLatch latchDone;

        public Player(CountDownLatch latchAssign, CountDownLatch latchDone) {
            this.latchAssign = latchAssign;
            this.latchDone = latchDone;
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

            final ElementHandle userIdEl = controllerPage.waitForSelector("#user-id", LONG_TIMEOUT_OPTIONS);
            final String userId = userIdEl.innerText();
            System.out.println(userId);
            latchAssign.countDown();
            final ElementHandle generatorEl = controllerPage.waitForSelector("#generator", LONG_TIMEOUT_OPTIONS);
            System.out.println("Generator open");
            for (int j = 0; j < 150; j++) {
                generatorEl.click();
            }
            playwright.close();
            latchDone.countDown();
        }
    }
}