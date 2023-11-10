///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.6.3
//DEPS io.vertx:vertx-web-client:4.3.4
//JAVA 17+

import io.vertx.ext.web.client.predicate.ResponsePredicate;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URL;
import java.util.concurrent.Callable;


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
import java.util.concurrent.atomic.AtomicBoolean;


@Command(name = "GameLoader", mixinStandardHelpOptions = true, version = "GameLoader 0.1",
        description = "GameLoader made with jbang")
class GameLoader implements Callable<Integer> {

    private static final Random R = new Random();


    @Parameters(index = "0", description = "The url", defaultValue = "http://localhost:8080")
    private URL url;

    @Option(names = {"-p", "--players"}, description = "The amount of players to assign", defaultValue = "50")
    private int players;

    @Option(names = {"-c", "--clicks"}, description = "How many click each player will trigger", defaultValue = "100")
    private int clicks;

    @Option(names = {"--power"}, description = "Click power", defaultValue = "15")
    private int power;


    public static void main(String... args) {
        int exitCode = new CommandLine(new GameLoader()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception { // your business logic goes here...
        load();
        return 0;
    }

    void load() throws InterruptedException {
        boolean ssl = url.getProtocol().equals("https");
        Vertx vertx = Vertx.vertx();
        WebClient client = WebClient.create(vertx, new WebClientOptions().setTrustAll(true).setVerifyHost(false).setMaxPoolSize(500));
        final List<JsonObject> users = Collections.synchronizedList(new ArrayList<>());
        final Set<String> names = new HashSet<>();
        final CountDownLatch latchLogin = new CountDownLatch(players);
        final int port = url.getPort() == -1 ? ssl ? 443 : 80 : url.getPort();
        for (int i = 0; i < players; i++) {
            final int index = i;
            client.request(HttpMethod.POST, port, url.getHost(),
                            "/api/game/assign/"+i+"?team=" + (i % 2 + 1 ))
                    .ssl(ssl)
                    .expect(ResponsePredicate.SC_SUCCESS)
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
            client.request(HttpMethod.GET, port, url.getHost(),
                            "/api/game/status")
                    .ssl(ssl)
                    .expect(ResponsePredicate.SC_SUCCESS)
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
            Thread.sleep(500);
        }
        System.out.println("game started");


        final CountDownLatch latchClick = new CountDownLatch(players * clicks);

        for (int i = 0; i < clicks; i++) {
            for (JsonObject user : users) {
                client.request(HttpMethod.POST, port, url.getHost(),
                                "/api/power")
                        .expect(ResponsePredicate.SC_SUCCESS)
                        .ssl(ssl)
                        .sendJsonObject(new JsonObject().put("quantity", power).put("source", user.getString("name"))
                                .put("destination", user.getInteger("team")))
                        .onComplete((r) -> {
                            if (r.failed()) {
                                r.cause().printStackTrace();
                            }
                            latchClick.countDown();
                        });
            }
            Thread.sleep(200);
        }
        latchClick.await();
    }
}

