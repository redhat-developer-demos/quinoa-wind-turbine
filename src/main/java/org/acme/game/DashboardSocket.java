package org.acme.game;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.game.GameResource.GameEvent;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import org.acme.commentary.Commentary;
import org.acme.commentary.CommentaryFeed;
import org.acme.race.RaceResource;
import org.acme.race.RaceState;

/**
 * The dashboard's connection. It receives the current race on connect, then every race snapshot and game event.
 */
@RolesAllowed("admin")
@WebSocket(path = "/ws/dashboard", endpointId = DashboardSocket.ENDPOINT_ID)
public class DashboardSocket {

    static final String ENDPOINT_ID = "dashboard";

    private final GameResource game;
    private final RaceResource race;
    private final CommentaryFeed commentary;
    private static final Object CLOSED = new Object();
    private final Map<String, CompletableFuture<Void>> closing = new ConcurrentHashMap<>();

    public DashboardSocket(GameResource game, RaceResource race, CommentaryFeed commentary) {
        this.game = game;
        this.race = race;
        this.commentary = commentary;
    }

    @OnOpen
    public Multi<DashboardMessage> open(WebSocketConnection connection) {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        closing.put(connection.id(), closed);
        Multi<DashboardMessage> live = Multi.createBy().merging().streams(
            race.updates().map(DashboardMessage.Race::new),
            game.updates().map(DashboardMessage.Game::new),
            commentary.updates().map(DashboardMessage.Comment::new));
        Multi<DashboardMessage> messages = Multi.createBy().concatenating().streams(
            Multi.createFrom().item(() -> new DashboardMessage.Race(race.state())),
            Multi.createFrom().iterable(commentary.latest().stream()
                .<DashboardMessage>map(DashboardMessage.Comment::new).toList()),
            live);
        // WebSockets Next doesn't cancel the returned Multi on close. A quiet commentary stream must end too.
        return untilClosed(messages, closed)
            .onTermination().invoke(() -> closing.remove(connection.id(), closed));
    }

    static Multi<DashboardMessage> untilClosed(Multi<DashboardMessage> messages, CompletableFuture<Void> closed) {
        return Multi.createBy().merging().streams(messages.<Object>map(message -> message),
                Uni.createFrom().completionStage(closed).replaceWith(CLOSED).toMulti())
            .select().first(item -> item != CLOSED)
            .map(item -> (DashboardMessage) item);
    }

    @OnClose
    public void close(WebSocketConnection connection) {
        CompletableFuture<Void> closed = closing.remove(connection.id());
        if (closed != null) {
            closed.complete(null);
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = DashboardMessage.Race.class, name = "race"),
        @JsonSubTypes.Type(value = DashboardMessage.Game.class, name = "game"),
        @JsonSubTypes.Type(value = DashboardMessage.Comment.class, name = "commentary")
    })
    public sealed interface DashboardMessage {

        @RegisterForReflection
        record Race(RaceState race) implements DashboardMessage {
        }

        @RegisterForReflection
        record Game(GameEvent event) implements DashboardMessage {
        }

        @RegisterForReflection
        record Comment(Commentary commentary) implements DashboardMessage {
        }
    }

    /**
     * Browsers send the login cookie with a WebSocket handshake from any site, so only pages served by this app can
     * open the dashboard connection.
     */
    @ApplicationScoped
    public static class SameOriginCheck implements HttpUpgradeCheck {

        @Override
        public boolean appliesTo(String endpointId) {
            return ENDPOINT_ID.equals(endpointId);
        }

        @Override
        public Uni<CheckResult> perform(HttpUpgradeContext context) {
            String origin = context.httpRequest().getHeader("Origin");
            String host = context.httpRequest().getHeader("Host");
            if (origin != null && !sameHost(origin, host)) {
                return CheckResult.rejectUpgrade(403);
            }
            return CheckResult.permitUpgrade();
        }

        static boolean sameHost(String origin, String host) {
            try {
                URI uri = URI.create(origin);
                String originHost = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
                return host != null && host.equalsIgnoreCase(originHost);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    }
}
