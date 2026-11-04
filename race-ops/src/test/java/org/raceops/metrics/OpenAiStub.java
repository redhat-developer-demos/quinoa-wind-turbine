package org.raceops.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A local OpenAI-compatible endpoint, so the director's model call runs offline in tests.
 * It answers every chat completion with the JSON the test set, and a fixed token usage —
 * the usage numbers are what make the metrics assertion possible.
 */
public class OpenAiStub implements QuarkusTestResourceLifecycleManager {

    private static final int PROMPT_TOKENS = 120;
    private static final int COMPLETION_TOKENS = 45;

    private static volatile String answer = "{}";

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;

    /** The assistant's message content for the next call. */
    static void answer(String json) {
        answer = json;
    }

    @Override
    public Map<String, String> start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
            return Map.of(
                "quarkus.langchain4j.openai.base-url",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
                "quarkus.langchain4j.openai.api-key", "fake-key",
                "quarkus.langchain4j.openai.chat-model.model-name", "test-model",
                "quarkus.langchain4j.openai.timeout", "3s");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start the local OpenAI stub", e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] body = mapper.writeValueAsBytes(Map.of(
            "id", "chatcmpl-local-test",
            "object", "chat.completion",
            "created", 1,
            "model", "test-model",
            "choices", List.of(Map.of(
                "index", 0,
                "finish_reason", "stop",
                "message", Map.of("role", "assistant", "content", answer))),
            "usage", Map.of(
                "prompt_tokens", PROMPT_TOKENS,
                "completion_tokens", COMPLETION_TOKENS,
                "total_tokens", PROMPT_TOKENS + COMPLETION_TOKENS)));
        try (exchange) {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
