package org.acme;

import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.kafka.Record;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.resteasy.reactive.ResponseHeader;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestStreamElementType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Set;

@Path("/power")
@ApplicationScoped
public class PowerResource {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Channel("power1-in")
    Multi<Power> power1;

    @Channel("power1-out")
    Emitter<Power> power1Emitter;

    @Channel("power2-in")
    Multi<Power> power2;

    @Channel("power2-out")
    Emitter<Power> power2Emitter;

    // For statistics/leader boards to Kafka
    @Channel("user-actions")
    Emitter<Record<String, Integer>> userNameActions;

    // SSE to admin to start the game, ...
    @Channel("admin-stream") Emitter<String> adminStream;
    @Channel("admin-stream") Multi<String> streamOfAdmin;

    @Inject
    UsernameGenerator usernameGenerator;

    @Inject
    InteractiveQueries interactiveQueries;

    @GET
    @Path("/score")
    public Set<UserScore> getScores() {
        return interactiveQueries.getScores();
    }


    @GET
    @Path("/assign")
    public JsonNode assignNameAndTeam() {
        ObjectNode assign = objectMapper.createObjectNode();
        assign
            .put("name", usernameGenerator.getName())
            .put("team", random.nextInt(1) + 1);
        
            return assign;
    }

    @GET
    @Path("stream/{team}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @ResponseHeader(name = "Connection", value = "keep-alive")
    public Multi<Power> stream(@RestPath int team) {
        final Multi<Power> p = switch (team) {
            case 1 -> power1;
            case 2 -> power2;
            default -> throw new IllegalArgumentException("Team not found: " + team);
        };
        return Multi.createBy().merging()
                .streams(
                        p,
                        emitAPeriodicPing()
                );

    }

    private Multi<Power> emitAPeriodicPing() {
        return Multi.createFrom().ticks().every(Duration.ofSeconds(10))
                .onItem().transform(x -> new Power(0, ""));
    }

    @GET
    @Path("stream/notifications")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @ResponseHeader(name = "Connection", value = "keep-alive")
    public Multi<String> notifications() {
        return streamOfAdmin;
    }

    @POST
    @Path("{team}")
    @Consumes(MediaType.APPLICATION_JSON)
    public void generate(Power power, @RestPath int team) {
        final Power p = power == null ? new Power() : power;
        if (p.quantity > 1) {
            throw new IllegalArgumentException("We don't support that much energy for now");
        }
        switch (team) {
            case 1 -> power1Emitter.send(p);
            case 2 -> power2Emitter.send(p);
            default -> throw new IllegalArgumentException("Team not found: " + team);
        }
        
        // Sends action to leader board topic
        userNameActions.send(Record.of(power.nickname, power.quantity));
    }

    @POST
    @Path("start")
    public Response startGame() {
        try {
            this.sendToAdmin(new ServerSideEventDTO("start"));
            return Response.ok().build();
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    void sendToAdmin(ServerSideEventDTO serverSideEventDTO) throws JsonProcessingException {
        String result = OBJECT_MAPPER.writeValueAsString(serverSideEventDTO);
        System.out.println("sending: " + result);
        adminStream.send(result);
    }
}