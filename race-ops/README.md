# race-ops

The race director application for the Race Day demo (see [../PLAN.md](../PLAN.md)). It runs the
event so the presenter doesn't have to: watch the race, decide when it starts, propose boosts
when a team falls behind, restyle teams between heats.

This is a subproject of `quinoa-wind-turbine` with its own pom, built with the repository's
wrapper. It knows the game only through its MCP server and never imports game classes
(`NoGameImportsTest` fails the build if it ever does).

## What is in this slice

- **MCP client** (`quarkus-langchain4j-mcp`, named client `race`) pointing at the game's
  read-only `/mcp` server. Subscribes to the `race://current` resource; updates arrive as
  `McpResourceUpdatedEvent` CDI events over the streamable transport's subsidiary channel,
  each followed by a `resources/read` of the snapshot.
- **Event filter** (`RaceEventFilter`): the ~4 snapshots per second mid-race reduce to the
  moments worth acting on: lifecycle transitions, 25/50/75% milestones, lead changes.
- **Engagement service** (`EngagementService`): composes the console's report from every
  snapshot and overlays the analyst's answer when it is fresh. Team distance is the average
  power per tapping player, so headcount is fair but quitters are not. The numbers come from
  race-analyst over A2A, with an in-process fallback that only states the gap.
- **Ops surface** (`/api/ops/stream` SSE, `/api/ops/state`, `/ops` page).

## The A2A mesh

The director consults two specialists, each its own Quarkus app so one can be restarted or killed
without touching the others. `race-analyst` (port 8082) computes the engagement report from two
snapshots in plain code. `race-creative` (port 8083) is the LLM agent whose tools are the game's
theme tools.

The analyst is called through `RaceDirectorSupervisor`, a declarative langchain4j supervisor whose
two sub-agents are the analyst and the creative app. Mid-race the analyst always runs first, with
the snapshots as a typed payload, and the model then makes the actual decision: return `done` and
the director boosts the trailing team, or call the creative director once for a look at the next
heat. Nothing in the request pre-writes which specialist to use, so the second hop depends on what
the analyst found. Between heats the same entry point gets a standing order to call the creative
director. The route is read from the agentic scope (which specialist answered), never parsed from
model text. The answer strategy is `LAST`, so the specialist's structured answer reaches the
director untouched; a summary strategy put a second model in the path, which reworded the creative
JSON into prose the app could not parse and cost another five seconds a short race does not have.

Only `supervisorContext` steers that planner. The framework builds the planner's system message
itself and merely appends that context, so an `@SystemMessage` on the supervisor interface is
silently ignored: the first diagnosis-first run offered the model nothing but a recap (seen live,
2026-11-06). The instructions therefore live in `DirectorDecisions.supervisorContext`, one text per
phase, and `InterventionRouteTest` guards the wording the planner depends on.

The payload never goes through the model. The snapshot pair and the theme request travel as typed
arguments (`AnalysisRequest`, `ThemeRequest`), which the planner will not replace with generated
text. Without that it rewrote the snapshot JSON into a sentence and the analyst answered "needs a
current snapshot" (seen live, 2026-11-06); each wrapper's `toString()` is the JSON, because that is
what the A2A client sends.

Two guardrails sit in front of the model's choice. A next-heat look is only accepted when at least
`BoostPolicy.QUITTER_SHARE` (30%) of the trailing team's players stopped tapping; below that the
race still needs the boost, so the card becomes the boost and the analyst's diagnosis is its reason.
And a decision that lands after its race stopped running is dropped instead of being shown, because
the two hops can take longer than the remaining race.

The boost card's reason is the analyst's own diagnosis sentence, with the policy's numbers and a
fixed closing line. The analyst is also called directly for the console's engagement numbers, which
refresh every couple of seconds and are facts rather than decisions. An approved theme executes
directly too, because a change the operator already approved must not wait on another model call.

The game rejects a restyle mid-heat, so a theme approved while the race runs is `QUEUED` with a
"applies after the finish" note and the director applies it on the first snapshot after the race
stops. A theme proposed between heats is a normal card and applies immediately.

