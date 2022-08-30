package org.acme;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoadTest {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    public static final int USERS = 500;
    public static final int CLICKS = 200;
    public static final Random R = new Random();

    @Test
    void load() throws InterruptedException {
        Vertx vertx = Vertx.vertx();
        WebClient client = WebClient.create(vertx, new WebClientOptions().setTrustAll(true).setVerifyHost(false).setMaxPoolSize(500));
        final List<GameResource.User> users = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch latchLogin = new CountDownLatch(USERS);
        for (int i = 0; i < USERS; i++) {
            client.request(HttpMethod.POST, 443, "quinoa-wind-turbine-adamevin-dev.apps.sandbox.x8i5.p1.openshiftapps.com",
                            "/api/game/assign")
                    .ssl(true)
                    .send()
                    .onComplete(r -> {
                        if (r.failed()) {
                            r.cause().printStackTrace();
                            return;
                        }
                        System.out.println(r.result().bodyAsString());
                        final GameResource.User user = r.result().bodyAsJson(GameResource.User.class);
                        users.add(user);
                        System.out.println("login " + user);
                        latchLogin.countDown();
                    });
        }
        latchLogin.await();
        Thread.sleep(5000);
        System.out.println("Users created");
        final CountDownLatch latchClick = new CountDownLatch(USERS * CLICKS);

        for (int i = 0; i < CLICKS; i++) {
            for (GameResource.User user : users) {
                client.request(HttpMethod.POST, 443, "quinoa-wind-turbine-adamevin-dev.apps.sandbox.x8i5.p1.openshiftapps.com",
                                "/api/power")
                        .ssl(true)
                        .sendJsonObject(new JsonObject().put("quantity", R.nextInt(20, user.team() == 1 ? 35 : 40) ).put("source", user.name())
                                .put("destination", user.team()))
                        .onComplete((r) -> {
                            if (r.failed()) {
                                r.cause().printStackTrace();
                            }
                            latchClick.countDown();
                        });
            }
        }
        latchClick.await();
    }

}
