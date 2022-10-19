///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS io.vertx:vertx-web-client:4.3.4

import java.util.concurrent.ExecutorService;

import static java.lang.System.*;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

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

public class SimulateGame {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    public static final int USERS = 50;
    public static final int CLICKS = 500;
    public static final Random R = new Random();
    public static final String SERVER_HOST = "localhost";
    public static final int SERVER_PORT = 8080;
    public static final boolean SSL = SERVER_PORT == 443;

    public static void main(String... args) throws InterruptedException {
        load();
    }

    static void load() throws InterruptedException {
        Vertx vertx = Vertx.vertx();
        WebClient client = WebClient.create(vertx, new WebClientOptions().setTrustAll(true).setVerifyHost(false).setMaxPoolSize(500));
        final List<JsonObject> users = Collections.synchronizedList(new ArrayList<>());
        final Set<String> names = new HashSet<>();
        final CountDownLatch latchLogin = new CountDownLatch(USERS);
        for (int i = 0; i < USERS; i++) {
            final int index = i;
            client.request(HttpMethod.POST, SERVER_PORT, SERVER_HOST,
                            "/api/game/assign")
                    .ssl(SSL)
                    .send()
                    .onComplete(r -> {
                        if (r.failed()) {
                            r.cause().printStackTrace();
                            return;
                        }
                        try {
                            final JsonObject user = r.result().bodyAsJsonObject();
                            users.add(user);
                            names.add(user.getString("name"));
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
            client.request(HttpMethod.GET, SERVER_PORT, SERVER_HOST,
                            "/api/game/status")
                    .ssl(SSL)
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
            for (JsonObject user : users) {
                client.request(HttpMethod.POST, SERVER_PORT, SERVER_HOST,
                                "/api/power")
                        .ssl(SSL)
                        .sendJsonObject(new JsonObject().put("quantity", R.nextInt(user.getInteger("team") == 1 ? 30 : 40) ).put("source", user.getString("name"))
                                .put("destination", user.getInteger("team")))
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
