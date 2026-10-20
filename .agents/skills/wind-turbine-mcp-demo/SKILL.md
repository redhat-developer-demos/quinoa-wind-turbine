---
name: wind-turbine-mcp-demo
description: Run a live demo of the wind turbine race driven by an agent through the game's own MCP server - start, pause and reset the race, watch the standings and report milestones such as a team passing 50%. Use when the user asks to demo the MCP server, run or watch a race from the agent, or prepare the "start the race and tell me when one team passes 50%" stage moment.
---

# Drive the wind turbine race through its MCP server

The app is an MCP server with two endpoints, configured in `.mcp.json` (Claude Code / Cursor) and `.bob/mcp.json` (Bob):

| Server | URL | Login | Tools |
|---|---|---|---|
| `wind-turbine-race` | `http://localhost:8080/mcp` | none | `get_race_state`, `get_leaderboard(team?)`, `get_player(name)`, resource `race://current` |
| `wind-turbine-admin` | `http://localhost:8080/admin/mcp` | OAuth, `admin` role | the same, plus `start_race`, `pause_race`, `reset_race` |

`get_race_state` returns the status (`WAITING`, `STARTED`, `PAUSED`, `FINISHED`), the race time, the
teams in race order with `distance` in percent of the track, and the players sorted by generated
power. Team 1 is Mountains and team 2 is Beach.

## Running against a cluster deployment

The `.mcp.json` / `.bob/mcp.json` URLs point at `localhost:8080` for dev mode. To drive a deployment
on OpenShift, point the client at the route instead, for example `https://<app-route>/mcp` and
`https://<app-route>/admin/mcp`. With Bob: `bob mcp add wind-turbine-race-cluster https://<route>/mcp
-t http --scope global` (and the same for `/admin/mcp`). Headless `bob run` needs `BOB_API_KEY` set in
the environment.

Admin MCP on the cluster needs OIDC and a Keycloak realm that lets the agent register a client:

- Enable OIDC on the deployment with `GAME_OIDC_ENABLED=true` and `GAME_OIDC_URL=https://<keycloak>/realms/wind-turbine`.
  A Quarkus redeploy replaces the deployment and drops variables set with `oc set env`, so re-apply
  them (or bake them into the build) after every redeploy.
- The `wind-turbine` realm must have the client-registration policies and the `wind-turbine-audience`
  default scope from `src/main/kubernetes/keycloak/realm-import.yaml`, and no "Trusted Hosts" policy,
  so an agent like Bob can register a client dynamically and get a token with the `wind-turbine`
  audience. A realm imported before those were added keeps Keycloak's strict Trusted Hosts default and
  rejects registration ("Host not trusted"); re-importing is additive, so recreate the realm fresh.
  Recreating the realm deletes any client an agent registered dynamically, so afterwards clear the
  agent's cached OAuth for the realm and re-authenticate — for Bob, the `bob.mcp.oauth.*` entries in
  `~/.bob/settings/auth-secrets.json`, and free its callback port (33418) if a stale `bob` process
  holds it. Otherwise the login reuses the dead client and the callback shows "Authorization flow is
  not active".
- Read-only `/mcp` tools work with no login, so a headless agent can watch a cluster race at once.
  Admin tools need a one-time interactive OAuth login the agent can't do headless: run `bob chat` (or,
  in Claude Code, `/mcp` then Authenticate) and log in as `developer`, which caches the token.

## Smooth demo rule: no commands that need approval

When the user asks to run, start or demo the game, drive it **only** through tools that don't prompt:
the `wind-turbine-race` / `wind-turbine-admin` MCP tools and the Quarkus Agent MCP (`quarkus_status`,
`quarkus_start`, `quarkus_logs`). Don't use `execute_command`, `curl`, `lsof`, `ps`, `kill`, `sleep`
or shell polling loops, and skip the HTTP fallback below. Each of those triggers an approval prompt
and breaks the flow.

