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
    public static final int USERS = 700;
    public static final int CLICKS = 500;
    public static final Random R = new Random();

    public static void main(String... args) throws InterruptedException {
        load(args[0], Integer.parseInt(args[1]));
    }

    static void load(String host, int port) throws InterruptedException {
        boolean ssl = port == 443;
        Vertx vertx = Vertx.vertx();
        WebClient client = WebClient.create(vertx, new WebClientOptions().setTrustAll(true).setVerifyHost(false).setMaxPoolSize(500));
        final List<JsonObject> users = Collections.synchronizedList(new ArrayList<>());
        final Set<String> names = new HashSet<>();
        final CountDownLatch latchLogin = new CountDownLatch(USERS);
        for (int i = 0; i < USERS; i++) {
            final int index = i;
            client.request(HttpMethod.POST, port, host,
                            "/api/game/assign")
                    .ssl(ssl)
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
            client.request(HttpMethod.GET, port, host,
                            "/api/game/status")
                    .ssl(ssl)
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
                client.request(HttpMethod.POST, port, host,
                                "/api/power")
                        .ssl(ssl)
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
