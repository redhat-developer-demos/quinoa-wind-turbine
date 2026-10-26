# AGENTS.md -- Quinoa WindTurbine Racer Project Guide

This guide contains project context, architecture, operational workflows, and testing rules for AI agents and developers working on `quinoa-wind-turbine`.

---

## 1. Project Overview

**Quinoa WindTurbine Racer** is a real-time multiplayer racing game demo combining:
* **Backend:** Quarkus (Java 25) with Reactive Messaging (Kafka), Infinispan (Clustered counters/state), REST endpoints, WebSockets Next for the phones and the dashboard, Server-Sent Events (SSE), and an MCP server (Quarkiverse `quarkus-mcp-server-http`) secured with OIDC bearer tokens from Keycloak.
* **Frontend:** React + Vite integrated seamlessly via the **Quarkus Quinoa** extension (`src/main/webui/`).
* **Simulation & Testing:** JBang load simulation (`scripts/GameLoader.java`) and Playwright E2E browser tests (`src/test/java/org/acme/RaceWebUITest.java`).

---

## 2. Architecture & Key Components

```mermaid
flowchart LR
    A[Player UI: /] -->|POST /api/game/assign| B[GameResource]
    A <-->|WS /ws/player/id?team=N| W[PlayerSocket]
    D[Dashboard UI: /dashboard] -->|POST /api/game/event| B
    D <-->|WS /ws/dashboard| S[DashboardSocket]
    G[GameLoader] -->|POST /api/power, or the player WS with --ws| C[PowerResource]
    M[MCP client] -->|/mcp, or /admin/mcp with a bearer token| X[RaceTools]
    X -->|read the race| R
    X -->|start, pause, reset| B

    B -->|Kafka topic: game-events| T[RaceTopology - Kafka Streams]
    C -->|Kafka topic: power| T
    W -->|Kafka topic: power| T
    R[RaceResource] -->|race snapshots| W
    R -->|race snapshots| S
    B -->|game events| W
    B -->|game events| S
    T -->|Kafka topic: race-progress| R
    T -->|finish event on game-events| B
    B -->|User and race ID counters| E[(Infinispan Cache/Counter)]
```

### Package layout
Code lives in feature packages under `src/main/java/org/acme/`, and tests mirror them under `src/test/java/org/acme/`.

| Package | Contents |
|---|---|
| `game` | Game lifecycle and player entry: `GameResource`, `GameConfig`, `PowerResource`, `PlayerSocket`, `DashboardSocket`, `CheatResource` |
| `race` | Race state and computation: `RaceTopology`, `RaceState`, `RaceResource`, `RaceModifier` |
| `commentary` | Live commentary: coordinator, trigger detection, feed, template and AI commentators |
| `theme` | Re-skinning: `ThemeService`, `ThemeValidator`, `PresetThemes`, `ThemeResource`, `UiConfigResource` |
| `halloffame` | Persisting and serving finished races |
| `admin` | MCP tools (`RaceTools`) and the admin login and token policies |

### Key Source Files & Endpoints
* **Player Assignment & Game State:** [`GameResource.java`](src/main/java/org/acme/game/GameResource.java)
  * `POST /api/game/assign` & `POST /api/game/assign/{id}?team={team}`: Registers players and increments Infinispan counter.
  * `GET /api/game/status`: Returns current game state (`empty`, `start`, `stop`, `reset`).
  * `GET /api/game/events`: SSE stream of game lifecycle events.
  * `POST /api/game/event`: Protected admin endpoint (role: `admin`) to broadcast game events.
* **Power & Racing Generation:** [`PowerResource.java`](src/main/java/org/acme/game/PowerResource.java)
  * `POST /api/power`: Receives player click/motion events.
