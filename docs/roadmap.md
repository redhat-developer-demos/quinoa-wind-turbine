# Roadmap

The server-side game is built and deployed. The race runs in a Kafka Streams topology, players and
the dashboard get live updates over WebSockets, an MCP server exposes race control behind OIDC, and a
commentator posts lines with an optional LLM. The game deploys to OpenShift against either Strimzi or
Confluent Cloud. Three features remain, and this document scopes them: a persistent leaderboard, a
track drawn from a path instead of a fixed image, and admin-driven theme changes with an optional LLM.

## Already built

- Server-side race state in a Kafka Streams topology (`RaceTopology`), with per-replica race queries
  and SSE/WebSocket push to phones and the dashboard.
- An MCP server (`RaceTools`) with read-only tools on `/mcp` and admin tools on `/admin/mcp`, secured
  with OIDC bearer tokens from Keycloak.
- Race commentary (`CommentaryGenerator`), template lines by default and an optional LangChain4j AI
  service, with one owner per race elected through a Kafka consumer group.
- Secret team boosts (power-ups and sabotage).
- A repeatable OpenShift deployment with Strimzi or Confluent Cloud, Keycloak and Infinispan.

See [AGENTS.md](../AGENTS.md) for the architecture and the git history for how each shipped.

## Ground rules

The game has to run without an LLM. The theme feature below sits behind `game.ai.enabled`, which
defaults to `false`, and has a non-AI fallback, so turning the flag off downgrades the demo instead of
breaking it. Use the one OpenAI-compatible provider the commentator already configures: OpenAI by
default, with the base URL and model name configurable for a local or hosted endpoint.

Each PR keeps `./mvnw quarkus:dev` with Dev Services as the only setup step, adds tests, and updates
the README. The OpenShift profiles keep working with five replicas, so a new writer has to behave with
more than one pod.

## Persistent leaderboard

Status: built on this branch. The dashboard shows it between races, `GET /api/hall-of-fame` serves it,
and `get_hall_of_fame` exposes it over MCP.

When a race finishes, persist its result to Infinispan so a booth can show past races across a whole
event and the list survives restarts. Add `GET /api/hall-of-fame`, a read-only MCP tool, and a
dashboard view shown between races. A finished race appears; a race reset before finishing does not.

Capture the finish from the `race-progress` snapshot, not the finish `GameEvent`. Only the snapshot
carries the race time (`elapsedMs`) and the per-team results; the finish event does not. Detect the
first snapshot with `Status.FINISHED` for a given `raceId`.

With five replicas, every per-replica consumer sees the finish, so guard against five writes two ways.
Elect a single writer with the commentary ownership pattern: a new `HallOfFameWriter` that implements
`KafkaConsumerRebalanceListener` with its own `@Identifier` and a shared consumer group, owning
partition 0 the way `CommentaryGenerator` does. Then key the Infinispan entry by `raceId` and use
`putIfAbsent`, so a brief double-owner during a rebalance can't write twice.

Scope the entry around stable facts. Player names come from the player id (`Utils.getNameById`), and a
phone keeps its id across resets, but a reload or a new visitor gets a new id and a new name, and the
names are generated rather than chosen. So a name does not identify a person across sessions. Persist
the team result (winner, race time, final standings) and the top generator of that race by their race
name as flavor, and don't aggregate per-player stats across races. A durable individual leaderboard
would need player-chosen names, which is a separate feature.

New Java: a `HallOfFameEntry` record, a `HallOfFameStore` that owns a persistent cache and reads and
writes JSON through Jackson, the `HallOfFameWriter` above, a `HallOfFameResource` for
`GET /api/hall-of-fame`, and a `get_hall_of_fame` tool in `RaceTools`. A `HallOfFame.jsx` dashboard
view fetches the endpoint and shows past races when the dashboard is between races. No new
dependencies and no new Kafka topic.

The one real risk is persistence. A persistent counter is not a persistent cache, so the
`hall-of-fame` cache needs a file-store on the Infinispan volume, which is only truly testable on the
cluster because Dev Services runs an ephemeral container. Entries also grow for the length of an
event, so decide on a cap or TTL.

Tests: a race driven to finish appears in `/api/hall-of-fame` with a nonzero race time and the right
winner; a reset race does not appear; the writer persists only on the FINISHED snapshot, only on the
owner, and is idempotent per `raceId`.

## Parametric race track

Status: built, with one change from the original plan. The painted image stays the default because it
looks far better than any procedural vector track; `RaceTrack.jsx` renders either the painted image
with its hand-drawn lane paths or, for an AI-generated theme, an SVG track from `LanePath.js`. A
`ThemeContext` seeded from `/api/config` lets the board re-skin live. Generated tracks will use painted
presets plus a vector fallback, so the vector renderer is the fallback rather than the default.

The dashboard track is the image `race-track-1.png` with two hand-drawn `offset-path` strings in
`RaceTrack.jsx`. Replace it with an SVG track drawn from a single centerline path, where each lane is
a computed offset of that path. The theme feature needs this to render a generated track. The game
stays at two teams, but the lane-offset generator costs the same for any number of lanes, so write it
for N and leave room to add teams later.

