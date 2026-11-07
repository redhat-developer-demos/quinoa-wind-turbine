package org.racecreative.a2a;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.a2aproject.sdk.server.PublicAgentCard;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * The creative director's self-description. Two skills, because the two modes have different
 * consequences: proposing is safe, executing an approved brief changes the game. Single-tenant
 * on purpose — one app, one agent.
 */
@ApplicationScoped
public class CreativeAgentCardProducer {

    @ConfigProperty(name = "creative.a2a.agent.name")
    String agentName;

    @ConfigProperty(name = "creative.a2a.agent.description")
    String agentDescription;

    @ConfigProperty(name = "a2a.agent.version")
    String agentVersion;

    @ConfigProperty(name = "a2a.agent.url")
    String agentUrl;

    @Produces
    @Singleton
    @PublicAgentCard
    public AgentCard agentCard() {
        return AgentCard.builder()
            .name(agentName)
            .description(agentDescription)
            .version(agentVersion)
            .supportedInterfaces(List.of(
                new AgentInterface(TransportProtocol.JSONRPC.asString(), agentUrl)))
            .capabilities(AgentCapabilities.builder()
                .streaming(false)
                .pushNotifications(false)
                .build())
            .skills(List.of(
                AgentSkill.builder()
                    .id("propose-theme")
                    .name("Propose the next heat's look")
                    .description("Reads the game's presets and proposes a preset, a team-name brief "
                        + "or a full-theme brief for the next heat. Changes nothing.")
                    .tags(List.of("theme", "proposal", "creative"))
                    .examples(List.of(
                        "{\"mode\":\"propose\",\"currentTheme\":\"classic\",\"event\":\"a seafood festival\"}"))
                    .build(),
                AgentSkill.builder()
                    .id("apply-approved-theme")
                    .name("Apply an approved theme brief")
                    .description("Executes an approved brief through the game's own suggest_team_names "
                        + "or generate_theme tools. This changes the race; only call it after approval.")
                    .tags(List.of("theme", "apply", "admin"))
                    .examples(List.of(
                        "{\"mode\":\"apply\",\"kind\":\"ai-theme\",\"brief\":\"a retro arcade tournament\"}"))
                    .build()))
            .defaultInputModes(List.of("text"))
            .defaultOutputModes(List.of("text"))
            .build();
    }
}
