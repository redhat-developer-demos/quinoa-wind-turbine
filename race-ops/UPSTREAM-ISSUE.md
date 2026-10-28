# Upstream issue draft: quarkiverse/quarkus-langchain4j

Draft for an issue to file at https://github.com/quarkiverse/quarkus-langchain4j/issues.
Edit freely before creating; the isolation matrix and the code pointer are the parts worth
keeping intact. Found 2026-10-28 while building the race-ops director slice; tracked in
PLAN.md under risks.

---

**Title:** Dev mode dies on live reload when an MCP client with the subsidiary channel is connected

### Description

A dev-mode live reload of an application that uses `quarkus-langchain4j-mcp` kills the dev
process when the MCP client is connected to a server with `subsidiary-channel=true`. The
process exits silently — no stack trace, no "Live reload triggered" log — and maven reports:

    Failed to execute goal io.quarkus:quarkus-maven-plugin:...:dev (default-cli):
    Failed to run: Dev mode process did not complete successfully

Reloads while the client is *not* connected work fine, so the trigger is specifically the
open subsidiary SSE channel at shutdown time.

### Environment

- quarkus-langchain4j-mcp 1.15.0.CR1 (the `close()` at fault is identical in 2.0.0.Beta1)
- Quarkus platform 3.40.1.1, Java 25, macOS
- Server side: quarkus-mcp-server-http 2.0.3, MCP protocol 2026-07-28 negotiated
- Client app in dev mode on port 8081, server on 8080

### Reproduction

Client config:

```properties
quarkus.langchain4j.mcp.race.transport-type=streamable-http
quarkus.langchain4j.mcp.race.url=http://localhost:8080/mcp
quarkus.langchain4j.mcp.race.subsidiary-channel=true
```

The app subscribes to a resource (`race.subscribeToResources(List.of("race://current"))`)
at startup and observes `McpResourceUpdatedEvent`, so notifications flow over the
subsidiary channel.

1. Start the MCP server app (any app exposing a resource with updates).
2. Start the client app in dev mode, wait until it has subscribed and is receiving
   `notifications/resources/updated`.
3. Touch any file in the client module (even `README.md`).

Actual: within a few seconds the dev process exits with the message above and nothing in
the log between the last application line and the exit.

### Isolation matrix

| Client state when a module file changes | Result |
|---|---|
| Connected, subsidiary SSE channel open | Dev process exits silently |
| Never connected (server down, subscription retrying) | Live reload completes, app keeps serving |
| Connected, no file change | App runs indefinitely |

Disconnecting SSE consumers of the client app's own endpoints has no effect, so the app's
own SSE handling is not involved.

A clean SIGTERM stop while the client is connected was not separately verified; only the
live-reload path was isolated.

### Suspected cause

`QuarkusStreamableHttpMcpTransport.close()` (1.15.0.CR1 and 2.0.0.Beta1):

```java
@Override
public void close() throws IOException {
    closed.set(true);
    try {
        httpClient.close().toCompletionStage().toCompletableFuture().get();
    } catch (InterruptedException | ExecutionException e) {
        throw new RuntimeException(e);
    }
}
```

The subsidiary channel is a long-lived HTTP GET that never completes on its own. `close()`
blocks on `httpClient.close()` while that request is in flight, and `McpRecorder` registers
`client::close` as a `CuratedApplicationShutdownBuildItem` task, which runs during the
live-reload restart of the old application instance. The exit mechanism itself (hang
followed by a dev-mode force-exit vs. a thrown exception) was not captured; the isolation
matrix above is what was verified.

Possible directions: close the in-flight subsidiary GET (track the response and close the
connection) before awaiting `httpClient.close()`, or bound the wait.

### Workaround

Restart the client's dev mode instead of live-reloading it while the MCP connection is
live, or run with `subsidiary-channel=false` (at the cost of server-initiated
notifications). Production is unaffected — only the dev-mode reload path is involved.
