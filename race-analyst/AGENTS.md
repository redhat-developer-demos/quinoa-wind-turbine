# AGENTS.md -- race-analyst

The engagement analyst of the Race Day mesh. race-ops sends two race snapshots over A2A and gets
back the per-team engagement report plus one sentence saying why the trailing team is behind. See
[README.md](README.md) for the contract and the diagnosis rules.

This app has no model. The numbers and the diagnosis are plain code, and that is deliberate: the
director's own model only phrases the answer. Do not add an LLM call here. If a new question needs a
model, it belongs in race-ops or race-creative.

## Layout

`analytics` holds `EngagementAnalytics`, `EngagementReport`, `Diagnosis` and `AnalysisService`.
`a2a` holds the agent card and the executor. `model` mirrors the game's snapshot JSON and must stay
a mirror, not an import. `NoForeignImportsTest` fails the build if this app imports `org.acme` or
`org.raceops` classes.

## Running

```bash
./mvnw -f race-analyst/pom.xml quarkus:dev   # port 8082
```

The app needs no game, broker or Keycloak. It serves JSON-RPC on `/` and the card on
`/.well-known/agent-card.json`. Callers must send `A2A-Version: 1.0`; without it the SDK treats the
call as the older protocol and rejects the task.

## A2A shape

One tenant, one agent: a single `@PublicAgentCard` `AgentCard` bean and one `AgentExecutor` bean.
`a2a.authorization.required=false` is required. The SDK fails closed on every operation without a
`TaskAuthorizationProvider`, and this app has none because it holds no state and only race-ops
reaches it. Removing the flag makes every call answer "Task not found".

Keep `AnalysisService` tolerant of a wrapped request: it reads the first `{` to the last `}`, because
the supervisor in front of the caller may add a sentence to the payload. A bare JSON object must keep
working unchanged and still has a test.

## Testing

```bash
./mvnw -f race-analyst/pom.xml test   # 15 tests
```

`AnalystEndpointTest` boots the app on a random port and sends a real `SendMessage` task, which
catches missing executor beans and route mistakes that unit tests cannot see. The analytics and
diagnosis rules are pure functions with a test for each branch. Keep `%test.quarkus.http.test-port=0`
so tests never collide with a running dev instance.