* **Race computation:** [`RaceTopology.java`](src/main/java/org/acme/race/RaceTopology.java) is a Kafka Streams topology that computes per-player and per-team power, distance and finish order, publishes snapshots to the compacted `race-progress` topic and emits the `finish` game event. All race messages use key `race` on partition 0 so one Streams task owns the race. Rules come from `game.*` config ([`GameConfig.java`](src/main/java/org/acme/game/GameConfig.java)).
* **Race queries:** [`RaceResource.java`](src/main/java/org/acme/race/RaceResource.java) keeps a local copy of `race-progress` on every replica.
  * `GET /api/race/state`, `GET /api/race/leaderboard?team={team}`, `GET /api/race/stream` (SSE).
* **WebSockets:** [`PlayerSocket.java`](src/main/java/org/acme/game/PlayerSocket.java) and [`DashboardSocket.java`](src/main/java/org/acme/game/DashboardSocket.java) (WebSockets Next).
  * `/ws/player/{id}?team={team}`: the phone sends `{"quantity": N}` with the taps of the last 200 ms. The server sends `status` (game event), `standing` (team position and rank in team) and `notification` messages. An `HttpUpgradeCheck` rejects unknown IDs and teams with 400. Opening the connection registers the player, and each replica registers its connected players again after a reset.
  * `/ws/dashboard`: requires the `admin` role and a same-origin `Origin` header. Sends `race` snapshots and `game` events.
  * WebSockets Next doesn't cancel the `Multi` returned by `@OnOpen` when the connection closes, so every per-connection stream goes through `PlayerSocket.whileOpen`.
* **MCP server:** [`RaceTools.java`](src/main/java/org/acme/admin/RaceTools.java) defines tools on two servers.
  * `/mcp` (default server, no login): `get_race_state`, `get_leaderboard`, `get_player`, `get_hall_of_fame`, and the resource `race://current`, which notifies subscribers on every race update.
  * `/admin/mcp` (server `admin`): the same, plus `start_race`, `pause_race`, `reset_race`, `boost_team`, `reset_hall_of_fame`, `list_preset_themes`, `apply_theme`, and, when AI is enabled, `suggest_team_names` and `generate_theme`. An HTTP permission requires a bearer token with the `admin` role. It isn't `@RolesAllowed` on the tools, because MCP clients only start their OAuth login on a 401.
  * Tokens come from the Keycloak realm `wind-turbine` (`src/main/resources/keycloak/wind-turbine-realm.json`, imported by Keycloak Dev Services). Users `developer` (admin) and `player`; clients `wind-turbine` (the app) and `mcp-agent` (public, PKCE, redirect `http://localhost:33418/callback`, direct grants in dev only). The app requires the audience `wind-turbine`. Dev Services uses the container name `wind-turbine`, so it creates its own container instead of attaching to another project's Keycloak when container reuse is enabled; the realm in a shared container doesn't match the file.
  * Protected resource metadata: `/.well-known/oauth-protected-resource/admin/mcp`. OIDC is off by default in every mode. Enable admin MCP with `GAME_OIDC_ENABLED=true` or the same setting as a system property, `-Dquarkus.oidc.tenant-enabled=true`; dev mode then starts Keycloak Dev Services, while deployments also need `GAME_OIDC_URL`. The dashboard's form login and read-only MCP work without Keycloak. Token tests enable OIDC through their own test profile.
  * On a cluster, a Quarkus redeploy replaces the deployment and drops variables set with `oc set env`, so re-apply `GAME_OIDC_ENABLED`/`GAME_OIDC_URL` after each redeploy. For an MCP agent that registers a client dynamically (Bob), the `wind-turbine` realm must carry the client-registration policies and `wind-turbine-audience` default scope from [`realm-import.yaml`](src/main/kubernetes/keycloak/realm-import.yaml) and no "Trusted Hosts" policy; a realm imported before those keeps Keycloak's strict default and rejects registration, and re-import is additive, so recreate it fresh. See the `wind-turbine-mcp-demo` skill for driving a cluster deployment.
  * Locally, a dev realm can keep Keycloak's default "Trusted Hosts" client-registration policy, which the repo realm file doesn't declare, and then Bob's registration fails with `Policy 'Trusted Hosts' rejected request`. Keycloak refuses that policy with both checks off, so the file can't disable it; run [`scripts/open-dev-registration.sh`](scripts/open-dev-registration.sh) to delete it from the running realm.
  * The form-login paths (`/dashboard`, `/ws/dashboard`, `/api/game/event`) are pinned to the form mechanism, so anonymous requests still redirect to `/login`. Because of that, `@TestSecurity` doesn't reach them; tests log in with `AdminSession.login()`.
