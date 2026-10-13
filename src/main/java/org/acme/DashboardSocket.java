package org.acme;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.GameResource.GameEvent;

import java.net.URI;

/**
 * The dashboard's connection. It receives the current race on connect, then every race snapshot and game event.
 */
@RolesAllowed("admin")
@WebSocket(path = "/ws/dashboard", endpointId = DashboardSocket.ENDPOINT_ID)
public class DashboardSocket {

    static final String ENDPOINT_ID = "dashboard";

    private final GameResource game;
    private final RaceResource race;

    public DashboardSocket(GameResource game, RaceResource race) {
        this.game = game;
        this.race = race;
    }

    @OnOpen
    public Multi<DashboardMessage> open(WebSocketConnection connection) {
        return Multi.createBy().merging().streams(
            Multi.createBy().concatenating().streams(Multi.createFrom().item(race::state),
                    PlayerSocket.whileOpen(race.updates(), connection))
                .map(DashboardMessage.Race::new),
            PlayerSocket.whileOpen(game.updates(), connection).map(DashboardMessage.Game::new));
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = DashboardMessage.Race.class, name = "race"),
        @JsonSubTypes.Type(value = DashboardMessage.Game.class, name = "game")
    })
    public sealed interface DashboardMessage {

        @RegisterForReflection
        record Race(RaceState race) implements DashboardMessage {
        }

        @RegisterForReflection
        record Game(GameEvent event) implements DashboardMessage {
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