Keep the `offset-path` and `offset-distance` percent animation. Finish detection and the Playwright
test read `offsetDistance` directly, so the new renderer computes each lane's path and feeds it to the
same animation primitive rather than switching to another one.

This PR also carries the frontend theme refactor, which the theme feature depends on. Config loads
once into a static module global (`TEAMS_CONFIG`), so nothing can re-skin live. Introduce a React
theme context seeded from `GET /api/config` and updated on a theme game event, then replace the static
`TEAMS_CONFIG` imports in `RaceTrack.jsx`, `LeftBar.jsx`, `Winner.jsx` and the game controller with
reads from the context.

Tests: a unit test for the lane-path generator (a closed path, inside the view box, one path per
lane), and the existing Playwright race kept green against the new renderer.

## AI theme changes

Status: built. The `RaceTheme` model, `ThemeValidator`, `PresetThemes`, `ThemeService`, the admin
`/api/theme` endpoints, the `apply_theme` and `list_preset_themes` MCP tools, and live switching of
team names, colors and cars across every client through a `theme` game event are all in. The
dashboard carries a theme panel for presets and team editing, and the AI path is complete:
`ThemeGeneratorAi` runs on its own named model config and offers both team-name suggestions from an
event or location prompt (applied onto the current colors and track) and full-theme generation,
with every answer validated before it applies. Themes switch only between races. Cars are tinted from
the team color and accent, so a re-skin uses the existing sprites. The active theme lives in memory:
a pod that starts after a theme change shows the configured default until the next theme event.

An admin applies a theme between races that changes team names, colors, car sprites, background
palette and the track shape, and every client switches together. A theme is a validated `RaceTheme`,
stored in Infinispan and broadcast as a `theme` game event over the existing `game-events` topic, so
all replicas and phones switch without a reload. Switching is allowed only between races. With
`game.ai.enabled=true`, a LangChain4j AI service returns a `RaceTheme` as structured output; the app
validates it and keeps the current theme if validation fails. Without the flag, the admin picks a
preset or edits names and colors by hand.

The frequent need is changing team names per event, for example to local food or sports names that a
crowd enjoys without causing tension. So the feature also offers a light path: suggest two team names
from an event or location prompt and apply them onto the current colors and track. That returns a
small structured result and fits the commentary model, unlike generating a whole theme.

Cars are a re-skin, meaning one of the existing sprites plus a color tint, not generated art.

Backend: a `RaceTheme` record (names, colors, car sprite and tint, background palette, track
centerline path), a `ThemeService` holding the active theme in memory and in Infinispan so a
late-joining pod picks it up, a `ThemeValidator` (color contrast, bounded names, allowed sprites, a
closed track path inside the view box), a `PresetThemes` list, an admin `ThemeResource`, admin MCP
tools, and a `ThemeGeneratorAi` service. Full-theme generation is slower and larger than a one-line
comment, so give it a named model configuration (`quarkus.langchain4j.openai.theme.*`) with a bigger
token budget and a longer timeout rather than the commentary model's 256 tokens and short deadline.
Store the theme as JSON in a String cache to avoid a Protostream schema.

This feature depends on the parametric track for anything that changes the track shape. The name,
color, car and palette parts do not.

Tests: theme validation (valid and invalid themes, low contrast, a bad path), a mocked AI service that
returns a valid and an invalid theme, and an endpoint test that applies a preset and sees
`/api/config` reflect it and survive a reset.

## Known issues

- AI commentary with a hosted model isn't verified end to end. It works at the API level
  (`gpt-5.6-luna` on OpenAI answers in about two seconds, which is why `game.commentary.timeout` is
  `5s`), but the running app hasn't been checked to emit `source=ai` lines. A shell that exports
  `OPENAI_BASE_URL`, `OPENAI_API_KEY` or `OPENAI_MODEL` overrides `.env`, because a real environment
  variable outranks `.env` in Quarkus, and a reasoning model such as `rits/zai-org/glm-5-3` needs
  about 2048 tokens and 12 to 36 seconds per line, so it times out into the template. Use a fast
  non-reasoning model and keep those variables out of the shell when you want `.env` to win.
- A new pod reads `game-events` from the latest offset, so a pod that starts mid-race reports status
  `empty` until the next event. The leaderboard reads its state from Infinispan to avoid this, but the
  active theme lives in memory, so a pod that starts after a theme change shows the configured default
  until the next theme event. Apply the same Infinispan approach to game status and the theme if it
  becomes a problem.
- Set `quarkus.http.auth.session.encryption-key` from a secret. Each pod otherwise encrypts the login
  cookie with its own key, so with five replicas a dashboard session only works on the pod that
  created it, including `/ws/dashboard`.
- The socket tests hang in the dev-mode test runner (`devui-testing_runTest`) but pass under
  `./mvnw test`.