* **Hall of fame:** `HallOfFameWriter` persists each finished race to an Infinispan cache (one writer across replicas, elected over partition 0 of `race-progress` like the commentator), `HallOfFameStore` reads and writes it as JSON and clears it for the admin `reset_hall_of_fame` tool, and `HallOfFameResource` serves `GET /api/hall-of-fame`. The dashboard shows it between races.
* **Themes:** `ThemeService` holds the active theme and applies a new one between races (enforced: mid-race applies are rejected) by broadcasting a `theme` game event, so every replica and client switches together. `RaceTheme`/`ThemeValidator`/`PresetThemes` define and check the re-skin (team names, colors, cars); `ThemeResource` is the admin `POST /api/theme` plus presets, and, with `game.ai.enabled`, `POST /api/theme/suggest-names` and `/api/theme/generate` backed by `ThemeGeneratorAi` on its own named model config (`quarkus.langchain4j.openai.theme.*`); `UiConfigResource` serves the active theme and the AI flag. On the frontend, `ThemeContext.jsx` holds the live theme, `ThemePanel.jsx` is the operator's between-races control, and `RaceTrack.jsx` draws either the painted default image (with hand-drawn lane paths) or a generated SVG track from `LanePath.js`.
* **Frontend Single Page App:** [`src/main/webui/`](src/main/webui/)
  * `src/main/webui/src/components/Dashboard/`: Main operator race screen with live track, leaderboard, and play/reset controls. It renders the server's race state and doesn't compute the race itself.
  * `src/main/webui/src/components/GameController/`: Mobile player controller with team selection modal, wind turbine SVG, and tap/shake/swipe listeners. It talks to the server over the player WebSocket (`src/main/webui/src/api/PlayerSocket.js`).

---

## 3. Essential Commands & Development Workflow

### Starting Dev Mode

OIDC is off unless something switches it on, and both ways of switching it on set the same property. Keep it on for normal work so Keycloak Dev Services spin up and the admin MCP server (`/admin/mcp`) is available. A plain start skips Keycloak and disables the admin tools — never do this unless explicitly asked for a minimal/no-auth start.

* **For AI Agents:** `quarkus_start` takes no environment variables, but its `extraArgs` parameter reaches the JVM and Quarkus reads system properties, so start with `extraArgs: "-Dquarkus.oidc.tenant-enabled=true"`. The server keeps the argument with the instance, so `quarkus_restart` and dev-mode reloads stay authenticated too. Check status with `quarkus_status`, read logs with `quarkus_logs`, stop with `quarkus_stop`.
* **For Developers (CLI):** Run `GAME_OIDC_ENABLED=true ./mvnw quarkus:dev`, or `./mvnw quarkus:dev -Dquarkus.oidc.tenant-enabled=true`.
* `extraArgs` is split on whitespace, so a value containing a space can't go through it. Nothing in this project needs one; if something does, set the environment variable in the process that launches the Quarkus Agent MCP server instead.

> **CRITICAL — always verify OIDC is active after every start or restart:**
> After `quarkus_start` or `quarkus_stop` + `quarkus_start`, confirm OIDC loaded by running:
> `curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/admin/mcp`
> The response **must be `401`**. A `403` means the app started **without** the OIDC flag — Keycloak Dev Services did not spin up. If you see 403, stop immediately with `quarkus_stop` and restart with `GAME_OIDC_ENABLED=true ./mvnw quarkus:dev` via CLI (the env var is the most reliable path). Do **not** attempt multiple `quarkus_start` retries with different profiles or args — they all ignore the env var and will fail the same way.

