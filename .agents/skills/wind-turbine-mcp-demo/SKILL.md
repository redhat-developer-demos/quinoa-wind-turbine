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
`--interval 100` ≈ 27s, `--interval 50` ≈ 14s. Set `--clicks` well above `1500 / ((power + 1) / 2)`
(about 273 for `--power 10`), or slow players stop before the finish line.

With random power, players naturally diverge and teams produce genuine race drama. Team 1
still wins ties on the tie-break rule.

## Run the race

1. `reset_race` starts a new race ID and clears the scores. Connected players register again by
   themselves.
2. `get_race_state` until the expected players are listed.
3. `start_race`.
4. Poll `get_race_state` every few seconds and report what the user asked for, such as the first
   team whose `distance` reaches 50, a lead change, or the leaderboard. Stop polling when the status
   is `FINISHED`, and report the `winner` and finish times. Clients that support resource
   subscriptions can subscribe to `race://current` instead of polling.
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
