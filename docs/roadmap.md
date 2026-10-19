# Roadmap

This plan splits the new features into stages, and each feature in a stage is a separate PR that
ships on its own. The order follows dependencies. Most later features need the race state to live
on the server, so the first PR moves it there.

Before 1.1, the race ran in the dashboard browser. `Dashboard.jsx` added up the power events it
received over SSE, computed each team's distance in `DashboardUtils.js`, detected the finish, and
then posted the `finish` event back to the server, so the server never knew who was winning. An
MCP tool, an AI commentator or a hall of fame can't answer "who's in the lead?" until the server
computes it, which is why feature 1.1 came first.

## Status

Feature 1.1 is merged into `main` with PR #98, together with the configurable dashboard login and
the GitHub Actions image build fix described under [Done outside the plan](#done-outside-the-plan).
Feature 1.2 is merged with PR #99, which also fixes the frontend build after the Vite 8 update on
`main`. The [open follow-ups](#open-follow-ups) list the work that came out of both features.
Feature 1.3 is committed on `main` as `1abf286`; its implementation plan is in
[`docs/plans/1.3-mcp-server.md`](plans/1.3-mcp-server.md). Cluster verification is deferred and
doesn't block the next implementation step, feature 1.4 (race commentary), planned in
[`docs/plans/1.4-race-commentator.md`](plans/1.4-race-commentator.md). Feature 1.4 is implemented
in this PR. Stage 2 and later features haven't started.

Feature 1.5 is planned to make cluster deployment repeatable with either Strimzi on OpenShift or
Confluent Cloud. It includes the deferred cluster verification from 1.3 and 1.4. Its scope is
described under [Deploy the game with Strimzi or Confluent Cloud](#15-deploy-the-game-with-strimzi-or-confluent-cloud).
No deployment changes or cluster tests have been performed as part of writing this item.

## Ground rules

The game has to run without an LLM. Every AI feature sits behind one runtime property,
`game.ai.enabled`, which defaults to `false`. Each AI feature also has a non-AI fallback, so turning
the flag off downgrades the demo instead of breaking it:

| Feature | With `game.ai.enabled=true` | Fallback |
|---|---|---|
| Race commentator | LLM commentary | Template lines driven by race events ("Beach takes the lead") |
| Agent players | LLM picks a pacing strategy | Scripted bots with fixed strategies |
| Track and car themes | Generated from a prompt | Preset themes picked from a list |

Feature 1.4 adds the LangChain4j OpenAI extension, with its model integration and Dev Services off
by default. A plain `./mvnw quarkus:dev` needs no model or API key. A `GET /api/features` endpoint
tells the UI which flags are on. Template commentary remains visible when AI is off.

Use one OpenAI-compatible provider integration. OpenAI is the default endpoint when AI is on,
with the API key from `OPENAI_API_KEY` locally and from a secret on the cluster. The base URL and
model name are configurable, so an Ollama or another local server can use the same integration
when it supports the required Chat Completions API. There is no separate Ollama extension,
profile or model Dev Service. Endpoint, model and AI flag changes take effect on restart; 1.4
must verify that the same packaged build supports them.

## Kafka and deployment targets

Local runs and `GameLoader` use the Kafka Dev Service, as they do today. The game on the cluster
runs against either Confluent Cloud or Strimzi, chosen by config profile:

- The existing `confluent` profile connects to Confluent Cloud with SASL credentials from the
  environment.
- The existing `openshift-cluster` profile connects to a Strimzi cluster at
  `my-cluster-kafka-bootstrap:9092`.

Confluent stays optional. The default build has no Confluent libraries, payloads stay JSON through
Jackson, and switching to Confluent Cloud only changes configuration. Anything that needs a
Confluent dependency, such as Confluent Schema Registry serdes, goes behind a Maven profile.

Both targets need the new topics to exist before the app starts, since Confluent Cloud doesn't
create topics on first use. Each feature that adds a topic also adds a Strimzi `KafkaTopic`
resource under `src/main/kubernetes/` and the matching Confluent CLI commands in
`docs/openshift-install.md`. Kafka Streams also needs `replication.factor=3` for its internal
topics on Confluent Cloud, and an API key with permission to create them.

A third deployment target is the Backstage software template in `WindTurbineInc/software-templates`.
Its pending PR #19 makes `fetch:template` read this repository's `main` branch, so every change
merged here reaches the next generated project without a template release. The template deploys
with its own Helm chart in `manifests/helm/app`, which runs the app with
`QUARKUS_PROFILE=openshift-cluster` and creates its own Strimzi cluster. A feature that adds a
topic therefore also adds it to the chart's `templates/kafka-topics.yaml`. A feature that needs a
new secret or environment variable must either default to something that works without it, or
ship together with a matching chart change.

These rules also apply:

- Each PR keeps `./mvnw quarkus:dev` with Dev Services as the only setup step.
- Each PR adds tests and updates the README.
- Load tests keep using `scripts/GameLoader.java`. Playwright stays at low concurrency, as described
  in `AGENTS.md`.
- The OpenShift profiles keep working with 5 replicas. Several features below depend on this, and
  their sections say how.

## Stage 1: server-side race, push, MCP and commentary

### 1.1 Compute race state with Kafka Streams

This feature adds `quarkus-kafka-streams` and a topology that reads the `power` topic and computes
per-player and per-team totals, distance and finish order on the server. The topology writes
snapshots to a compacted `race-progress` topic, and every replica consumes that topic to keep a
local copy of the current race. Each replica can then answer leaderboard queries and push updates
to clients without calling the other pods. Kafka Streams interactive queries alone wouldn't work
here, because with 5 replicas each pod only holds the state for its own partitions.

Finish detection also moves into the topology. Keying team totals by race ID puts one race in a
single partition, so exactly one Streams task decides the finish and emits the `finish` event. With
5 replicas, this also ensures the server sends `finish` only once.

Changes:

- `PowerResource` stamps each incoming power event with the current race ID, and `GameResource`
  creates a new race ID on `reset`.
- Add a `RaceTopology` producer, a `RaceState` record, and `GET /api/race/state` and
  `GET /api/race/leaderboard`.
- Write the topology so it works for any number of teams, even though the UI still shows two. This
  keeps feature 4.3 a UI change.
- Move the distance formula from `DashboardUtils.js` to Java (power × 100 /
  (tap power × taps needed × players)). The dashboard renders `race-progress` and stops computing
  it.
- Add `KafkaTopic` resources and Confluent CLI commands for `race-progress` (compacted), and set
  the Kafka Streams replication factor in the `confluent` profile.

Tests: topology unit tests with `TopologyTestDriver` that cover totals, distance, finish order,
and a reset in the middle of a race. Add a `@QuarkusTest` against Dev Services Kafka for the REST
endpoints.

Demo moment: kill the dashboard tab mid-race and reopen it. The race continues from the same
position because the browser no longer owns the state.

#### What was built

Status: merged into `main` with PR #98.

`RaceTopology` reads `power` and `game-events` and keeps the race in two state stores, one with
per-player scores keyed by player name and one with the team totals and race status. It publishes
a `RaceState` snapshot to `race-progress` at most every 250 ms (`game.race.snapshot-interval`),
and only when the race changed, because phones send many power events per second. A snapshot also
goes out right after each reset, start and pause. The finish snapshot is followed by one `finish`
event on `game-events`. `RaceResource` keeps the latest snapshot on every replica and serves
`GET /api/race/state`, `GET /api/race/leaderboard?team=N` and a `GET /api/race/stream` SSE stream,
which the dashboard now renders instead of the raw power stream. Race IDs (`race-N`) come from a
new `races` Infinispan counter that `reset` increments. The race rules are typed config in
`GameConfig` (`game.tap-power`, `game.taps-needed-per-player`).

The Strimzi topics are in `src/main/kubernetes/openshift.cluster.yml`, and in
`manifests/helm/app/templates/kafka-topics.yaml` on the template's `wind-turbine-remote` branch. The Confluent CLI commands are in `docs/openshift-install.md`, and the
`confluent` profile has the Kafka Streams SASL settings and `replication.factor=3`.

The implementation differs from the plan above in these places:

- Every power and game event uses the same Kafka key, `race`, and goes to partition 0, instead of
  being keyed by race ID. With a race ID key, a new race could hash to a different partition and
  task than the previous one, and that task wouldn't see the players who were already
  registered. Writing to a fixed partition also keeps the topology correct when `power` and
  `game-events` have different partition counts on an existing cluster. The race ID is still
  stamped on every power event and used to ignore power sent for an old race.
- A reset removes all players instead of keeping them at zero. Phones send an empty power event
  every 3 seconds, so active players register again within a few seconds, and players who left
  no longer slow their team down.
- `POST /api/game/event` rejects `finish` with a 400, since only the topology decides the finish.
- The dashboard no longer sends `reset` when it opens. The demo moment depends on this.
- Kafka Streams runs with its default at-least-once guarantee. A crash right at the finish could
  emit `finish` twice. See the follow-ups.

Tests: `RaceTopologyTest` has eight `TopologyTestDriver` tests covering totals and distance, power
outside the running race, finish order and a single finish event, a reset mid-race, pause, four
teams, snapshots only on change, and ignoring `finish` events from outside. `RaceResourceTest` runs
a reset, start and power events through the REST endpoints against Dev Services Kafka.

The demo moment, a `GameLoader` run against the topology and the Playwright `RaceWebUITest` were
checked later, while building 1.2.

### 1.2 Push to players and the dashboard with WebSockets Next

This feature adds `quarkus-websockets-next` with two endpoints. On `/ws/player`, the phone sends
taps in batches and receives the game status, the team's rank and personal notifications such as
"you're your team's top generator". On `/ws/dashboard`, the dashboard receives `race-progress`
snapshots and game events, and the endpoint requires the `admin` role.

The player controller stops polling `/api/game/status` and stops sending one POST per tap. The REST
endpoints stay available for `GameLoader` and backward compatibility. You can add a `--ws` mode to
`GameLoader` and compare request rate and p99 latency between the two modes in
`http.server.requests`.

Tests: WebSockets Next test client for both endpoints. Update `RaceWebUITest` to cover the new
transport while keeping it at its current low player count.

Demo moment: the same `GameLoader` run over REST and over WebSockets, with the Prometheus numbers
side by side.

#### What was built

Status: merged into `main` with PR #99.

`PlayerSocket` serves `/ws/player/{id}?team={team}`. The phone gets its ID from
`POST /api/game/assign` as before and then opens the socket. An `HttpUpgradeCheck` rejects unknown
IDs and teams with a 400 before the upgrade. The phone sums its taps and sends one
`{"quantity": N}` message every 200 ms at most, and the server applies the same 200 limit per
message as the REST endpoint. The server sends `status` messages with the game events, `standing`
messages with the team's position and the player's rank in the team, and `notification` messages
when the player's team takes the lead or the player becomes the team's top generator. The phone no
longer polls `/api/game/status` or posts to `/api/power`.

`DashboardSocket` serves `/ws/dashboard`. It requires the `admin` role and sends the current race on
connect, then every `race-progress` snapshot and game event. The dashboard renders from it instead
of the `/api/race/stream` SSE endpoint, which stays available like the other REST endpoints.

`GameLoader --ws` opens one player socket per simulated player and sends each click as a message.
`quarkus.websockets-next.server.metrics.enabled` adds the `quarkus_websockets_server_*` metrics for
the comparison with `http_server_requests_seconds`.

The implementation differs from the plan above in these places:

- The phone no longer sends an empty power event every 3 seconds. Opening the socket registers the
  player, and after a reset every replica registers the players connected to it again, so they
  appear in the new race without tapping.
- The dashboard socket also checks the `Origin` header. Browsers send the login cookie with a
  WebSocket handshake from any site, so without the check another page could open the dashboard
  connection with the operator's session.
- WebSockets Next doesn't cancel the stream returned by `@OnOpen` when the connection closes. Each
  per-connection stream ends at its first item after the close, otherwise closed phones kept being
  registered again after every reset.
- `quarkus.websockets-next.server.auto-ping-interval=10s` keeps quiet sockets open, because
  `quarkus.http.idle-timeout` closes idle connections after 30 seconds.

Tests: `PlayerSocketTest` plays a race over two player sockets, checks that oversized batches are
ignored, that invalid teams and IDs are rejected, and that a closed player doesn't come back after a
reset. `DashboardSocketTest` checks that an admin receives the race, and that anonymous and
cross-site connections are rejected. `PlayerStandingTest` covers the rank calculation and when the
notifications fire. `RaceWebUITest` now logs in to the dashboard and checks that the race finishes
with 10 browser players. It hadn't run since the dashboard got a login, because the `e2e` profile
included and excluded the same tag.

Verified in a browser in dev mode: a 20-player `GameLoader --ws` race to the finish, a phone tab's
taps, standing and lead notification, and reloading the dashboard mid-race.

### 1.3 Expose race control as an MCP server

The implementation plan, including the endpoint layout and the Keycloak setup, is in
[`docs/plans/1.3-mcp-server.md`](plans/1.3-mcp-server.md).

This feature adds the Quarkiverse `quarkus-mcp-server-http` extension with these tools:

- `get_race_state` and `get_leaderboard`, which read the local `race-progress` copy from 1.1
- `start_race`, `pause_race` and `reset_race`, which require the `admin` role and send game events
  through the existing emitter
- `get_player(name)` for one player's score and rank

It also exposes the current race as an MCP resource, so clients can subscribe to it. The MCP server
needs no LLM. The client brings the model, so this feature works with `game.ai.enabled=false`.

The `/mcp` path uses OIDC bearer tokens through `quarkus-oidc`, because MCP clients can't use the
dashboard's form login. In dev mode, Keycloak Dev Services starts a realm with an `admin` role and
a `developer` user, so local setup stays at `./mvnw quarkus:dev`. On the cluster, the app points at
a Red Hat build of Keycloak instance. The MCP authorization spec expects the server to publish
OAuth protected resource metadata so clients can find the authorization server. This PR needs to
check that Quarkus OIDC can serve that metadata and that Claude Code and goose complete the login
flow against it.

The dashboard keeps form login in this PR. The read-only tools can stay anonymous, like the
`/api/game/status` endpoint is today. Moving the dashboard to OIDC as well, so the admin uses one
login for both, is a small follow-up PR. That PR can also remove the `game.admin.password-digest`
default from `application.properties`, but only once deployments from the software template get
their own password or an OIDC login, because they still log in with the password behind that
digest.

Tests: the MCP server's test utilities for tool listing and calls, including a check that admin
tools reject anonymous callers and accept a token with the `admin` role.

Demo moment: in Claude Code or goose, ask "start the race and tell me when one team passes 50%".
You can also mention that the repo's own `.mcp.json` points at the Quarkus Agent MCP, so an MCP
server helped build the app and the app now runs one itself.

#### What was built

Status: committed on `main` as `1abf286`. The template chart change is commit `6b6fad5` on the
template's `wind-turbine-remote` branch. Cluster verification is deferred while work moves to 1.4.

`RaceTools` defines two MCP servers. `/mcp` needs no login and has `get_race_state`,
`get_leaderboard`, `get_player` and the resource `race://current`, which sends an update
notification for every race snapshot. `/admin/mcp` has the same tools plus `start_race`,
`pause_race` and `reset_race`, and an HTTP permission requires a bearer token with the `admin`
role. An anonymous request gets a 401 that points to `/.well-known/oauth-protected-resource/admin/mcp`,
and that metadata names the `wind-turbine` realm, so MCP clients can start their login. Keycloak
Dev Services imports the dev realm from `src/main/resources/keycloak/wind-turbine-realm.json`.

The implementation differs from the plan in these places:

- The metadata needs `quarkus.oidc.resource-metadata.resource=admin/mcp`. Without it, the resource
  is the bare host. Dev and test advertise `http`, because the metadata defaults to `https`.
- With OIDC on the classpath, the bearer 401 became the default challenge and the dashboard stopped
  redirecting to the login page. The dashboard paths, `/ws/dashboard`, `/api/game/event` and
  `/api/power/stream` now name the form mechanism explicitly.
- The cluster profiles replace `prod` instead of inheriting from it, so the OIDC defaults are set
  for `prod`, `openshift-cluster`, `openshift-sandbox` and `confluent`. Without that, the app failed
  to start on the cluster with no OIDC server URL.
- Without Keycloak, `/admin/mcp` redirects to the login page instead of answering 401. It's still
  rejected, but MCP clients only report an error.
- Keycloak on the cluster runs with a small PostgreSQL database. The realm is imported once, so
  Keycloak's file database would lose it on a restart.
- Tests log in through the form and get tokens with a password grant, instead of `@TestSecurity`.
- Quinoa's SPA routing answered `/.well-known/*` with `index.html`, so Claude Code's OAuth discovery
  for `/mcp` failed to parse the response. `/.well-known` is now in
  `quarkus.quinoa.ignored-path-prefixes` and answers 404, except for the admin metadata. The default
  tests run without Quinoa, so only a browser or `curl` check against dev mode shows this.

Tests: `RaceToolsTest` checks the anonymous read tools, the 401 with `resource_metadata`, the
metadata itself, a 403 for a user without the admin role, and an admin token that starts and resets
the race. `PlayerStandingTest` covers the `get_player` ranking.

Verified in dev mode with the dashboard open in a browser: a `developer` token from Keycloak called
`start_race` over `/admin/mcp`, and the dashboard showed the race running. The browser login from
Claude Code and goose isn't verified yet and remains a follow-up. Verification of the Keycloak
resources on a cluster is deferred and doesn't block 1.4.

### 1.4 Add a race commentator, with or without an LLM

The implementation plan is in
[`docs/plans/1.4-race-commentator.md`](plans/1.4-race-commentator.md).

The commentator subscribes to `race-progress` and game events and posts a short line when something
notable happens, such as a lead change, a team crossing 25/50/75%, a player surge or the finish. It
checks at most every few seconds and only speaks on these triggers, so it doesn't make an LLM call
for every tap.

A `Commentator` interface has two implementations. The template implementation is always present
and fills in fixed sentences. When `game.ai.enabled=true`, a LangChain4j `@RegisterAiService`
implementation receives the same trigger and a compact race summary, and returns one line. The
LangChain4j implementation needs a timeout and a length guardrail, and it falls back to the
template line if the model is slow or fails. Commentary goes out through the dashboard WebSocket
from 1.2 and appears as a ticker.

This PR adds the OpenAI-compatible provider described in the ground rules, with a configurable
base URL and model name for hosted or local endpoints.

Tests: template commentator unit tests, and a mocked AI service to check the trigger logic and the
fallback on timeout.

Demo moment: run the race with the flag off, turn it on, and run it again.

#### What was built

Status: implemented in this PR. Local verification is recorded in
the [implementation plan](plans/1.4-race-commentator.md#verification-results). Cluster testing is
deferred.

`CommentaryTriggerDetector` detects lead changes, 25/50/75% milestones, player surges and finish
from race snapshots. `CommentaryCoordinator` coalesces routine triggers, gives finish priority,
and bounds work to one model call and one pending trigger. Templates are the default. With
`GAME_AI_ENABLED=true`, a stateless LangChain4j AI service uses the configured OpenAI-compatible
endpoint and falls back to templates on error, timeout or invalid output.

One consumer group generates commentary from partition 0 of `race-progress`, and `race-commentary`
distributes the lines to every replica. Replicas cache recent lines for reconnects, deduplicate
event IDs, and reconcile independently arriving race, game and commentary updates. The dashboard
displays the latest line in a polite live region and cancels all socket streams on close.
`GET /api/features` reports the configured AI flag.

Team names, colors and cars are configured in `application.properties` under `game.teams.1.*`
and `game.teams.2.*`. The UI reads them from `GET /api/config`, and both commentary implementations
use the same names. Finish commentary waits until the winning car completes its movement.

The Kafka Streams race store now preserves a monotonic publication timestamp. A delayed power
record can still determine race time, but its finish snapshot can no longer appear older than
the preceding periodic snapshot and be discarded by commentary consumers.

The topic is configured in Dev Services and the Strimzi manifest, with Confluent CLI instructions
in `docs/openshift-install.md`. Both software-template Helm charts have the matching topic change.
The README documents the hosted default and custom endpoint configuration.

### 1.5 Deploy the game with Strimzi or Confluent Cloud

Status: planned, not implemented. This item covers deploying the game and its dependencies and
testing them on OpenShift after 1.4. Both Kafka options must support the same application image
and JSON messages. Choose one provider per deployment:

- Strimzi runs Kafka on the OpenShift cluster. The deployment provisions the operator, Kafka
  cluster, persistent storage and topics.
- Confluent Cloud provides Kafka externally. The deployment provisions or connects to a Cloud
  cluster, creates the topics and permissions, and supplies the connection credentials to the app.

#### Shared application and dependency deployment

Create one application deployment definition with provider-specific configuration and resources.
Consolidate the duplicated OpenShift settings in `application.properties`, keeping the existing
`openshift-cluster` and `confluent` profiles usable. Replace the unconditional generated
`QUARKUS_PROFILE=openshift-cluster` setting so a Confluent deployment selects the correct runtime
profile. Verify that switching providers needs only deployment configuration, without rebuilding
the image or changing serializers.

Parameterize the namespace, image registry and tag, route host, replica count, resource requests
and limits, storage class and dependency endpoints. Build with Java 25 and use a unique image tag.
Configure startup, readiness and liveness probes, an HTTPS route and graceful shutdown for the
WebSocket connections. Deploy directly first, with GitOps and Pipelines documented as optional
automation rather than prerequisites for running the game.

Add a repeatable Infinispan deployment with persistent storage and explicit counter/state
configuration. Its service name and authentication secret must match the app configuration rather
than depend on manually creating `infinispan-generated-secret`. Allow an existing Infinispan
service to be supplied. Separate deployments must use independent game state, including their
Infinispan counters, even when both Kafka options run side by side.

Create secret references for the dashboard password, Infinispan credentials and one shared
`quarkus.http.auth.session.encryption-key` across all app replicas. Keep these references in the
deployment definition so a redeploy preserves them. Supply team configuration, AI mode and model
settings through deployment configuration, with an optional OpenAI API-key secret. Template
commentary must work without that secret.

Support reusing an existing Keycloak instance or deploying Keycloak with PostgreSQL and persistent
storage when admin MCP access is needed. Split the realm import from instance creation so reusing
Keycloak does not replace an existing installation. Update
`src/main/kubernetes/keycloak/keycloak.yaml` for the operator APIs served by the target cluster
and its namespace scope. The inspected cluster serves `v2alpha1`, while this manifest currently
uses `v2beta1`. Import the `wind-turbine` realm with the admin role, audience mapper, MCP client
and required OAuth callbacks. Configure the external issuer URL and certificate trust from the
app pods, and provide the realm import's password secret in the realm import's namespace.

#### Strimzi on OpenShift

Replace the software template's Kafka 3.4/ZooKeeper manifest with a KRaft deployment supported by
the chosen Strimzi operator release. Include `Kafka` and `KafkaNodePool` resources, three brokers
and a controller quorum, persistent volumes and a topic operator. Document operator installation and namespace scope,
including the Red Hat Streams distribution where appropriate.

Update `src/main/kubernetes/openshift.cluster.yml` and the software-template Helm resources to
create `power`, `game-events`, `race-progress` and `race-commentary` before the game starts. Keep
the race inputs on partition 0 and use one partition for these topics. Set replication to three,
compact `race-progress`, and retain `race-commentary` for one hour. Configure replication for
Kafka Streams internal topics as well. Supply the internal bootstrap endpoint and configure
listener authentication, TLS and secrets consistently for both messaging and Kafka Streams.

#### Confluent Cloud

Keep Strimzi operators and Kafka custom resources out of the Confluent deployment. Provide
repeatable Cloud topic and permission provisioning, using the same four topic names, partition
counts and retention policies as the Strimzi path. The app may use an existing Cloud cluster.
Document cluster selection or creation, region and the network access needed from OpenShift.

Wire `CONFLUENT_BOOTSTRAP_SERVERS`, `CONFLUENT_API_KEY` and `CONFLUENT_API_SECRET` through deployment
configuration and secrets. Apply SASL/TLS settings to both reactive messaging and Kafka Streams.
Keep internal-topic replication at three. Grant access to the application topics, the shared
commentary consumer group, per-replica consumer groups and Kafka Streams internal topics and
groups. Account for the current UUID-based group names when defining permissions. Topic creation
and ACL provisioning must complete before the app rollout.

#### Documentation and software templates

Update `README.md` and `docs/openshift-install.md` with complete prerequisites, build and deploy
steps, secret setup, dependency readiness checks and cleanup for each provider. Explain which
resources are created and which existing services can be reused. Keep credentials out of source
control and distinguish deleting the app from deleting persistent data or Cloud resources.

Add the provider choice to both software-template variants and their Helm charts. Include
Strimzi resources only for that option and Cloud connection secrets only for Confluent. Preserve
the shared Infinispan, Keycloak, application and route configuration in both paths.

#### Cluster verification

Run the same acceptance checks against both providers with AI disabled first, then with OpenAI
when credentials are available. Verify rollout, dependency health, team configuration, dashboard
login, player WebSockets, read-only MCP and authenticated admin MCP access through HTTPS.

Use `GameLoader --ws` for simulated players and a few browser contexts for UI checks. Verify race
progress and finish, commentary timing and team names, pause/resume, reset and reconnect replay.
Scale the app to five replicas and confirm that login works across pods, every dashboard receives
the same commentary IDs, and only one consumer owns commentary generation. Restart the Streams
owner and commentary owner during a race, check state recovery and reassignment, and verify that
stale commentary is not replayed. Cover a new pod joining an already running race, including the
known game-status initialization follow-up. Record failures and required fixes before marking
either deployment path verified.

Demo moment: deploy the same image once with Strimzi and once with Confluent Cloud, then run the
same race checks through each deployment's route.

## Stage 2: agents as players and as developers

### 2.1 Add agent players

Bot players join a team and send power through the same WebSocket or REST path as humans, so the
existing `quantity > 200` check and any rate limits apply to them too. A bot picks a strategy, for
example steady, sprint at the start or save energy for the finish, and runs it with a
`quarkus-scheduler` job.

With `game.ai.enabled=true`, an agent picks and changes its strategy. Its tools return the race
state from the MCP server in 1.3 and send power. Without the flag, bots pick a scripted strategy at
random. An admin adds bots from the dashboard or through an MCP tool (`add_bots(team, count)`).

The agents run inside the Quarkus app as LangChain4j AI services, using the same provider setup as
the commentator. Many bots can then share one model configuration, and the bots stay part of the
demo code.

As a separate stage moment, an external agent joins the race as one named player. It connects to
the game's MCP server, reads the race state and calls a `send_power` tool, which applies the same
limits as the phones. This is a single player driven by hand during the talk, not a way to run many
bots.

IBM Bob (through Bob Shell) is the first choice for this agent, and goose is the fallback. A short
spike before 2.1 decides between them by checking whether each agent can:

- run a prompt without interaction, so it can play from a script
- connect to a remote MCP server over Streamable HTTP
- complete the OIDC login against the server from 1.3, or at least accept a bearer token
- call tools every second or so for the length of a race without stalling

If Bob passes, use it here and in 2.2. If it doesn't, use goose for the player, which also adds
another AAIF project to the demo, and keep Bob for 2.2 only if it passes the checks that 2.2 needs.

Tests: scripted strategies run against a test race and finish in a predictable order. The test
also checks that bots can't exceed the per-request power limit.

### 2.2 Script an agent-driven development segment

This stage is mostly docs and repo configuration. It adds project skills under `.agents/skills/` for
common changes, such as adding a game event type, a WebSocket message or a dashboard panel, and
extends `AGENTS.md` with the new architecture from stage 1. It also adds a scripted talk segment in
`docs/` for a small feature that an agent builds live, with a known-good diff to fall back on if
the live run goes wrong. The agent is IBM Bob if the spike in 2.1 shows it works, and goose
otherwise. Bob reads `AGENTS.md` and the skills in `.agents/skills/`, uses the Quarkus
Agent MCP from `.mcp.json` to run dev mode and tests, and can call the game's own MCP server to
check its change against a running race.

`AGENTS.md` is the only agent instruction file in the repository, and 1.1 already added the race
topology and its endpoints to it. The Quarkus Agent MCP writes a `CLAUDE.md` that only
links to `AGENTS.md` whenever `quarkus_start` runs, and in Claude Code that file hides `AGENTS.md`
instead of loading it. A project hook in `.claude/settings.json` deletes the generated file after
`quarkus_start` and `quarkus_create`, and stops Claude Code from writing a `CLAUDE.md` itself. The
hook is a workaround until the MCP is changed (see the follow-ups).

## Stage 3: race lifecycle as a workflow

### 3.1 Model the race lifecycle with Quarkus Flow

Today the lifecycle is a string `type` on `GameEvent`. Since 1.1, the race topology decides when
to send `finish`, but nothing models the states in between. This feature moves the lifecycle into
a Quarkus Flow (CNCF Serverless Workflow) definition: lobby, countdown, race, podium, reset. The
workflow consumes the events from 1.1 and the start command from the dashboard or MCP. It can also have agentic steps when the flag is on,
such as choosing a mid-race event, which connects to 4.1.

Tests: workflow tests that drive each transition with events, including a pause and a reset
mid-race.

## Stage 4: gameplay and customization

Features 4.1 and 4.2 only depend on 1.1, so they can move ahead of stages 2 and 3 if the Confluent
talk comes first.

### 4.1 Add power-ups and sabotage

A new `race-modifiers` topic carries events such as "gust: team 2 power ×2 for 5 s" or "calm:
team 1 power ×0.5 for 3 s". The Kafka Streams topology joins power events with active modifiers
before aggregating. A scheduler sends random modifiers during a race, an admin can trigger them
from the dashboard or MCP, and with the flag on the workflow in 3.1 or the commentator can choose
them.

Each modifier is a separate event with a clear effect on the stream, so the Confluent segment has
something to inspect live during the game on the cluster. In Confluent Cloud you can show consumer
lag on the power topic, stream lineage from `power` and `race-modifiers` into `race-progress`, and
a Flink SQL query over the modifier topic. Locally, the same topics are visible in the Kafka Dev UI
while `GameLoader` runs. On a Strimzi cluster the game works the same, without the Confluent Cloud
views.

Phones show a notification over the player WebSocket when their team gets a boost.

Tests: topology tests for the join, including modifiers that expire mid-race.

### 4.2 Keep a persistent hall of fame

When a race finishes, the app writes the top players, team results and race time to an Infinispan
cache. The cache is already in the stack and persists across restarts. The hall of fame covers a
whole event, for example a day at a conference booth. Add `GET /api/hall-of-fame`, a dashboard
view between races and an MCP tool. Infinispan is enough for this. If you also want queries such as
"best average per team", Panache with PostgreSQL Dev Services would add a second persistence
example to the demo.

Tests: a finished race appears in the hall of fame, and a reset race does not.

### 4.3 Support more than two teams

The backend from 1.1 already handles any number of teams. Feature 1.4 moved the two team definitions
to `game.teams` config served by `GET /api/config`. This feature extends that endpoint to return
all configured teams, removes the
`destination() > 2` check in favor of the configured count, and updates the UI where it assumes
two teams:

- `toDashboardModel()` in `DashboardUtils.js` builds the team list from `TEAMS_CONFIG`, but still
  returns fixed `team1` and `team2` leaderboards for `Winner.jsx`. Since 1.1, `Dashboard.jsx` and
  `LeftBar.jsx` work from a list of teams.
- `RaceTrack.jsx` has one hardcoded `offset-path` per car.
- `ChooseTeamModal.jsx` has two fixed buttons.

The race track change is the largest. The track is a PNG (`race-track-1.png`) with one hand-drawn
path per lane. This feature replaces it with an SVG track drawn from a centerline path, where each
lane is an offset of that path, so the track supports any number of cars. 4.4 needs the same change.

Tests: unit tests for lane path generation, and a Playwright run with three teams and a few players.

### 4.4 Generate the track and cars from a prompt

An admin enters a prompt on the dashboard, for example "a race across Mars between rovers", and the
app produces a theme: team names and colors, car sprites, track shape and background palette. The
theme is a validated `RaceTheme` record, stored in Infinispan and broadcast as a game event, so all
replicas and connected phones switch together.

With `game.ai.enabled=true`, a LangChain4j AI service returns a `RaceTheme` as structured output.
The app validates it before applying it. The track must be a closed path inside the view box, team
colors must have enough contrast, and names must have a maximum length. If validation fails, the
app tells the admin and keeps the current theme. Without the flag, the admin picks from preset
themes.

Cars can start as a choice from the existing sprites plus a color tint, with LLM-generated SVG cars
or an image model as a later step. Generated images take longer and are harder to validate, so they
should not block this feature.

Tests: theme validation, including invalid paths and low contrast, and a mocked AI service that
returns a valid and an invalid theme.

### 4.5 Optional: add Schema Registry for the Confluent profile

This feature is optional and can ship any time after 1.1. A `confluent` Maven profile adds the
Confluent serdes and registers schemas for `power`, `race-modifiers` and `race-progress`, so the
Confluent segment can show schema evolution, for example by adding a field to power events during
the talk. The default build keeps JSON. The two builds can't share the Kafka serializer config, so
this PR has to keep the serializer settings in the matching config profile, and CI should build
both profiles.

Apicurio Registry is the equivalent for Strimzi, and its Dev Service can run locally. Supporting
both registries means two sets of serdes, so start with Confluent only and add Apicurio if you need
it.

## Done outside the plan

These changes shipped with 1.1 in PR #98, because 1.1 couldn't be tested or built without them.

The dashboard login is now configurable. The embedded properties realm put the username inside a
property key and needed a pre-hashed password, so it couldn't be changed from the environment.
`AdminIdentityProvider` replaces it and checks `game.admin.username` and `game.admin.password`,
which `GAME_ADMIN_USERNAME` and `GAME_ADMIN_PASSWORD` can set. Without a password, it checks the
login against `game.admin.password-digest`, which defaults to the digest that was already in
`application.properties`. Deployments from the software template therefore keep the workshop
password they had. Dev and test mode use `developer` / `developer`. The
`quarkus-elytron-security-properties-file` dependency is gone. `AdminLoginTest` goes through the
real form login, and `AdminConfigTest` checks that the digest format matches what the old realm
accepted.

The GitHub Actions image build works again. `quarkus-openshift` made the OpenShift in-cluster build
the default image builder, and the runner has no cluster, so the workflow's image build failed.
The project now has the podman builder extension as well. `application.properties` keeps
`quarkus.container-image.builder=openshift` so local and cluster deploys behave as before, and the
workflow selects podman with `-Dquarkus.container-image.builder=podman`. The push step also passes
the registry login to Quarkus directly. The pull request build has run locally with podman, but the
push to `ghcr.io` only runs on GitHub and hasn't been tested yet.

PR #99 fixes the frontend build that broke when the Dependabot updates were merged
into `main`. Vite 8 and `@vitejs/plugin-react` 6 need Node 20.19 or later, so Quinoa now installs
Node 22.12.0. `eslint-plugin-react-hooks` 7 conflicted with `eslint-config-airbnb`, which made
`npm install` fail. Nothing ran ESLint, so the branch removes it with its configuration.

The same PR raises `quarkus.http.idle-timeout` to an hour in dev mode only. The Vite live
reload socket that Quinoa proxies has no pings, and when the 30-second timeout closed it, Vite
reloaded every open page and sent the phones back to the team choice.

The dashboard no longer goes blank when the operator resets a finished race. The winner popup stays
on for one render after the reset snapshot clears the winner, and it crashed looking up the team for
winner -1. This bug came with 1.1.

`GameLoader` retries a failed assignment instead of waiting forever. When dev mode reloads the app
on the first request after a code change, the reload closes the open connections, and the script
used to hang because the failed players never counted down. It also no longer sleeps 5 seconds
after the assignments.

## Open follow-ups

- Give each template deployment its own dashboard password. Done for template deployments by the
  1.3 chart change, which generates `game-admin` in every namespace; deployments made with
  `docs/openshift-install.md` still need the manual secret. `docs/openshift-install.md` describes
  the approach: a Vault `RandomSecret` per namespace, the same way the template's build chart
  already creates the webhook secret, and `GAME_ADMIN_PASSWORD` read from it in the chart's
  `deployment.yaml`. After that, the digest default can go.
- Set `quarkus.http.auth.session.encryption-key` from a secret. Without it, each pod encrypts the
  login cookie with its own key, so with 5 replicas a dashboard session only works on the pod
  that created it. This was already the case before 1.1.
- Turn on `processing.guarantee=exactly_once_v2` for the cluster profiles, so a Streams crash at
  the finish can't emit `finish` twice. Dev Services Kafka stays at-least-once.
- Make `GameLoader` use player IDs that real phones don't get. It assigns IDs from 0, and the
  users counter hands out the same IDs to phones, so a phone and a bot can end up as one player.
- Find out why the socket tests hang in the dev-mode test runner (`devui-testing_runTest`). They
  pass under `./mvnw test` in a few seconds.
- The session cookie follow-up above also applies to `/ws/dashboard`. With 5 replicas, the
  dashboard socket only authenticates on the pod that created the session.
- Add goose's OAuth callback to the `mcp-agent` redirect URIs, and check the browser login with
  Claude Code and goose against `/admin/mcp`.
- Deferred: check the Keycloak resources on a cluster with the RHBK operator: the edge route, the password
  placeholder in the realm import, and whether the app trusts the router's certificate when it
  calls Keycloak through the external host.
- New pods read `game-events` from the latest offset, so a pod that starts during a race reports
  the status `empty` until the next game event. Tests read from the start to avoid this.
