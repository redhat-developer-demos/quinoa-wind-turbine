package org.acme;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Disabled
public class LoadTest {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    public static final int USERS = 500;
    public static final int CLICKS = 200;
    public static final Random R = new Random();
    public static final String SERVER_HOST = "quinoa-wind-turbine-windturbine-demo.apps.openshift.sotogcp.com";

    @Test
    void load() throws InterruptedException {
        Vertx vertx = Vertx.vertx();
        WebClient client = WebClient.create(vertx, new WebClientOptions().setTrustAll(true).setVerifyHost(false).setMaxPoolSize(500));
        final List<GameResource.User> users = Collections.synchronizedList(new ArrayList<>());
        final Set<String> names = new HashSet<>();
        final CountDownLatch latchLogin = new CountDownLatch(USERS);
        for (int i = 0; i < USERS; i++) {
            final int index = i;
            client.request(HttpMethod.POST, 443, SERVER_HOST,
                            "/api/game/assign")
                    .ssl(true)
                    .send()
                    .onComplete(r -> {
                        if (r.failed()) {
                            r.cause().printStackTrace();
                            return;
                        }
                        try {
                            final GameResource.User user = r.result().bodyAsJson(GameResource.User.class);
                            users.add(user);
                            names.add(user.name());
                            System.out.println("login " + user + " " + index);
                            latchLogin.countDown();
                        } catch (Exception e) {
                            System.out.println(r.result().bodyAsString());
                        }

                    });
        }
        latchLogin.await();
        Thread.sleep(5000);
        System.out.println(users.size() + "users created");
        System.out.println(names.size() + " different names");
        AtomicBoolean started = new AtomicBoolean(false);
        while (!started.get()) {
            client.request(HttpMethod.GET, 443, SERVER_HOST,
                            "/api/game/status")
                    .ssl(true)
                    .send()
                    .onComplete(r -> {
                        if (r.failed()) {
                            r.cause().printStackTrace();
                            return;
                        }
                        final String status = r.result().bodyAsJsonObject().getString("type");
                        System.out.println(status);
                        started.set(Objects.equals(status, "start"));
                    });
            Thread.sleep(2000);
        }
        System.out.println("game started");


        final CountDownLatch latchClick = new CountDownLatch(USERS * CLICKS);

        for (int i = 0; i < CLICKS; i++) {
            for (GameResource.User user : users) {
                client.request(HttpMethod.POST, 443, SERVER_HOST,
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

    @Test
    void name() {
        System.out.println(Utils.NAMES.size());
        System.out.println(new HashSet<>(Utils.NAMES).size());
    }
}
