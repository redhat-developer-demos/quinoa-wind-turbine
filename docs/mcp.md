# MCP and code assistants

The app is also an MCP server, so an agent such as Claude Code or goose can read the race and, with an admin login, run it. There are two endpoints, because MCP clients only start their login when the server answers with a 401.

`/mcp` needs no login. It has the tools `get_race_state`, `get_leaderboard`, `get_player` and `get_hall_of_fame`, the resource `race://current`, which sends an update notification to subscribers every time the race changes, and the prompts `race_briefing` and `race_recap`.

`/admin/mcp` has the same tools, resource and prompts, plus `start_race`, `pause_race`, `reset_race`,
`boost_team`, `reset_hall_of_fame`, `set_input_mode` (`tap`, `shake` or `both`, between races only),
`set_commentary_style` (`broadcast`, `hype`, `analyst` or `dry`, between races only), and the theme
tools `list_preset_themes` and `apply_theme` (between races only; a finished heat is reset first so
the new names never land on its leaderboard). It also has the `theme_idea` prompt. It
needs a bearer token with the `admin` role from the `wind-turbine` Keycloak realm. Enable OIDC
explicitly to use it. For local development:

```bash
GAME_OIDC_ENABLED=true ./mvnw quarkus:dev
```

That variable only feeds the `quarkus.oidc.tenant-enabled` property, so passing the property itself
works the same and needs no environment change:

```bash
./mvnw quarkus:dev -Dquarkus.oidc.tenant-enabled=true
```

This is how a code assistant enables it through the Quarkus Agent MCP. `quarkus_start` accepts
`extraArgs` but no environment variables, and the argument survives `quarkus_restart`.

Keycloak Dev Services then imports [`wind-turbine-realm.json`](../src/main/resources/keycloak/wind-turbine-realm.json),
with the user `developer` / `developer`. With OIDC enabled, a request without a token gets a 401
that points to `/.well-known/oauth-protected-resource/admin/mcp`, where the client finds the realm
and runs the login in a browser. With OIDC disabled, admin MCP rejects every request with 403,
including requests with a dashboard login cookie.
The dashboard uses its separate form login and keeps working in either mode.

The project's [`.mcp.json`](../.mcp.json) lists both endpoints as `wind-turbine-race` and `wind-turbine-admin`, next to the Quarkus Agent MCP, so Claude Code offers them when you open the repository and asks you to approve them once. They only connect while dev mode runs. The admin entry uses the realm's public client `mcp-agent`, whose redirect URI is `http://localhost:33418/callback`, so keep its callback port if you add the server to another client. For another URL, such as a deployment on OpenShift, add a server yourself with the same client:

```bash
claude mcp add --transport http --client-id mcp-agent --callback-port 33418 wind-turbine-admin https://<your route>/admin/mcp
```

To log in, run `/mcp` in Claude Code, pick `wind-turbine-admin`, choose Authenticate and log in as `developer`. You can then ask it, for example, to start the race and tell you when one team passes 50%.

## Prompt templates

Prompts are MCP's template primitive: the server owns the wording and fills in its own data, and the client only picks arguments. The game uses them so an agent does not have to paste race JSON or remember the percentage and rounding rules.

| Prompt | Server | Arguments | Returns |
|---|---|---|---|
| `race_briefing` | both | `style` (`broadcast`, `hype`, `analyst`, `dry`), `focus` (a team or player, optional) | Two user messages: the briefing rules, then the live race and team names as JSON |
| `race_recap` | both | `raceId` (a finished race, default latest), `tone` | The recap rules and the hall-of-fame entry, or the race in progress when nothing has finished |
| `theme_idea` | admin | `event`, `audience` (optional) | A design brief with the presets, cars, logos and validation rules, ending in `apply_theme` |

The `style` and `tone` arguments offer completion values, so a client suggests them as the operator types. Prompts only read state: applying a theme or starting a race still goes through the tools and their checks.

## Bob

[`.bob/mcp.json`](../.bob/mcp.json) gives both admin servers `"oauth": {"enabled": true, "clientId": "mcp-agent"}`, so Bob logs in with the pre-registered public client `mcp-agent` (authorization code with PKCE) and skips dynamic client registration. The operator signs in as `developer` in the browser and no secret is stored anywhere. Bob's callback is `http://127.0.0.1:33418/callback`, and it falls back to ports 33419 to 33427 when the first is busy, so the realm lists all ten. A realm imported before those redirect URIs were added needs them added to `mcp-agent` through the Admin API or the console. Use `Reset auth` in Bob's MCP settings after changing the client.

Dev Services uses port 8181 so its authorization server URL stays the same across restarts, and the service name `wind-turbine`, so it gets its own container rather than another Quarkus project's Keycloak when container reuse is enabled. Restart dev mode after changing the realm file so Keycloak imports it; an already running container keeps its old realm configuration.

### Dynamic registration (fallback)

Remove the `oauth` block from `.bob/mcp.json` and Bob registers a client itself. The dev realm accepts anonymous registration and applies the `wind-turbine-audience` client scope to new clients. Keycloak asks the operator to consent during browser login.

If registration fails with `Policy 'Trusted Hosts' rejected request to client-registration service`,
the dev realm kept Keycloak's default Trusted Hosts policy. Keycloak adds it to realms created from
its template, the realm file doesn't declare one, and Keycloak refuses the policy with both checks
off, so the realm file can't turn it off. Run the script instead:

```bash
scripts/open-dev-registration.sh
```

It reads the realm from the running app's metadata, deletes the policy and registers a throwaway
client to check the result, then deletes that client. Pass the app URL if it isn't
`http://localhost:8080`. The dashboard login and the read-only server work without this.

If Bob returns to `Needs auth` after browser login and Quarkus logs `The JWT is no longer valid`, compare `date -u` with `podman machine ssh date -u`. A Podman VM clock behind the host makes new Keycloak tokens appear expired to Quarkus. Run `podman machine ssh sudo chronyc -a burst 4/4`, check the clocks again, then use `Reset auth` and `Authenticate` in Bob.

## OIDC outside dev mode

OIDC is off by default in every mode. Outside dev and test, enabling it also needs
`GAME_OIDC_URL` pointing at the realm, for example `https://keycloak.example.com/realms/wind-turbine`.
Tests that exercise token authentication enable OIDC in their own test profile.

## Skills

The skills in [`.agents/skills`](../.agents/skills) drive the game from an assistant. `.claude/skills` is a symlink to the same directory.

`wind-turbine-mcp-demo` resets the race, gets simulated players in with `GameLoader --ws`, starts the race and reports lead changes and milestones. If the admin login is missing it stops and asks you to authenticate.

`wind-turbine-load-test` runs the GameLoader and reads the Prometheus metrics at `/metrics`.
