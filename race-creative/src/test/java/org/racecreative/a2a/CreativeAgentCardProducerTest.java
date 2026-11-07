package org.racecreative.a2a;

import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The card is the contract race-ops reads: the URL, and two skills whose descriptions carry
 * the propose/apply distinction.
 */
class CreativeAgentCardProducerTest {

    private static CreativeAgentCardProducer producer() {
        CreativeAgentCardProducer producer = new CreativeAgentCardProducer();
        producer.agentName = "Race Creative Director";
        producer.agentDescription = "Proposes and applies themes.";
        producer.agentVersion = "1.0.0";
        producer.agentUrl = "http://localhost:8083/";
        return producer;
    }

    @Test
    void theCardAdvertisesTwoSkillsOnOneJsonRpcEndpoint() {
        AgentCard card = producer().agentCard();
        assertEquals("http://localhost:8083/", card.supportedInterfaces().get(0).url());
        assertEquals("JSONRPC", card.supportedInterfaces().get(0).protocolBinding());
        assertEquals(2, card.skills().size());
        assertEquals("propose-theme", card.skills().get(0).id());
        assertEquals("apply-approved-theme", card.skills().get(1).id());
        assertFalse(card.capabilities().streaming());
    }

    @Test
    void theApplySkillSaysItChangesTheGame() {
        AgentCard card = producer().agentCard();
        assertEquals(true, card.skills().get(1).description().contains("changes the race"));
    }
}
