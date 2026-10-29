///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS dev.langchain4j:langchain4j:1.21.0
//DEPS dev.langchain4j:langchain4j-open-ai:1.21.0
//DEPS dev.langchain4j:langchain4j-http-client-jdk:1.21.0
//DEPS dev.langchain4j:langchain4j-mcp:1.21.0-beta31
//DEPS org.slf4j:slf4j-simple:2.0.18

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolProviderResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures what an agent pays to answer one question about the race against two reShapr MCP
 * endpoints: the raw translation of the game's OpenAPI contract (every operation is a tool,
 * full JSON responses) and the reshaped server (one get_race_momentum tool). Same model, same
 * question, so the difference in tool calls and tokens is the context-control effect. Run it
 * during a live race so the answer is real:
 *
 * <pre>
 *   scripts/reshapr-measure.sh
 *   jbang scripts/ReshaprMeasure.java raw=&lt;url&gt; reshaped=&lt;url&gt;
 * </pre>
 *
 * The model comes from the same environment as the game's AI commentary: OPENAI_BASE_URL,
 * OPENAI_API_KEY and OPENAI_MODEL.
 */
public class ReshaprMeasure {

    static final String QUESTION = "Who is winning the race right now, and by how much? "
        + "Answer in one or two sentences.";
    static final String SYSTEM = "You answer questions about a live two-team racing game. "
        + "Use the tools available to you to find out what is happening.";
    static final int MAX_STEPS = 10;

    record Run(String label, String url, int toolsOffered, int toolCalls,
               Integer inputTokens, Integer outputTokens, String answer) {
    }

    public static void main(String[] args) throws Exception {
        String baseUrl = requireEnv("OPENAI_BASE_URL");
        String apiKey = requireEnv("OPENAI_API_KEY");
        String modelName = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
        ChatModel model = OpenAiChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(modelName)
            .timeout(Duration.ofSeconds(60))
            .build();

        List<Run> runs = new ArrayList<>();
        for (String arg : args) {
            int split = arg.indexOf('=');
            String label = split > 0 ? arg.substring(0, split) : arg;
            String url = split > 0 ? arg.substring(split + 1) : arg;
            runs.add(runAgainst(label, url, model));
        }
        if (runs.isEmpty()) {
            throw new IllegalArgumentException("pass one or more label=url arguments");
        }

        System.out.println();
        System.out.println("Question: " + QUESTION);
        System.out.println();
        for (Run run : runs) {
            System.out.printf("%-10s %d tools offered, %d tool calls, %s input + %s output tokens%n",
                run.label(), run.toolsOffered(), run.toolCalls(),
                run.inputTokens(), run.outputTokens());
            System.out.println("           answer: " + run.answer());
            System.out.println();
        }
        if (runs.size() == 2) {
            Run first = runs.get(0);
            Run second = runs.get(1);
            int before = first.inputTokens() + first.outputTokens();
            int after = second.inputTokens() + second.outputTokens();
            if (before > 0 && after > 0) {
                System.out.printf("%s vs %s: %d -> %d tokens (%d%% fewer), %d -> %d tool calls%n",
                    first.label(), second.label(), before, after,
                    Math.round((1 - after / (double) before) * 100),
                    first.toolCalls(), second.toolCalls());
            }
        }
    }

    static Run runAgainst(String label, String url, ChatModel model) {
        try (McpClient client = new dev.langchain4j.mcp.client.DefaultMcpClient.Builder()
            .transport(new StreamableHttpMcpTransport.Builder().url(url).build())
            .clientName("reshapr-measure")
            .build()) {

            ToolProviderResult provided = McpToolProvider.builder()
                .mcpClients(client)
                .build()
                .provideTools(new dev.langchain4j.service.tool.ToolProviderRequest(null, UserMessage.from(QUESTION)));
            List<AiServiceTool> tools = provided.aiServiceTools();

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(SYSTEM));
            messages.add(UserMessage.from(QUESTION));

            int toolCalls = 0;
            int inputTokens = 0;
            int outputTokens = 0;
            for (int step = 0; step < MAX_STEPS; step++) {
                List<ToolSpecification> specifications = tools.stream()
                    .map(AiServiceTool::toolSpecification).toList();
                ChatResponse response = model.chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(specifications)
                    .build());
                AiMessage ai = response.aiMessage();
                messages.add(ai);
                TokenUsage usage = response.tokenUsage();
                if (usage != null) {
                    inputTokens += orZero(usage.inputTokenCount());
                    outputTokens += orZero(usage.outputTokenCount());
                }
                if (!ai.hasToolExecutionRequests()) {
                    return new Run(label, url, tools.size(), toolCalls,
                        inputTokens, outputTokens, ai.text());
                }
                for (var request : ai.toolExecutionRequests()) {
                    toolCalls++;
                    AiServiceTool tool = tools.stream()
                        .filter(t -> t.name().equals(request.name()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("no tool " + request.name()));
                    String result = tool.toolExecutor().execute(request, null);
                    messages.add(ToolExecutionResultMessage.from(request, result));
                }
            }
            throw new IllegalStateException("the model did not finish within " + MAX_STEPS + " steps");
        } catch (Exception e) {
            throw new RuntimeException(label + " (" + url + "): " + e.getMessage(), e);
        }
    }

    static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("set " + name + " (the game's AI commentary uses the same)");
        }
        return value;
    }
}