- Check whether the app is up with `quarkus_status` or a `get_race_state` call, not `lsof` or `curl`.
  If `get_race_state` answers, the app is already running: don't start a second instance.
- Poll with `get_race_state`, but never call it 5 times in a row with identical arguments: the
  tool runner treats that as a doom loop and aborts the run, so the demo stalls and the race ends
  unreported. Alternate it with `get_leaderboard` (for team 1, then team 2, then no team), and keep
  going until the status is `FINISHED`. Report each lead change and milestone as you see it, and
  always report the final result. If the client can subscribe to `race://current`, subscribe instead
  of polling. Plain reads of the resource return the same snapshot as `get_race_state`, so they don't
  avoid the repeated-call guard.
- The one command the demo can't avoid is the simulated-player launcher. It is pre-approved in
  `.claude/settings.local.json` as `Bash(jbang scripts/GameLoader.java:*)`. Run exactly that
  command, and nothing else, from the shell. If it isn't pre-approved (Bob), ask the user once to
  start it, or to let real phones join.
- If something fails and the fix needs a shell command, stop and tell the user. Don't improvise.

## Before the demo

1. Make sure dev mode runs with OIDC enabled. The default game runs without Keycloak, so admin MCP
   needs `GAME_OIDC_ENABLED=true` in the dev process environment. With the Quarkus Agent MCP, its
   server process must inherit that environment variable before `quarkus_start`. Otherwise start
   with `GAME_OIDC_ENABLED=true ./mvnw quarkus:dev`. Check status with `quarkus_status`.
2. Check that both game servers are connected. If `wind-turbine-race` shows `ECONNREFUSED`, it was
   probably tried before dev mode started: ask the user to run `/mcp`, pick it and choose Reconnect.
   It needs no login, so Authenticate is the wrong choice for it.
3. The admin login opens a browser, so only the user can do it: `/mcp`, pick `wind-turbine-admin`,
   choose Authenticate, and log in as `developer` / `developer`. Keycloak Dev Services uses port
   8181 in dev mode. The user may need to authenticate again when the realm or its keys change.
4. Ask the user to open `http://localhost:8080/dashboard` in a browser (same login) so the audience
   sees the race. The dashboard follows the server's state, so it shows what the tools do.
5. Each tool call waits for a permission prompt or an auto-mode check, which can take several
   seconds. For a live demo, suggest allowing the game's tools in `.claude/settings.json`, for example
   `mcp__wind-turbine-race__*` and `mcp__wind-turbine-admin__*`, so milestones aren't reported late.
   With Bob, the cleanest way to avoid prompts is to launch `bob chat --auto-approve`. Bob keeps
   per-tool approvals only in its global store (`~/.bob/settings/settings.json`, `approval.allowedExecutors`),
   not per project, and reads them at startup, so editing them while a chat is open has no effect.

## Get players into the race

Real phones join at `http://<host>:8080/`. Without phones, start simulated players over the player
WebSocket in the background:

```bash
jbang scripts/GameLoader.java http://localhost:8080 --players 12 --clicks 600 --power 10 --interval 75 --ws
```

Wait until its output says `sockets connected, waiting for the game to start`. The players then
show up in `get_race_state` with `generated: 0`.

**Pace tuning:** every player needs 1,500W to finish. `--power` is the *maximum* per click —
actual values are random in `[1, power]`, so the average is `(power + 1) / 2`. Race duration is roughly
`1500 / ((power + 1) / 2) × interval_ms / 1000` seconds. The recommended preset (`--power 10 --interval 75`)
gives ~20-second races with genuine spread between players. Adjust `--interval` to taste:
`--interval 100` ≈ 27s, `--interval 50` ≈ 14s. Over `--ws` the loader taps until the race finishes, so
`--clicks` is only a safety cap and the default (500) is plenty; raise it only for very slow settings.

With random power, players naturally diverge and teams produce genuine race drama. Team 1
still wins ties on the tie-break rule.

## Run the race

