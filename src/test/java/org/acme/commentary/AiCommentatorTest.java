package org.acme.commentary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.acme.race.RaceState;

import static org.junit.jupiter.api.Assertions.*;

@Tag("optional")
@QuarkusTest
@TestProfile(AiCommentatorTest.Enabled.class)
@QuarkusTestResource(value = OpenAiStub.class, restrictToAnnotatedClass = true)
class AiCommentatorTest {
    @Inject
    AiCommentator ai;

    @Inject
    ObjectMapper mapper;

    @Inject
    TemplateCommentator template;

    public static class Enabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("game.ai.enabled", "true", "game.teams.1.name", "Alpine", "game.teams.2.name", "Coast");
        }
    }

    @Test
    void injectedServiceUsesOpenAiContractAndOnlyRelevantPlayerFacts() throws Exception {
        OpenAiStub.respond(200, "Player Racer surges ahead.", 0);
        CommentaryTrigger trigger = new CommentaryTrigger("surge-test", "ai-contract", "surge", 1,
            1, null, "Racer");
        assertEquals("Player Racer surges ahead.", ai.comment(trigger, snapshot()));
        OpenAiStub.Request request = nextRequest();
        assertEquals("POST", request.method());
        assertEquals("/v1/chat/completions", request.path());
        assertEquals("Bearer fake-key", request.authorization());
        JsonNode body = mapper.readTree(request.body());
        assertEquals("test-model", body.path("model").asText());
        assertEquals(2, body.path("messages").size());
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertTrue(body.path("messages").get(0).path("content").asText().contains("180"));
        assertTrue(body.path("messages").get(0).path("content").asText().contains("one decimal place"));
        JsonNode user = body.path("messages").get(1);
        assertEquals("user", user.path("role").asText());
        String content = user.path("content").asText();
        JsonNode facts = mapper.readTree(content.substring(content.indexOf('{')));
        assertEquals("surge", facts.path("event").path("type").asText());
        assertEquals(1_000, facts.path("elapsedMs").asLong());
        assertEquals(2, facts.path("teams").size());
        assertEquals("Alpine", facts.path("teamNames").path("1").asText());
        assertEquals("Coast", facts.path("teamNames").path("2").asText());
        io.restassured.RestAssured.given().get("/api/config").then().statusCode(200)
            .body("teams[0].name", org.hamcrest.Matchers.is("Alpine"))
            .body("teams[1].name", org.hamcrest.Matchers.is("Coast"))
            .body("teams[0].car", org.hamcrest.Matchers.is("car-orange"));
        assertEquals(1, facts.path("players").size());
        assertEquals("Racer", facts.path("players").get(0).path("name").asText());
        // The facts carry one-decimal distances, never the raw race-math doubles.
        assertEquals(10.2, facts.path("teams").get(0).path("distance").asDouble(), 1e-9);
        assertFalse(content.contains("10.1666"));
        assertFalse(content.contains("Unrelated player"));
        assertNull(OpenAiStub.REQUESTS.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void coordinatorPublishesRealModelOutput() throws Exception {
        OpenAiStub.respond(200, "  Alpine\n takes the lead.  ", 0);
        try (Fixture fixture = new Fixture(true, Duration.ofSeconds(2))) {
            fixture.start();
            Commentary line = fixture.next();
            assertEquals("ai", line.source());
            assertEquals("Alpine takes the lead.", line.text());
            assertEquals("lead", line.trigger());
            assertEquals("ai-contract", line.raceId());
            nextRequest();
        }
    }

    @Test
    void deadlinePublishesFallbackAndIgnoresLateHttpResponse() throws Exception {
        OpenAiStub.respond(200, "A late model line.", 500);
        try (Fixture fixture = new Fixture(true, Duration.ofMillis(120))) {
            fixture.start();
            OpenAiStub.Request request = nextRequest();
            assertTemplate(fixture.next());
            request.completed().get(3, TimeUnit.SECONDS);
            fixture.assertSilent();
            assertNull(OpenAiStub.REQUESTS.poll());
        }
    }

    @Test
    void resetDiscardsAnOutstandingHttpResponse() throws Exception {
        OpenAiStub.respond(200, "A line from the previous race.", 500);
        try (Fixture fixture = new Fixture(true, Duration.ofSeconds(2))) {
            fixture.start();
            OpenAiStub.Request request = nextRequest();
            fixture.coordinator.accept(RaceState.waiting("next-race"));
            request.completed().get(3, TimeUnit.SECONDS);
            fixture.assertSilent();
        }
    }

    @Test
    void disabledCoordinatorMakesNoHttpRequest() throws Exception {
        OpenAiStub.respond(200, "Unused model line.", 0);
        try (Fixture fixture = new Fixture(false, Duration.ofSeconds(2))) {
            fixture.start();
            assertTemplate(fixture.next());
            assertNull(OpenAiStub.REQUESTS.poll(200, TimeUnit.MILLISECONDS));
        }
    }

    private static OpenAiStub.Request nextRequest() throws InterruptedException {
        OpenAiStub.Request request = OpenAiStub.REQUESTS.poll(3, TimeUnit.SECONDS);
        assertNotNull(request, "Expected a request to the local OpenAI endpoint");
        return request;
    }

    private static void assertTemplate(Commentary line) {
        assertEquals("template", line.source());
        assertEquals("Alpine takes the lead!", line.text());
    }

    private static RaceState snapshot() {
        return new RaceState("ai-contract", RaceState.Status.STARTED, 1_000, 1, null,
            List.of(new RaceState.TeamState(1, 1, 1, 150, 10.166666666666666, null),
                new RaceState.TeamState(2, 2, 1, 0, 0, null)),
            List.of(new RaceState.PlayerState("Racer", 1, 150),
                new RaceState.PlayerState("Unrelated player", 2, 0)));
    }

    private final class Fixture implements AutoCloseable {
        final BlockingQueue<Commentary> output = new LinkedBlockingQueue<>();
        final CommentaryCoordinator coordinator;

        Fixture(boolean enabled, Duration timeout) {
            coordinator = new CommentaryCoordinator(new CommentaryTriggerDetector(Duration.ofSeconds(5), 2, 150),
                template, ai, enabled, Duration.ZERO, timeout, 180, output::add);
            coordinator.ownership(true);
            coordinator.accept(RaceState.waiting("ai-contract"));
        }

        void start() {
            coordinator.accept(snapshot());
        }

        Commentary next() throws InterruptedException {
            Commentary line = output.poll(3, TimeUnit.SECONDS);
            assertNotNull(line, "Expected commentary from the coordinator");
            return line;
        }

        void assertSilent() throws InterruptedException {
            assertNull(output.poll(350, TimeUnit.MILLISECONDS), "Unexpected late commentary");
        }

        @Override
        public void close() {
            coordinator.close();
        }
    }
}
