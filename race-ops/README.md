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
  moments worth acting on — lifecycle transitions, 25/50/75% milestones, lead changes.
- **Engagement analytics** (`EngagementAnalytics`): per team, who is tapping and who quit.
  Team distance is the average power per tapping player, so headcount is fair but quitters
  are not; this is the director's fairness instrument. Pure code, no model.
- **Ops surface** (`/api/ops/stream` SSE, `/api/ops/state`, `/ops` page).

The director agent's next pieces (boost and theme proposals, approval-gated on the
console) are the remaining slice; see the plan's remaining-work list.

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
names start on an empty board. The advice messages come from the model
when `race-ops.ai-enabled` is on (same hosted endpoint as the game), with deterministic
fallbacks otherwise — and the model never gets the last word: boosts are clamped to the
trailing team and sane parameters, themes to the real preset list. The boost prompt is told
whether the race is in its opening laps, middle or final laps, so the message matches the
stakes instead of sounding the same at every point.

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

```bash
# terminal 1: the game (dev mode, port 8080)
GAME_OIDC_ENABLED=true ./mvnw quarkus:dev

# terminal 2: race-ops (dev mode, port 8081)
./mvnw -f race-ops/pom.xml quarkus:dev
```

Then open http://localhost:8081/ops.html and start a race (dashboard Play button, GameLoader, or
an MCP client). The console shows filtered events and the engagement table; a live SSE
indicator turns green when the game connection is up.

No test in this project talks to a server or a model. The watcher is off in tests
(`%test.race-ops.watcher-enabled=false`); the filter and analytics are pure functions tested
against constructed snapshots.

## Metrics

The director serves Prometheus metrics on `/metrics`, the same path as the game, and under
`/ops/metrics` on a cluster where the app is mounted under the `/ops` root path.
`quarkus-micrometer` adds the JVM, process, HTTP server and client, Netty and CPU meters
by default; the director adds its own:

| Metric | What it shows |
|---|---|
| `race_ops_proposals_proposed_total{type}` | Cards the director put on the console, `boost` or `theme` |
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
`quarkus.http.root-path=/ops` because OpenShift routes do not rewrite paths. A path route
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