When the user asks to run or demo the race, do the whole flow yourself and don't stop to ask between
steps: reset, get the players in, start, monitor, and report the result. Only pause for the things
that genuinely need the user, such as the one-time admin login or launching real phones.

Keep the running commentary short, but make the finish a proper recap. During the race the tool output
is already shown, so don't paste raw JSON or the whole leaderboard on every poll: give a one-line
update only when something changes — a lead change, a team passing 25/50/75%, or the finish. Launch the
player loader once and watch the race through `get_race_state`; don't re-read the loader's log file.

When the race is `FINISHED`, give a rich final summary the audience enjoys, not a one-liner:

- A headline with the winning team and the finish time.
- A team standings table: team, power generated, distance, finish time.
- A top players table (overall, from `get_leaderboard` with no team): rank, player, team, power.
- Two or three interesting facts drawn from what you saw — how close the finish was, the top scorer
  and their team, whether the lead changed, the spread between a team's best and worst player.

Emoji and tables are welcome here; this is the payoff moment.

1. `reset_race` starts a new race ID and clears the scores. Connected players register again by
   themselves.
2. `get_race_state` until the expected players are listed.
3. `start_race`.
4. Poll `get_race_state` every few seconds. Report the milestone the user asked for (for example the
   first team past 50%) or lead changes, in one line each. Stop polling when the status is `FINISHED`,
   and give the rich final summary described above. Clients that support resource subscriptions can
   subscribe to `race://current` instead of polling.
5. `pause_race` and `start_race` pause and resume the same race.

If polling through tool calls is too slow for a milestone, wait in a shell loop on the REST endpoint
and read the state through MCP afterwards:

```bash
until curl -s localhost:8080/api/race/state | python3 -c "import json,sys; d=json.load(sys.stdin); sys.exit(0 if d['status']!='STARTED' or max(t['distance'] for t in d['teams'])>=50 else 1)"; do sleep 1; done
```

## Without the MCP tools

When the agent has no MCP connection, for example in a script, the same calls work over HTTP. In
dev mode, the realm's `mcp-agent` client allows a password grant, which the cluster realm doesn't:

```bash
B=http://localhost:8080
AS=$(curl -s $B/.well-known/oauth-protected-resource/admin/mcp | python3 -c "import json,sys;print(json.load(sys.stdin)['authorization_servers'][0])")
TOKEN=$(curl -s -d grant_type=password -d client_id=mcp-agent -d username=developer -d password=developer $AS/protocol/openid-connect/token | python3 -c "import json,sys;print(json.load(sys.stdin)['access_token'])")
H=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
SID=$(curl -s -D - -o /dev/null -X POST $B/admin/mcp "${H[@]}" -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}' | grep -i mcp-session-id | awk '{print $2}' | tr -d '\r')
curl -s -X POST $B/admin/mcp "${H[@]}" -H "Mcp-Session-Id: $SID" -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
curl -s -X POST $B/admin/mcp "${H[@]}" -H "Mcp-Session-Id: $SID" -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"start_race","arguments":{}}}'
```

Read-only calls work the same way against `$B/mcp` without the `Authorization` header.

## Troubleshooting

- `JSON Parse error: Unrecognized token '<'` during authentication means a `/.well-known/*` URL
  returned the app's HTML page. `quarkus.quinoa.ignored-path-prefixes` must include `/.well-known`.
- A 401 from `wind-turbine-admin` after dev mode restarted means the token is from the previous
  Keycloak. Authenticate again.
- A 403 means the token is valid but the user lacks the `admin` role. Only `developer` has it in
  the dev realm.
- `/admin/mcp` requires `GAME_OIDC_ENABLED=true` in every mode. Outside dev mode, also set
  `GAME_OIDC_URL`. See `docs/openshift-install.md`. For a deployed app, add a server with
  `claude mcp add --transport http --client-id mcp-agent --callback-port 33418 wind-turbine-admin https://<route>/admin/mcp`.
- Don't use Playwright or browser instances to simulate players. Use `GameLoader`, as `AGENTS.md`
  requires.
