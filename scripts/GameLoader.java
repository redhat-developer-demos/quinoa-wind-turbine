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
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
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
    private static final int MAX_ATTEMPTS = 60;


    @Parameters(index = "0", description = "The url", defaultValue = "http://localhost:8080")
    private URL url;

    @Option(names = {"-p", "--players"}, description = "The amount of players to assign", defaultValue = "50")
    private int players;

    @Option(names = {"-c", "--clicks"}, description = "How many click each player will trigger", defaultValue = "100")
    private int clicks;

    @Option(names = {"--power"}, description = "Click power (maximum, actual is random 1..power)", defaultValue = "15")
    private int power;

    @Option(names = {"--interval"}, description = "Milliseconds between click batches", defaultValue = "200")
    private int interval;

    @Option(names = {"--ws"}, description = "Send power over the player WebSocket instead of one POST per click")
    private boolean ws;


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
        final Set<String> names = Collections.synchronizedSet(new HashSet<>());
        final CountDownLatch latchLogin = new CountDownLatch(players);
        final int port = url.getPort() == -1 ? ssl ? 443 : 80 : url.getPort();
        System.out.println("Assigning " + players + " players");
        for (int i = 0; i < players; i++) {
            assign(vertx, client, port, ssl, i, 1, users, names, latchLogin);
        }
        latchLogin.await();
        System.out.println(users.size() + " users created");
        System.out.println(names.size() + " different names");
        AtomicBoolean started = new AtomicBoolean(false);
        if (ws) {
            loadOverWebSockets(vertx, users, port, ssl, started);
            return;
        }
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
                        .sendJsonObject(new JsonObject().put("quantity", R.nextInt(power) + 1).put("source", user.getString("name"))
                                .put("destination", user.getInteger("team")))
                        .onComplete((r) -> {
                            if (r.failed()) {
                                r.cause().printStackTrace();
                            }
                            latchClick.countDown();
                        });
            }
            Thread.sleep(interval);
        }
        latchClick.await();
    }

    /**
     * Assigns one player and retries when the request fails, for example while dev mode reloads the app and closes the
     * open connections. The latch counts down once the player is assigned or the last attempt failed.
     */
    void assign(Vertx vertx, WebClient client, int port, boolean ssl, int index, int attempt, List<JsonObject> users,
                Set<String> names, CountDownLatch latchLogin) {
        client.request(HttpMethod.POST, port, url.getHost(), "/api/game/assign/" + index + "?team=" + (index % 2 + 1))
                .ssl(ssl)
                .expect(ResponsePredicate.SC_SUCCESS)
                .send()
                .onComplete(r -> {
                    if (r.failed()) {
                        if (attempt < MAX_ATTEMPTS) {
                            vertx.setTimer(1000, t -> assign(vertx, client, port, ssl, index, attempt + 1, users, names,
                                    latchLogin));
                        } else {
                            System.out.println("Giving up on player " + index + " after " + attempt + " attempts: "
                                    + r.cause());
                            latchLogin.countDown();
                        }
                        return;
                    }
                    try {
                        final JsonObject user = r.result().bodyAsJsonObject();
                        users.add(user);
                        names.add(user.getString("name"));
                        System.out.println("login " + user + " " + index);
                    } catch (Exception e) {
                        System.out.println(r.result().bodyAsString());
                    }
                    latchLogin.countDown();
                });
    }

    /**
     * Opens one player connection per user, waits for the start status on them, then sends each click as a message.
     */
    void loadOverWebSockets(Vertx vertx, List<JsonObject> users, int port, boolean ssl, AtomicBoolean started)
            throws InterruptedException {
        HttpClient httpClient = vertx.createHttpClient(new HttpClientOptions().setTrustAll(true).setVerifyHost(false)
                .setMaxWebSockets(users.size()));
        final List<WebSocket> sockets = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch latchConnect = new CountDownLatch(users.size());
        for (JsonObject user : users) {
            WebSocketConnectOptions options = new WebSocketConnectOptions().setHost(url.getHost()).setPort(port)
                    .setSsl(ssl).setURI("/ws/player/" + user.getInteger("id") + "?team=" + user.getInteger("team"));
            httpClient.webSocket(options).onComplete(r -> {
                if (r.failed()) {
                    r.cause().printStackTrace();
                } else {
                    WebSocket socket = r.result();
                    socket.textMessageHandler(text -> {
                        JsonObject message = new JsonObject(text);
                        if ("status".equals(message.getString("type"))
                                && "start".equals(message.getJsonObject("event").getString("type"))) {
                            started.set(true);
                        }
                    });
                    sockets.add(socket);
                }
                latchConnect.countDown();
            });
        }
        latchConnect.await();
        System.out.println(sockets.size() + " sockets connected, waiting for the game to start");
        while (!started.get()) {
            Thread.sleep(500);
        }
        System.out.println("game started");

        final CountDownLatch latchClick = new CountDownLatch(sockets.size() * clicks);
        for (int i = 0; i < clicks; i++) {
            for (WebSocket socket : sockets) {
                final String click = new JsonObject().put("quantity", R.nextInt(power) + 1).encode();
                socket.writeTextMessage(click).onComplete(r -> {
                    if (r.failed()) {
                        r.cause().printStackTrace();
                    }
                    latchClick.countDown();
                });
            }
            Thread.sleep(interval);
        }
        latchClick.await();
        sockets.forEach(WebSocket::close);
    }
}
