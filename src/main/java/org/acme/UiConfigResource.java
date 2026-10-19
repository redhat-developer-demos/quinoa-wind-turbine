package org.acme;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("config")
public class UiConfigResource {
    private final GameConfig config;

    public UiConfigResource(GameConfig config) {
        this.config = config;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public UiConfig configuration() {
        return new UiConfig(List.of(team(1), team(2)));
    }

    private Team team(int id) {
        var team = config.teams().get(id);
        return new Team(id, team.name(), team.color(), team.car());
    }

    public record UiConfig(List<Team> teams) {}
    public record Team(int id, String name, String color, String car) {}
}
