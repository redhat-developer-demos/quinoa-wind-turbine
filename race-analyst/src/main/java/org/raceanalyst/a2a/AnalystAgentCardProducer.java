package org.raceanalyst.a2a;

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
 * The analyst's self-description, served at /.well-known/agent-card.json. It has one skill:
 * the director sends the snapshot pair and gets the engagement report plus the answer to
 * "why is that team behind". Single-tenant on purpose — one app, one agent, so no tenant
 * qualifier and no card routing.
 */
@ApplicationScoped
public class AnalystAgentCardProducer {

    @ConfigProperty(name = "analyst.a2a.agent.name")
    String agentName;

    @ConfigProperty(name = "analyst.a2a.agent.description")
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
                    .id("diagnose-engagement")
                    .name("Engagement diagnosis")
                    .description("Compare two race snapshots and say why a team is behind: tappers, "
                        + "players who stopped tapping, and average power per player.")
                    .tags(List.of("engagement", "analytics", "diagnosis"))
                    .examples(List.of(
                        "{\"base\": <snapshot>, \"current\": <snapshot>}",
                        "Why is team 2 eight points behind?"))
                    .build()))
            .defaultInputModes(List.of("text"))
            .defaultOutputModes(List.of("text"))
            .build();
    }
}
