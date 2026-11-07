# AGENTS.md -- race-ops

The race director for the Race Day demo. It watches the game through the game's MCP server, starts
the race when the field is stable, and proposes boosts and themes for the operator to approve. See
[README.md](README.md) for the architecture and the live verification notes.

This app knows the game only through MCP. `NoGameImportsTest` fails the build if it imports an
`org.acme` class. Keep that boundary when adding a feature: a new game capability arrives as a new
MCP tool, not as a shared class.

## Packages

| Package | Contents |
|---|---|
| `mcp` | The two MCP clients (read-only `race`, admin `race-admin`), the resource watcher and `AdminRaceClient` |
| `watch` | `RaceEventFilter`: turns the ~4 snapshots/s into lifecycle transitions, milestones and lead changes |
| `monitor` | `RaceMonitor`, `CurrentRace`, `OpsBroadcaster`: what the console shows |
| `analytics` | `EngagementService`: in-process report per snapshot, analyst answer overlaid when fresh |
| `director` | `RaceDirector`, `StartPolicy`, `BoostPolicy`, `ProposalService`, `DirectorDecisions`, `DirectorHealth` |
| `mesh` | The A2A clients, the two-sub-agent `RaceDirectorSupervisor`, `MeshClient`, `MeshExecutor` |
| `ops` | `/api/ops` REST and SSE, `/ops.html` |
| `metrics` | `DirectorMetrics` |

## Running

```bash
# full mesh, in this order: game, analyst, creative, race-ops
GAME_OIDC_ENABLED=true ./mvnw quarkus:dev
./mvnw -f race-analyst/pom.xml quarkus:dev
./mvnw -f race-creative/pom.xml quarkus:dev
./mvnw -f race-ops/pom.xml quarkus:dev
```

race-ops alone is valid: the mesh flag defaults to false and every mesh call has an in-process
fallback. With an agent, `quarkus_start` on this directory with port 8081; stop it with
`quarkus_stop`.

Do not hot-reload this app while its MCP client is connected to the game. The dev process dies, and
the cause is documented in [UPSTREAM-ISSUE.md](UPSTREAM-ISSUE.md). Stop and restart instead.

## Flags

`race-ops.director-enabled` gates acting on the game, and is off by default, on in dev and on the
cluster. `race-ops.ai-enabled` gates model calls. `race-ops.mesh-enabled` gates the A2A clients and
is off by default, on in dev and on the cluster. `race-ops.analyst.a2a-url` and
`race-ops.creative.a2a-url` are nested config groups; a flat property fails config validation.

## Testing

```bash
./mvnw -f race-ops/pom.xml test   # 93 tests
```

The suite never needs a game, a model or Keycloak. The watcher and the mesh are off in `%test`, and
the app boots with dummy OIDC values that are never used. `RaceDirectorTest` covers the static
guards (`gameRestarted`, `justFinished`) and the still-running check; `InterventionRouteTest` covers
the route, the route guard and the standing order; `EngagementServiceTest` covers the async analyst
refresh and the late-answer merge; `ProposalServiceTest` and `DirectorMetricsTest` use a stub admin
client.

Stop the dev-mode instance before running `mvn test`, because the compile reloads it and a live MCP
connection kills the process. Never run `mvn clean` while dev mode is running.

## Conventions that matter

Every mesh and MCP call in the proposal path runs on `MeshExecutor`, not on the snapshot observer. A
specialist that hangs must not stop the director from accepting snapshots.

The model never gets the last word. `DirectorDecisions` forces the trailing team, clamps the
multiplier and duration, and accepts only presets the game actually offers. Keep new model output on
the same leash.

Proposals wait for the operator. Only the start rule acts alone, because a start is reversible and a
boost changes the outcome. A card left open across a race change is rejected as stale.

Every direct decision goes through `RaceDirectorSupervisor`, one supervisor with both specialists as
sub-agents. Mid-race the analyst runs first and the model decides from its diagnosis whether the
director boosts (return `done`) or the creative director looks at the next heat; nothing in the
request pre-writes that choice. Between heats the same entry point is told to call the creative
director. It returns the specialist's answer unchanged (`SupervisorResponseStrategy.LAST`): the boost
card's reason is the analyst's diagnosis sentence, and the creative JSON reaches the guardrails
untouched. Do not put a summarizing model back in that path; it reworded the creative proposal into
prose the app could not parse.

Steering the planner is done through `supervisorContext` only. The framework builds the planner's
system message itself and appends that context, so an `@SystemMessage` on the supervisor interface is
silently ignored (checked in the source and seen live, 2026-11-06). Put new instructions in
`DirectorDecisions.supervisorContext` per phase, and update `InterventionRouteTest` when the wording
changes.

The route is read from the agentic scope (`intervention.diagnosed()` / `themed()`), never parsed from
model text. Two guardrails follow it: a next-heat look needs `BoostPolicy.QUITTER_SHARE` of the team
stopped tapping, and a decision whose race stopped running is dropped. A theme approved mid-heat is
`QUEUED` and applied after the finish, because the game rejects a restyle mid-race.

The creative agent is gated structurally too: `@ActivationCondition` reads the `themeWindow` value
the director passes with the supervised call, and the planner only offers agents whose condition
holds, so a soft-tapping diagnosis cannot spend a hop on a next-heat look. Do not reach that by
overriding `langchain4j-agentic` alone: beta33 on the older CR1 core failed at runtime with
`NoClassDefFoundError: dev/langchain4j/agentic/agent/ChatMessagesAccess` (seen live, 2026-11-06).
Bump `quarkus-langchain4j` to 1.15.0.CR2 instead, which pairs Quarkus 3.40.1.1 with
`langchain4j-bom` 1.23.0 and beta33. Verified on CR2: mid-race the planner's agent list held only
`Race Analyst$0`; between heats it held both, and the theme card still appeared.

The payload is a typed argument (`AnalysisRequest`, `ThemeRequest`), not part of the request text.
The planner model rewrites a JSON payload into prose; the framework refuses to overwrite a typed
scope value with generated text, which is what keeps the analyst's snapshots intact. Pass a new
payload through one of those wrappers, and keep its `toString()` equal to the JSON: that is the A2A
message body.

The analyst is also called directly for the console's engagement numbers, and an approved theme is
executed directly, because a change the operator already approved must not wait on a model.

The theme on the track is read from the game (`get_active_theme`) before every proposal. The local
`currentTheme` is only a fallback: it resets when this app restarts, which is how the director once
proposed the theme already on the track.
