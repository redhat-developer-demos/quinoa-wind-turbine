# race-creative

The creative director for Race Day (see [../PLAN.md](../PLAN.md)). A separate Quarkus app that
race-ops calls over A2A between heats to propose the next look for the race, and after approval to
apply it.

This is the LLM agent in the mesh. Its tools are the game's own theme tools, reached through the
admin MCP server: `list_preset_themes` to read the presets, `suggest_team_names` and
`generate_theme` to change the game. The model only gets the read tool. The two write tools are
called by plain code after the operator approves a card, so an approved change never waits on a
second model decision.

## Propose and apply

The one operation has two modes, and they do different things:

`{"mode":"propose","raceId":"race-1","currentTheme":"classic","event":"a developer conference","audience":"a room of developers"}`
reads the presets and answers with one of three shapes. A `preset` names a theme the game really
offers. An `ai-names` brief keeps colors, cars and track and only renames the teams. An `ai-theme`
brief generates the full look. None of them changes the game.

`{"mode":"apply","kind":"ai-names","brief":"a seafood festival in Lisbon"}` executes an approved
brief through `suggest_team_names` or `generate_theme`. Those tools apply the theme inside the game
before they return, so this mode is only reached from an approved card.

With `race-creative.ai-enabled=false`, or when the model call fails, propose still returns a
deterministic preset pick that avoids the theme already on. The answer keeps the same JSON shape, so
race-ops cannot tell a model proposal from a fallback except by how specific it is.

A plain sentence instead of the JSON is read as a `propose` request, with the sentence as the brief.
An orchestrator handing the task over may ask in words rather than copy the request object, and prose
can never reach `apply`, so accepting it is safe.

## A2A contract

JSON-RPC on `/`, card on `/.well-known/agent-card.json` (port 8083), header `A2A-Version: 1.0` on
every call. The card carries two skills, `propose-theme` and `apply-approved-theme`, to keep the
consequences visible to a client.

A propose answer looks like this:

```json
{"kind": "ai-names", "presetId": null,
 "message": "Keep the current look and give the teams developer-rivalry names.",
 "brief": "Team names for a developer conference wind-turbine race."}
```

An apply answer carries the same shape plus the result line the console shows, for example
`Applied: Tailwind Turbines vs Headwind Hackers.`

## Running

```bash
./mvnw -f race-creative/pom.xml quarkus:dev   # port 8083
```

The app needs the game running for its admin MCP client, and Keycloak when the game has OIDC on. The
dev profile uses the same direct grant as race-ops against the local Keycloak.

Do not hot-reload this app while its MCP client is connected. The langchain4j MCP transport blocks
on close during the reload and the dev process dies, the same bug documented in
[../race-ops/UPSTREAM-ISSUE.md](../race-ops/UPSTREAM-ISSUE.md). Stop and restart dev mode instead.

## Configuration

The MCP client is `quarkus.langchain4j.mcp.race-admin.*`, pointed at `http://localhost:8080/admin/mcp`
in dev and `http://quinoa-wind-turbine:8080/admin/mcp` on the cluster. OIDC is off by default in
tests, which use unreachable dummy values. `race-creative.ai-enabled` gates the model, and the model
itself uses the standard `OPENAI_*` variables, with a 15 s timeout and a 512-token answer cap.

On the cluster, the app uses the same `race-ops` confidential client as the director, whose service
account already holds the admin role. The `commentary-openai` secret supplies the model.

## Tests

```bash
./mvnw -f race-creative/pom.xml test   # 23 tests
```

The tests never talk to a game or a model. `ThemeGateway` is an interface so the proposal and
apply rules run against a stub, `CreativeProposalsTest` covers the fallback rotation, and
`CreativeEndpointTest` boots the app and sends real A2A tasks. `NoForeignImportsTest` keeps the app
from importing game or race-ops classes.

## Cluster

`scripts/deploy-openshift.sh` deploys race-creative after race-analyst and before race-ops: one
replica, no route, reached as `http://race-creative:80/`. The container listens on 8083, but the
Service Quarkus generates publishes port 80, so the advertised card and race-ops's cluster URL
carry 80. The model is enabled only when the
`commentary-openai` secret exists; without it the app still boots and proposes presets.
