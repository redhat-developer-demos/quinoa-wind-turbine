# Roadmap

This plan splits the new features into stages, and each feature in a stage is a separate PR that
ships on its own. The order follows dependencies. Most later features need the race state to live
on the server, so the first PR moves it there.

The race currently runs in the dashboard browser. `Dashboard.jsx` adds up the power events it
receives over SSE, computes each team's distance in `DashboardUtils.js`, detects the finish, and
then posts the `finish` event back to the server. The server never knows who is winning. An MCP
tool, an AI commentator or a hall of fame can't answer "who's in the lead?" until the server
computes it, so feature 1.1 comes first.

## Ground rules

The game has to run without an LLM. Every AI feature sits behind one runtime property,
`game.ai.enabled`, which defaults to `false`. Each AI feature also has a non-AI fallback, so turning
the flag off downgrades the demo instead of breaking it:

| Feature | With `game.ai.enabled=true` | Fallback |
|---|---|---|
| Race commentator | LLM commentary | Template lines driven by race events ("Beach takes the lead") |
| Agent players | LLM picks a pacing strategy | Scripted bots with fixed strategies |
| Track and car themes | Generated from a prompt | Preset themes picked from a list |

The LangChain4j dependency stays in the build, with its model integration and Dev Services off by
default so that a plain `./mvnw quarkus:dev` doesn't pull an Ollama model. A `GET /api/features`
endpoint tells the UI which flags are on, and the UI hides AI panels when they're off. The first AI
PR (1.4) needs to confirm the exact LangChain4j properties for this: `enable-integration=false` on
the provider and `quarkus.langchain4j.devservices.enabled=false`.

Two model providers are supported. OpenAI with a small, cheap chat model is the default when the
flag is on, and it reads the API key from `OPENAI_API_KEY` locally and from a secret on the
cluster. Ollama with a Granite model is the alternative, activated by an `ollama` config profile
that also turns on the Ollama Dev Service. The model names are config values, so you can pick the
cheapest current OpenAI model and Granite tag when you implement 1.4 instead of fixing them now.
With both provider extensions on the classpath, LangChain4j needs to be told which one to use.
1.4 has to check whether that choice is a build-time property. If it is, switching providers means
building with a different profile, which is fine for dev mode and for the cluster image but rules
out switching on stage without a restart.

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

### 1.3 Expose race control as an MCP server

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
login for both, is a small follow-up PR. It also removes the SHA digest of the `developer` password
from `application.properties`.

Tests: the MCP server's test utilities for tool listing and calls, including a check that admin
tools reject anonymous callers and accept a token with the `admin` role.

Demo moment: in Claude Code or goose, ask "start the race and tell me when one team passes 50%".
You can also mention that the repo's own `.mcp.json` points at the Quarkus Agent MCP, so an MCP
server helped build the app and the app now runs one itself.

### 1.4 Add a race commentator, with or without an LLM

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

This PR sets up both model providers described in the ground rules, with OpenAI as the default and
Granite on Ollama under the `ollama` profile.

Tests: template commentator unit tests, and a mocked AI service to check the trigger logic and the
fallback on timeout.

Demo moment: run the race with the flag off, turn it on, and run it again.

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

## Stage 3: race lifecycle as a workflow

### 3.1 Model the race lifecycle with Quarkus Flow

Today the lifecycle is a string `type` on `GameEvent`, and the dashboard browser decides when to
send `finish`. This feature moves the lifecycle into a Quarkus Flow (CNCF Serverless Workflow)
definition: lobby, countdown, race, podium, reset. The workflow consumes the events from 1.1 and
the start command from the dashboard or MCP. It can also have agentic steps when the flag is on,
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

The backend from 1.1 already handles any number of teams. This feature moves the team definitions
from `Config.js` to a `game.teams` config served by `GET /api/game/config`, removes the
`destination() > 2` check in favor of the configured count, and updates the UI where it assumes
two teams:

- `Dashboard.jsx` keeps one state hook per team (`team1`, `team2`).
- `LeftBar.jsx` builds `teamDef` with two fixed entries.
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