The creative agent is also gated structurally, not just by the prompt: `@ActivationCondition` on
`CreativeA2AAgent` reads a `themeWindow` scope value the director writes with the supervised call.
The planner only offers agents whose condition holds, and the invocation path re-checks it, so a
soft-tapping diagnosis cannot spend a hop on a next-heat look. The gate needs the agentic modules
that generalize activation conditions (1.23.0-beta33); those come from `quarkus-langchain4j` 1.15.0
CR2, which imports `langchain4j-bom` 1.23.0 and targets the same Quarkus 3.40.1.1. Bumping the
quarkiverse BOM is the supported way in: overriding only `langchain4j-agentic` to beta33 on CR1 mixed
it with the older core and failed at runtime with `NoClassDefFoundError: ChatMessagesAccess` (seen
live, 2026-11-06). Verified on CR2: mid-race the planner's agent list held only `Race Analyst$0`;
between heats it held both, and the theme card still appeared.

Every mesh call runs on `MeshExecutor`, a managed executor, never on the snapshot observer. A
specialist that hangs cannot stop the director from accepting snapshots. A late analyst answer is
merged against the newest snapshot, and an answer older than 6 s is dropped. The console keeps
status, elapsed time, positions and power from the current snapshot in every case.

`race-ops.mesh-enabled` gates the A2A clients and is off by default, on in dev and on the cluster.
With it off, the clients are never built and race-ops runs alone.

## The director

`RaceDirector` runs the event between races: when both teams have at least the minimum
players and the field has been quiet for the join plateau (defaults: 2 players, 20 s), it
starts the race through the game's admin MCP server. During the race it proposes boosts
for a team that falls far enough behind (default 15% of the track, mid-race only), with
the diagnosis of why; and when a heat finishes it proposes the theme for the next one,
picking from the presets the game offers and avoiding the theme already on. Both kinds of
proposal wait on the console (`/ops.html`) for a human to approve or dismiss, and only
approval executes them through the admin toolset. Approving a theme resets the finished
heat before the game re-skins, so its leaderboard keeps the names that raced and the new
names start on an empty board. The card's reasoning comes from the specialists when
`race-ops.ai-enabled` is on (same hosted endpoint as the game), with deterministic
fallbacks otherwise. The model never gets the last word: boosts are clamped to the
trailing team and sane parameters, themes to the real preset list.

The theme on the track is read from the game (`get_active_theme` over MCP) before every
proposal, never remembered locally. A local copy starts at the configured default and forgets
a theme the operator applied, which is how the director once proposed the KubeCon theme on a
track already wearing it (seen live, 2026-11-06).

The director holds its own OIDC token (`quarkus-oidc-client`, direct grant against the dev
Keycloak; a deployment overrides with a confidential client carrying the `admin` role),
and every power it has is the admin toolset in `AdminRaceClient`: start, pause, reset,
boost, team names, themes. The director is off by default (`race-ops.director-enabled`,
on in dev).

Verified live (when the plateau was 20 s): 12 GameLoader players joined, nobody pressed start, and 20 s after the last
join the director started the race itself; in a second run one team was sabotaged, fell
15% behind, the director proposed boosting it, and approving the card executed
"Applied x2.5 to team 1 for 30s"; and after a finished heat the director proposed "Fire
versus Ice, blazing orange against freezing blue", and approving it re-skinned the field
to Blaze and Frost. The same sequence runs on the OpenShift cluster through the deployed
director (race started on the join plateau, theme proposal approved through the `/ops`
path route, kill beat with the game healthy at 0 race-ops replicas).

With the mesh on, a rehearsal heat at 12 players ran end to end: the director started the race on
the 5 s plateau, the console showed the analyst's diagnosis while the race was running, and about
8 s after the finish an `ai-names` card proposed names for the developer crowd. Approving it applied
"The Spinlocks" through the creative app. In the same rehearsal the analyst answer stayed fresh
through `FINISHED` while elapsed time and positions came from the snapshot.

## The console on the dashboard

The game's dashboard carries the director console: a status line and the approval cards for
boost and theme proposals, rendered by `DirectorConsole.jsx` under the commentary ticker. It
is the only deliberate change to the game (`game.ops.enabled`, off by default, on in dev with
`game.ops.url` pointing at race-ops), together with the OpenAPI schema exposure that
reShapr imports, and it is a pure client of race-ops: when race-ops is
down, its polls fail and the console removes itself — the race continues from the game's own
state and the manual controls. race-ops allows the game's origin cross-origin in dev; a
deployment routes race-ops under the game's own host so the fetch is same-origin.

## Running

One command, from the repository root, starts the whole mesh in this order and waits for each:

```bash
jbang scripts/DevMode.java start
```