**Endpoints:**
* Player View: `http://localhost:8080/`
* Dashboard: `http://localhost:8080/dashboard`
* Dev UI: `http://localhost:8080/q/dev/`

### Load Testing & Performance Analysis
To run load tests and analyze system behavior:

1. **Start the application:**
   * Agent: Call MCP tool `quarkus_start` with `extraArgs: "-Dquarkus.oidc.tenant-enabled=true"`
   * CLI: Run `GAME_OIDC_ENABLED=true ./mvnw quarkus:dev`
2. **Execute the load generator (JBang):**
   ```bash
   jbang scripts/GameLoader.java http://localhost:8080 --players 12 --clicks 600 --power 10 --interval 75 --ws
   ```
   * The script registers simulated players across both teams and waits for the game to start before sending clicks.
   * `--power` is the **maximum** per click; actual values are random in `[1, power]`, so players diverge naturally.
   * `--interval` controls milliseconds between click batches (default 200). Use `--interval 75` for ~20s races, `--interval 100` for ~45s races.
   * `--ws` sends clicks over the player WebSocket instead of `POST /api/power` (recommended).
   * Over WebSockets (`--ws`), the loader taps until the race finishes, so `--clicks` is only a safety cap. Each click is a random 1..power (about `power/2`), so finishing needs roughly `3000 / power` batches; the default cap (500) covers the usual powers. Without `--ws` there's no finish detection, so size `--clicks` to at least `3000 / power`.
3. **Start the game:**
   * Open `http://localhost:8080/dashboard` in a browser (dev mode login: `developer` / `developer`, configured by `game.admin.*` in [`GameConfig.java`](src/main/java/org/acme/game/GameConfig.java) and checked by [`AdminIdentityProvider.java`](src/main/java/org/acme/admin/AdminIdentityProvider.java)), and click the **Play** button.
   * Alternatively, send a POST event using an authenticated admin session to `/api/game/event` with `{"type":"start"}`.
4. **Collect and analyze performance metrics:**
   * **Prometheus metrics:** `GET http://localhost:8080/metrics` (includes HTTP request latency, Kafka producer/consumer throughput, and Infinispan counter stats).
   * **Health & Liveness:** `GET http://localhost:8080/q/health`
   * **Micrometer timer / counts:** Search metrics for `http.server.requests`, `counted.user_assigned`, and Kafka messaging metrics.

---

## 4. Testing & Playwright Safety Rules

### Test Structure
1. **Unit / API Tests:** [`PowerResourceTest.java`](src/test/java/org/acme/game/PowerResourceTest.java) and [`RaceResourceTest.java`](src/test/java/org/acme/race/RaceResourceTest.java) test REST endpoints with RestAssured against Dev Services. [`RaceTopologyTest.java`](src/test/java/org/acme/race/RaceTopologyTest.java) tests the Kafka Streams topology with `TopologyTestDriver`, without a broker. [`RaceToolsTest.java`](src/test/java/org/acme/admin/RaceToolsTest.java) tests both MCP endpoints with `McpAssured` and real tokens from Keycloak Dev Services.
2. **Browser E2E Tests:** [`RaceWebUITest.java`](src/test/java/org/acme/RaceWebUITest.java:34) tests full browser interactions (Playwright + Quinoa).
3. **Optional tests:** the AI commentary tests (`AiCommentatorTest`, with a local OpenAI stub) and the cheat endpoint tests (`CheatResourceTest`) are tagged `@Tag("optional")`. Each needs its own app boot, so the default run skips them. Run them with `./mvnw test -Poptional`. Every `@TestProfile` restarts the app and its Dev Services, so reuse an existing profile before adding one.
4. **Load Simulation:** [`scripts/GameLoader.java`](scripts/GameLoader.java:36) uses non-blocking Vert.x WebClient for traffic generation.

