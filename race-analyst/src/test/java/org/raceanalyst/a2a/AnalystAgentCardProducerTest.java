package org.raceanalyst.a2a;

import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The card is what race-ops's A2A client reads to find the endpoint, so the URL and the
 * skill id are part of the contract. Built directly, without booting the app: a broken card
 * should fail here, not on stage.
 */
class AnalystAgentCardProducerTest {

    private static AnalystAgentCardProducer producer() {
        AnalystAgentCardProducer producer = new AnalystAgentCardProducer();
        producer.agentName = "Race Analyst";
        producer.agentDescription = "Explains why a team is behind.";
        producer.agentVersion = "1.0.0";
        producer.agentUrl = "http://localhost:8082/";
        return producer;
    }

    @Test
    void theCardAdvertisesOneJsonRpcEndpointAndOneSkill() {
        AgentCard card = producer().agentCard();
        assertEquals("Race Analyst", card.name());
        assertEquals("1.0.0", card.version());
        assertEquals(1, card.supportedInterfaces().size());
        assertEquals("JSONRPC", card.supportedInterfaces().get(0).protocolBinding());
        assertEquals("http://localhost:8082/", card.supportedInterfaces().get(0).url());
        assertEquals(1, card.skills().size());
        assertEquals("diagnose-engagement", card.skills().get(0).id());
        assertFalse(card.capabilities().streaming());
    }

    @Test
    void theSkillSaysTheInputIsASnapshotPair() {
        AgentCard card = producer().agentCard();
        String description = card.skills().get(0).description();
        assertTrue(description.contains("snapshots"), description);
    }
}
