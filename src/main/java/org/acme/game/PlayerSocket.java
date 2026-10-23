package org.acme.game;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.UserData.TypedKey;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.game.GameResource.GameEvent;
import org.acme.game.GameResource.User;
import org.acme.game.PowerResource.Power;
import org.acme.race.RaceState.PlayerStanding;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.acme.race.RaceResource;
import org.acme.race.RaceState;
import org.acme.race.RaceTopology;

import static org.acme.race.RaceTopology.raceMessage;
import static org.acme.game.Utils.NAMES;
import static org.acme.game.Utils.getNameById;

/**
 * The phone's connection, at {@code /ws/player/{id}?team={team}} once the player has an ID from
 * {@code POST /api/game/assign}. The phone sends its taps in batches and receives the game status, its standing and
 * notifications.
 */
@WebSocket(path = "/ws/player/{id}", endpointId = PlayerSocket.ENDPOINT_ID)
public class PlayerSocket {

    static final String ENDPOINT_ID = "player";
    static final TypedKey<User> USER = new TypedKey<>("user");

    private static final Logger LOG = Logger.getLogger(PlayerSocket.class);

    private final GameResource game;
    private final RaceResource race;
    private final Emitter<Power> powerOut;

    public PlayerSocket(GameResource game, RaceResource race, @Channel("power-out") Emitter<Power> powerOut) {
        this.game = game;
        this.race = race;
        this.powerOut = powerOut;
    }

    @OnOpen
    public Multi<PlayerMessage> open(WebSocketConnection connection) {
        User user = connection.userData().get(USER);
        register(user);
        // Each replica re-registers its own players after a reset, so they show up in the new race without tapping
        Multi<PlayerMessage> status = Multi.createBy().concatenating().streams(
                Multi.createFrom().item(game::status),
                whileOpen(game.updates(), connection).invoke(event -> {
                    if (GameEvent.RESET.equals(event.type())) {
                        register(user);
                    }
                }))
            .map(PlayerMessage.Status::new);
        AtomicReference<PlayerStanding> previous = new AtomicReference<>();
        Multi<PlayerMessage> standing = Multi.createBy().concatenating().streams(
                Multi.createFrom().item(race::state), whileOpen(race.updates(), connection))
            .map(state -> state.standing(user.name()))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .skip().repetitions()
            .onItem().transformToIterable(current -> messages(previous.getAndSet(current), current));
        return Multi.createBy().merging().streams(status, standing);
    }

    /**
     * Taps since the last batch. The phone sends one every 200 ms at most, so the same per-event limit as the REST
     * endpoint applies.
     */
    @OnTextMessage
    @NonBlocking
    public void generate(Generated generated, WebSocketConnection connection) {
        User user = connection.userData().get(USER);
        if (generated.quantity() <= 0 || generated.quantity() > GameConfig.MAX_QUANTITY) {
            LOG.debugf("Ignoring %d power from %s", generated.quantity(), user.name());
            return;
        }
        java.util.Set<String> modes = generated.modes() != null ? new java.util.TreeSet<>(generated.modes()) : java.util.Set.of();
        powerOut.send(raceMessage(new Power(generated.quantity(), user.name(), user.team(), game.currentRaceId(), modes)));
    }

    /**
     * Ends the stream at the first item after the connection closed. The extension doesn't cancel the stream returned
     * by {@code @OnOpen} on close, so without this every closed connection would keep its subscriptions.
     */
    static <T> Multi<T> whileOpen(Multi<T> stream, WebSocketConnection connection) {
        return stream.select().first(_ -> connection.isOpen());
    }

    private void register(User user) {
        powerOut.send(raceMessage(new Power(0, user.name(), user.team(), game.currentRaceId())));
    }

    static List<PlayerMessage> messages(PlayerStanding previous, PlayerStanding current) {
        List<PlayerMessage> messages = new ArrayList<>();
        messages.add(new PlayerMessage.Standing(current));
        if (previous == null || !previous.raceId().equals(current.raceId())
            || current.status() != RaceState.Status.STARTED) {
            return messages;
        }
        if (current.teams() > 1 && previous.teamPosition() > 1 && current.teamPosition() == 1) {
            messages.add(new PlayerMessage.Notification("Your team took the lead!"));
        }
        if (current.teamPlayers() > 1 && previous.rankInTeam() > 1 && current.rankInTeam() == 1) {
            messages.add(new PlayerMessage.Notification("You're your team's top generator!"));
        }
        return messages;
    }

    @RegisterForReflection
    public record Generated(int quantity, List<String> modes) {
        public Generated(int quantity) {
            this(quantity, List.of());
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = PlayerMessage.Status.class, name = "status"),
        @JsonSubTypes.Type(value = PlayerMessage.Standing.class, name = "standing"),
        @JsonSubTypes.Type(value = PlayerMessage.Notification.class, name = "notification")
    })
    public sealed interface PlayerMessage {

        @RegisterForReflection
        record Status(GameEvent event) implements PlayerMessage {
        }

        @RegisterForReflection
        record Standing(PlayerStanding standing) implements PlayerMessage {
        }

        @RegisterForReflection
        record Notification(String text) implements PlayerMessage {
        }
    }

    /**
     * Rejects the upgrade unless the player ID and team are valid, and keeps the player on the connection.
     */
    @ApplicationScoped
    public static class PlayerUpgradeCheck implements HttpUpgradeCheck {

        @Override
        public boolean appliesTo(String endpointId) {
            return ENDPOINT_ID.equals(endpointId);
        }

        @Override
        public Uni<CheckResult> perform(HttpUpgradeContext context) {
            Integer id = parse(context.pathParam("id"));
            Integer team = parse(context.httpRequest().getParam("team"));
            if (id == null || id < 0 || id >= NAMES.size() || team == null || team < 1 || team > GameConfig.TEAMS) {
                return CheckResult.rejectUpgrade(400);
            }
            context.userData().put(USER, new User(id, getNameById(id), team));
            return CheckResult.permitUpgrade();
        }

        private static Integer parse(String value) {
            try {
                return value == null ? null : Integer.valueOf(value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