### CRITICAL: Playwright & Container Safety
* **NEVER run load tests using Playwright / browser instances:** Launching multiple headless Chromium instances (`Playwright.create()`) simultaneously consumes immense CPU/RAM and can crash the host system. Heavy traffic load simulation is strictly handled via `scripts/GameLoader.java`.
* `RaceWebUITest` is annotated with `@Tag("e2e")` and excluded from default test runs. To run it on demand, use `./mvnw test -Pe2e`.
* Keep Playwright tests scoped to low concurrency (e.g., 2–10 players) for E2E UI verification only.
* If tests time out or crash, clean up orphaned `chrome-headless-shell` processes and stopped Testcontainers (`Infinispan`, `Kafka`) to free system memory.

---

## 5. Agent Skills & MCP Configuration

### Project Skills & MCP Setup
* **MCP Server Config:** [`.mcp.json`](.mcp.json:1) (Claude Code / Cursor) and [`.bob/mcp.json`](.bob/mcp.json:1) (Bob) define the project-level game MCP servers, `wind-turbine-race` (`/mcp`) and `wind-turbine-admin` (`/admin/mcp`). These connect while dev mode runs.
* **Quarkus Agent MCP (Bob only):** The `quarkus-agent` server is **not** in `.bob/mcp.json` because it requires machine-specific paths (`jbang` and `JAVA_HOME`) that Bob does not expand from environment variables. Add it once to your global Bob config (`~/.bob/settings/mcp.json`) with absolute paths for your machine:
  ```json
  "quarkus-agent": {
    "command": "/path/to/jbang",
    "args": ["quarkus-agent-mcp@quarkusio"],
    "env": {
      "JAVA_HOME": "/path/to/java21+"
    }
  }
  ```
  For sdkman users: `command` = `~/.sdkman/candidates/jbang/current/bin/jbang` (expand `~` to your home dir), `JAVA_HOME` = `~/.sdkman/candidates/java/current`.
* **Load Testing Skill:** [`.agents/skills/wind-turbine-load-test/SKILL.md`](.agents/skills/wind-turbine-load-test/SKILL.md:1) provides automated procedures for running traffic simulations with JBang, starting races, and analyzing Prometheus metrics.
* **MCP Demo Skill:** [`.agents/skills/wind-turbine-mcp-demo/SKILL.md`](.agents/skills/wind-turbine-mcp-demo/SKILL.md:1) covers driving a race through the game's MCP servers.

> **MANDATORY — activate the MCP demo skill before every race:**
> Whenever the user asks to start, run, load players, demo, or watch a race, call `use_skill("wind-turbine-mcp-demo")` **before taking any action**. Do not skip this even if you think you remember the rules — the skill contains critical race-running instructions (polling loop, secret Ferrari boost at 65%, IBM logo on Ferrari, OIDC verification) that must be re-read every time. Skipping the skill activation is the direct cause of missed boosts, missing logos, no live updates, and improper race flow.

* Skills live in `.agents/skills/`. `.claude/skills` is a symlink to it, so Claude Code finds the same skills as agents that read `.agents/`.

### Quarkus Extension & MCP Conventions
When adding or modifying Quarkus capabilities:
1. **Extension-First:** Search for matching Quarkus extensions (`quarkus_searchDocs` or `quarkus_searchTools query='extension'`) before writing custom boilerplate.
2. **Consult Skills:** Call `quarkus_skills` before implementing new features to review configuration properties and architectural patterns.
3. **Dev MCP Tools:** Use `quarkus_callTool` with `devui-testing_runTests` or `devui-testing_runTest` for running tests in dev mode.
4. **Never run `mvn clean` while dev mode is active:** It deletes `target/test-classes` and breaks the live test runner.