`start ops` boots the game and race-ops alone, `start oidc` boots the game alone with Keycloak, and
`stop` tears it down. See [the README](../README.md#run-it-locally). By hand, it is four terminals:

```bash
# terminal 1: the game (dev mode, port 8080)
GAME_OIDC_ENABLED=true ./mvnw quarkus:dev

# terminal 2: the analyst (port 8082)
./mvnw -f race-analyst/pom.xml quarkus:dev

# terminal 3: the creative director (port 8083)
./mvnw -f race-creative/pom.xml quarkus:dev

# terminal 4: race-ops (dev mode, port 8081)
./mvnw -f race-ops/pom.xml quarkus:dev
```

The game must be up before race-ops and race-creative. race-analyst needs nothing else. race-ops
also runs without the two specialists; start it first if you only care about the director.

Then open http://localhost:8081/ops.html and start a race (dashboard Play button, GameLoader, or
an MCP client). The console shows filtered events and the engagement table; a live SSE
indicator turns green when the game connection is up.

The tests never talk to a running game, a model or Keycloak. The watcher and the mesh are off in
`%test`, and the app boots with dummy OIDC values that are never used. `./mvnw -f race-ops/pom.xml
test` runs 93 tests: the filter and the director policies are pure functions, `InterventionRouteTest`
covers the route and the standing order, `RaceDirectorTest` the still-running guard,
`EngagementServiceTest` the async analyst refresh and the late-answer merge, and `ProposalServiceTest`
plus `DirectorMetricsTest` use a stub admin client. Stop the dev instance before running the suite,
because the compile reloads it and a live MCP connection kills the dev process.

## Metrics

The director serves Prometheus metrics on `/metrics`, the same path as the game, and under
`/ops/metrics` on a cluster where the app is mounted under the `/ops` root path.
`quarkus-micrometer` adds the JVM, process, HTTP server and client, Netty and CPU meters
by default; the director adds its own:

| Metric | What it shows |
|---|---|
| `race_ops_proposals_proposed_total{type}` | Cards the director put on the console: `boost`, `theme` for a preset, or `theme-ai` for an AI brief |
| `race_ops_proposals_decided_total{type,outcome}` | How they ended: `executed`, `failed`, `dismissed` (operator said no) or `stale` (the race ended first) |
| `race_ops_proposals_pending` | The card currently waiting for the operator |
| `race_ops_director_healthy` | 1 when the director's last call to the game worked, 0 when the game is down or rejects its token |
| `race_ops_races_started_total` | Races the director started itself on the join plateau |
| `race_ops_mcp_calls_seconds_count{tool,outcome}` | Latency of every call on the game's admin MCP server, per tool and per outcome |
| `race_ops_race_progress{team}` and `race_ops_race_players{team}` | Live progress in percent of the track and headcount per team |
| `race_ops_race_snapshots_total{status}` | Snapshots accepted, split by race status |

The model's token counts come from the langchain4j integration, not from this app:
`gen_ai_client_token_usage_total{gen_ai_token_type="input"}` and `...="output"`, plus
`gen_ai_client_operation_duration_seconds` for the call latency, all tagged with the request and
response model. They appear with the first request to the model rather than at boot, which is how
the integration registers them, not a missing registration. Don't filter on `ai_service_class_name`:
the director's calls arrive with `none` there, so select the series by your deployment's `job`
label instead.

The OIDC token request to Keycloak is not instrumented: only the admin MCP calls and the
model calls carry a timer.

## Cluster deployment

`scripts/deploy-openshift.sh` deploys race-ops next to the game when OIDC is on: one replica
(five would mean five directors racing to start and restyle), everything served under
`quarkus.http.root-path=/ops` because OpenShift routes do not rewrite paths. It also deploys
race-analyst and race-creative first, one replica each and no route, and points the director at
`http://race-analyst:80/` and `http://race-creative:80/` through the cluster profile. Port 80 is
the port Quarkus publishes the generated Service on; the containers keep listening on 8082 and
8083, and 3.40 has no setting to change the Service port, so the cluster URLs use 80 while dev
still uses the container ports. The
creative app gets the same `race-ops` client secret as the director, and its model is enabled only
when the `commentary-openai` secret exists. The path route
carries `/ops` on the game's own host to the race-ops service, so the director page is
`https://<game-host>/ops/ops.html`, its metrics are `https://<game-host>/ops/metrics`, and the
dashboard's console fetch is same-origin. The page uses relative URLs so the same file works at
`/ops.html` in dev and under the prefix in the cluster. The director authenticates with a
confidential `race-ops` Keycloak
client through a client-credentials grant. The script ensures that client, and the `admin`
role on its service account, through Keycloak's Admin API on every run: a realm re-import
adds nothing to an existing realm (Keycloak skips it wholesale), and the realm file cannot
declare the service-account user at all — Keycloak imports users before clients, so a user
named `service-account-race-ops` collides with the one the client creation makes and the
import job fails on the `(realm_id, username)` unique constraint.

