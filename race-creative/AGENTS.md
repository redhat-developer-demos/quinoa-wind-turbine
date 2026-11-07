# AGENTS.md -- race-creative

The LLM creative director of the Race Day mesh. race-ops calls it over A2A between heats for a
proposal, and after approval to apply one. See [README.md](README.md) for the contract and the
proposal shapes.

The model's tools are the game's theme tools, reached through the admin MCP server. The read tool
(`list_preset_themes`) is the model's. The two write tools (`suggest_team_names`,
`generate_theme`) change the game before they return, so they are only called from `apply` mode,
after the operator approved a card. Keep that split: never expose a write tool to `CreativeAgent`,
and never call one from the propose path.

## Layout

`creative` holds `CreativeService` (the propose and apply modes), `CreativeProposal` (the three
shapes) and `CreativeProposals` (the deterministic fallback). `theme` holds `ThemeGateway`, the
interface the tests stub, and its MCP implementation. `a2a` holds the card and the executor.
`NoForeignImportsTest` fails the build if this app imports `org.acme` or `org.raceops` classes.

## Running

```bash
./mvnw -f race-creative/pom.xml quarkus:dev   # port 8083
```

It needs the game running for the admin MCP client, and Keycloak when the game has OIDC on. The dev
profile uses the same direct grant as race-ops. Do not hot-reload while the MCP client is connected:
the transport blocks on close during the reload and the dev process dies, the bug documented in
[../race-ops/UPSTREAM-ISSUE.md](../race-ops/UPSTREAM-ISSUE.md). Stop and restart instead.

## A2A shape

One tenant, one agent, with two skills on the card so a client can see which call changes the game.
`a2a.authorization.required=false` is required, for the same reason as race-analyst: without it the
SDK answers every task with "Task not found". Callers must send `A2A-Version: 1.0`.

A request that is not JSON is read as a `propose` brief, because the supervisor in front of this app
may pass a sentence instead of the request object. Keep it that way: prose maps to `propose` only, so
no wording can reach `apply` and call a write tool.

## Testing

```bash
./mvnw -f race-creative/pom.xml test   # 23 tests
```

The suite never talks to a game or a model. `ThemeGateway` is an interface so the propose and apply
rules run against a stub, `CreativeProposalsTest` covers the fallback rotation, `ThemeToolsTest`
covers the MCP tool wrapper, and `CreativeEndpointTest` boots the app and sends real A2A tasks.
`%test.quarkus.http.test-port=0` keeps tests off the dev port.
