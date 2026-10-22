package org.acme.commentary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

public class OpenAiStub implements QuarkusTestResourceLifecycleManager {
    static final BlockingQueue<Request> REQUESTS = new LinkedBlockingQueue<>();
    private static final AtomicReference<Response> RESPONSE = new AtomicReference<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;

    static void respond(int status, String text, long delayMs) {
        REQUESTS.clear();
        RESPONSE.set(new Response(status, text, delayMs));
    }

    @Override
    public Map<String, String> start() {
        try {
            respond(200, "Team 1 takes the lead.", 0);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
            return Map.of(
                "quarkus.langchain4j.openai.base-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
                "quarkus.langchain4j.openai.api-key", "fake-key",
                "quarkus.langchain4j.openai.chat-model.model-name", "test-model",
                "quarkus.langchain4j.openai.timeout", "3s");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start local OpenAI stub", e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        Response response = RESPONSE.get();
        Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
            exchange.getRequestHeaders().getFirst("Authorization"),
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), new CompletableFuture<>());
        REQUESTS.add(request);
        try (exchange) {
            if (response.delayMs() > 0) Thread.sleep(response.delayMs());
            Object body = response.status() == 200
                ? Map.of("id", "chatcmpl-local-test", "object", "chat.completion", "created", 1,
                    "model", "test-model", "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                        "message", Map.of("role", "assistant", "content", response.text()))),
                    "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2))
                : Map.of("error", Map.of("message", "Local provider unavailable", "type", "server_error"));
            byte[] bytes = mapper.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            request.completed().complete(null);
        }
    }

    @Override
    public void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
        REQUESTS.clear();
    }

    record Request(String method, String path, String authorization, String body, CompletableFuture<Void> completed) {
    }

    private record Response(int status, String text, long delayMs) {
    }
}