Two traps in that wiring, both hit and fixed:

- The grant's enum value is `client`, not `client_credentials`
  (`quarkus.oidc-client.grant.type=client`).
- The secret must reach `quarkus.oidc-client.credentials.secret`, env
  `QUARKUS_OIDC_CLIENT_CREDENTIALS_SECRET`. Mapping an env var to
  `credentials.client-secret` sets the config *group* (its value lives at `.value`), so the
  OIDC client authenticates with no secret at all and Keycloak answers
  `invalid_client_credentials`.

Four more things only showed up on the cluster, all of them in the `openshift-cluster`
profile so dev is untouched:

- **MCP sessions go through the route, not the Service.** The game runs five pods and its
  Service round-robins, so a session opened on one pod is unknown to the next request, and the
  tool call hangs until the client gives up. The route is annotated `balance: source`, which
  keeps a caller on one pod, so both MCP clients in the cluster use `https://<game-host>/mcp`
  and `https://<game-host>/admin/mcp`. The deploy script passes the route to race-creative and
  to race-ops.
- **The MCP protocol version is pinned to 2026-07-28** on both clients
  (`quarkus.langchain4j.mcp.*.protocol-version=2026-07-28`). The game's server
  (quarkus-mcp-server 2.0.4) lists it as its newest supported version and answers it with
  stateless requests: every call carries `_meta` with the protocol version and the client
  capabilities, plus `Mcp-Method` and `Mcp-Name` headers, so there is no session to lose and
  no initialization handshake to agree on. Without the pin the client probes for it on first
  use and waits 30 s for an answer the game never gives, which is most of a decision budget.
- **The subsidiary SSE channel is off on the cluster.** The legacy stateful transport holds a
  silent stream open for resource-update notifications; an OpenShift edge route waits for
  response headers and answers 504 after 30 s, and the client then stops reading snapshots
  altogether. `RaceWatcher`'s 5 s poll keeps the snapshots fresh instead, which is enough for
  a heat. On 2026-07-28 there is no session stream at all, so the setting only matters for the
  legacy path the client falls back to if the server refuses the modern version.
- **The two entry points have different budgets.** A decision that has to land inside a running
  race waits 15 s (`MeshClient.INTERVENTION_TIMEOUT_SECONDS`). A next-heat look waits 40 s
  (`THEME_INTERVENTION_TIMEOUT_SECONDS`), because it pays for the planner, the A2A hop and the
  creative director's own model and MCP calls, which measured over 15 s on the cluster.

One behaviour to remember when driving the demo: the director proposes nothing while a card is
pending. A theme card left over from the previous heat blocks the mid-race boost for the whole
next heat, so decide every card before starting one.

## Known limitation: live reload while connected

A dev-mode live reload of race-ops while the MCP client is connected to the game kills the
dev process (silent exit, maven reports "Dev mode process did not complete successfully").
Reloads while disconnected work fine, and production is unaffected.

Cause: `QuarkusStreamableHttpMcpTransport.close()` in quarkus-langchain4j-mcp 1.15.0.CR1
(identical in 2.0.0.Beta1) blocks on `httpClient.close()` while the subsidiary SSE channel
is an in-flight request, and the extension's shutdown task runs it during the reload.
Verified by isolation: touch a module file with the game down (client never connected) and
the reload survives; with the game up (channel open) the process dies within seconds of the
file change. Until this is fixed upstream, stop and restart race-ops dev mode instead of
hot-reloading it while a race connection is live.

## Stack

Quarkus 3.40.1.1, quarkus-langchain4j 1.15.0.CR1 (same line as the game and the a2a lab),
Java 25. The MCP protocol negotiates to 2026-07-28 against the game's quarkus-mcp-server 2.x,
falling back to the older resource-subscription protocol when needed.
