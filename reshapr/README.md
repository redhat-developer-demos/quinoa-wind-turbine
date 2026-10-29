# reShapr — the generated, governed face of the race demo

Act 3's second face: not every API gets a hand-built MCP server (that's face 1, the game's
`RaceTools`). reShapr turns the game's existing REST API into MCP tools with zero code, and
the demo measures what shaping saves. It runs locally only — never `try.reshapr.io` on stage.

## The two endpoints

`scripts/reshapr-setup.sh` imports the game's `/q/openapi` contract as two services and
exposes one MCP endpoint each:

- **Wind Turbine Racer Raw** — the naive translation: every operation becomes a tool with
  its full JSON response, including the unauthenticated `/api/cheat` and the two SSE
  streams. The honest "before"; no artifact is ever attached to this service, because an
  absent artifact selection means "all attached artifacts apply" and would bleed the custom
  tool into the baseline.
- **Wind Turbine Racer** — the reshaped face: [`get-race-momentum.yaml`](get-race-momentum.yaml)
  defines one scripted Custom Tool that orchestrates `GET /api/race/state` and
  `GET /api/race/leaderboard` in parallel and returns a compact momentum summary (leader,
  gap, tappers per team, top players). The raw operations stay callable inside the script
  but the plan hides them, so the client sees exactly one tool.

## Running it

```bash
# terminal 1: the game, reachable from the reShapr containers (they run in a VM)
GAME_OIDC_ENABLED=true ./mvnw quarkus:dev -Dquarkus.http.host=0.0.0.0

# terminal 2: both MCP servers (needs the reshapr CLI and a container runtime)
scripts/reshapr-setup.sh

# then, during a live race, the measurement
scripts/reshapr-measure.sh
```

`reshapr-setup.sh` is idempotent: imports and the artifact update in place, the plan and
exposition are recreated. The control plane is at http://localhost:5555 (admin/password),
the proxy serves each exposition at `http://localhost:7777/mcp/<id>`.

First measured run (2026-10-29, live race, GLM 5.3, same question asked of both endpoints):
the raw translation offered 18 tools and cost 3848 tokens; the reshaped server offered one
tool and cost 734 — 81% fewer. Re-run `reshapr-measure.sh` for fresh numbers; the slide
should cite a measurement, not this one.

## Notes

- `host.containers.internal` from the reShapr containers does not reach a dev-mode Quarkus
  bound to localhost (podman on macOS, gvproxy answers 400), which is why the game must
  bind `0.0.0.0` and the script targets the machine's LAN address.
- The langchain4j MCP client (`StreamableHttpMcpTransport`, 1.21.0-beta31) negotiates
  reShapr's stateless 2026-07-28 protocol without configuration — the compatibility spike
  is done.
- `scripts/ReshaprMeasure.java` is the agent behind the measurement: same model
  (`OPENAI_BASE_URL`/`OPENAI_API_KEY`/`OPENAI_MODEL`, the game's AI commentary
  environment), same question, manual tool loop so every request's token usage and every
  tool call is counted.
