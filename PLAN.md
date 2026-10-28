# KubeCon US 2026 — Agentic AI inner loop (Race Day) — demo plan

Status: plan finalized 2026-10-23, revised for the audience path, the Ollama/MCP/local-model
spikes, and simplified on 2026-10-27 (Python/Spring runtime spread and sabotage detection
dropped, fresh cluster for the talk). Revised 2026-10-28 for the biggest change: Act 5 is
now the race director, an agent that operates the event, replacing the A2A commentary
mesh, which duplicated a commentator the game already had. This repo
(`redhat-developer-demos/quinoa-wind-turbine`) hosts the game, this plan and, as a
subproject, the `race-ops` director application.

## Session

Title (working): streamlined inner-loop experience for agentic AI — develop, test, and
iterate on agentic applications on a laptop, then deploy the same image to Kubernetes with
zero code changes. ("Multi-agent" softens to "agentic": the director is one agent with
tools plus a deterministic analytics component. Revisit the wording once the director
exists; if the director/analytics split earns A2A a real act, the stronger claim can come
back.)

Goal: showcase Quarkus agentic capabilities. Demo-heavy, roughly 35 minutes of stage
time in a 40-minute slot. Open-source tooling and cloud-native patterns only.

Pain points from the abstract that every act must answer:
- juggling API keys for LLMs and MCP-connected tools
- burden of configuring services correctly
- expensive inference calls
- highly customized dev setups
- MCP and/or A2A for portable integration and separation of concerns
- production-grade inference through configuration alone
- Kubernetes-native health checks, observability, deployment patterns

## Narrative spine

Race Day on `quinoa-wind-turbine`: one scenario for the whole talk. The first half is the
inner loop on the laptop — GameLoader simulates the players, one command starts every
service, iteration is fast. The agent story is the race director: a separate application
that runs the event through the game's MCP server while the presenter talks. The second
half is the production story: the same applications, pre-deployed to OpenShift with a
config-only difference, then the QR code comes out, the audience joins live, and the
director runs the race. Every demo is a zoom-in on one face of the same app.

## Acts

### Act 0 — Inner hell (2 min, slides)
Keys, config sprawl, per-run inference cost, "works on my machine." Opens with the
reShapr benchmark stat: 31 tool calls and ~67k tokens vs 1 call and ~1.8k tokens for the
same task, because APIs weren't designed for agents. Cite it as reShapr's measurement.

### Act 1 — One command, the whole game (5 min)
The presenter plays via GameLoader (`scripts/GameLoader.java`) simulating phones; the
audience watches the projector. `./mvnw quarkus:dev` with Dev Services is the only setup:
Kafka, Infinispan and Keycloak start as containers, wired automatically. Inference comes
from one hosted OpenAI-compatible endpoint — `OPENAI_BASE_URL` + `OPENAI_API_KEY` and a
model name, nothing else (EnMaaS serving GLM 5.3 today). The same jar ships both provider
integrations; `quarkus.langchain4j.chat-model.provider` is openai in dev and prod, and
flipping it to ollama gives fully local inference with the Dev Services container as the
offline fallback. The client code is identical everywhere. The inner-loop pain points are
answered by Dev Services for the services and one endpoint secret for the model — frame
it honestly, no "no keys" claim.

### Act 2 — Iterate and test for free (4 min)
Hot-reload the commentary prompt mid-race (commentator personality changes between
heats). Run the test suite: `AiCommentatorTest` against the stub OpenAI server,
`RaceToolsTest` via McpAssured, Playwright e2e through Quinoa. All local, all free; no
test touches an LLM by default.

### Act 3 — Three ways to give your agent tools (8 min)
1. Hand-written typed tools: wind-turbine `RaceTools`, netatmo — `@Tool` + bean
   validation + OIDC. Max control, you own the code.
2. Generated, governed tools: reShapr pointed at wind-turbine's own OpenAPI schema
   (`/q/openapi`), one Custom Tool composing race state + leaderboard (e.g.
   `get_race_momentum`), token shrink visible live (homebot JSON-RPC trace panel or the
   agent's tool-call count). Sensitive ops (start/pause/reset/boost) stay behind the
   hand-built OIDC-gated `/admin/mcp` server — governance demonstrated. reShapr runs
   from local Docker Compose, never `try.reshapr.io` on stage.
3. The client side: homebot, browser-as-MCP-client, 90-second guided MCP tour.

Note: reShapr is a friend's project — say so on stage; the benchmark is theirs.

### Act 4 — Cut
The kev/laya decision gate is out. The talk is complete without it; revisit only if
everything else lands early.

### Act 5 — The race director (6 min)
`race-ops`: a separate application that operates the event, because the presenter is
busy talking while the audience joins. It knows the game only through its MCP server.

- **Watcher (no LLM):** subscribes to `race://current` (subscription confirmed by spike,
  ~4 notifications/s mid-race, so it filters to meaningful events — milestones, lead
  changes, finish — before anything expensive). Computes the engagement analytics from
  per-player power: how many players tap, how hard, who quit. Plain code, no model; not
  everything needs an LLM.
- **Director (LLM):** decides and acts through the admin MCP toolset — `start_race`,
  `pause_race`, `reset_race`, `boost_team`, `suggest_team_names`, `generate_theme`,
  `apply_theme`. Every power it has is the admin scope, held under an OIDC token.
- **Start rule:** both teams at or above a minimum of active players, joins plateaued
  (no new player for ~20-30 s), or a wall-clock backstop from the run-of-show — the talk
  has a clock and the room doesn't. No headcount balance: the race math is
  engagement-normalized (see "Spike log"), so a 300 vs 480 split is fair.
- **Mid-race:** the director diagnoses why a team is behind — fewer tappers or quitters
  dragging the average — and proposes a boost on the console. Human approval, one click.
  The boost stops being a hidden cheat and becomes a designed, visible beat.
- **Between heats:** proposes team names and themes for the crowd (Atlanta preset,
  `suggest_team_names` from a location prompt).
- **Console:** status line and approval cards on the dashboard, config-gated, plus a
  `/ops` page served by race-ops as the dev surface and second-screen option. On the
  cluster a path-based route under the game's host avoids CORS.
- **Kill beat:** the director dies, the console empties, and the presenter falls back to
  the manual Play button that already exists. Degradation to a manual control room.
- **Demo moments:** the race starts itself while the presenter talks, because
  conditions were met; a boost proposal approved on stage; kill the director and drive
  by hand.

The game's own commentary chain is untouched. `A2ACommentator` is dropped; the console
is the only deliberate change to wind-turbine itself.

### Act 6 — Ship it (5 min)
Pre-warmed cloud OpenShift cluster (freshly provisioned for the talk; the sandbox
cluster is rehearsal-only). The game and the director are pre-deployed before the talk;
the images are built in rehearsal and are the same artifacts that ran on the laptop. On
stage: talk through the production pieces, then reveal the QR and let the audience join
while you continue — the director starts the race when the field is ready. The
config-only difference is the point: `OPENAI_BASE_URL` + `OPENAI_API_KEY` (+ model
name), nothing else. Time-lapse of the one-pass install (`scripts/deploy-openshift.sh`)
runs on a slide behind you while you talk.

Production inference: a hosted OpenAI-compatible endpoint with a bearer token (EnMaaS
today; presenting it as OpenShift AI serving is a rehearsal decision). Keep both paths
open — same cluster or external cluster — by staging two secrets in the namespace; the
switch is identical either way.

Observability beat: once phones are connected, open the Jaeger route mid-race and show
one trace from an audience tap through WebSocket → Kafka Streams → commentary model
call. This needs a collector deployed (today the exporter points at a service that
doesn't exist) and sampling set to 1 for the demo. Prometheus metrics from the 5
replicas.

Closing (2 min): quarkus-agent-mcp driving dev mode (scaffold, hot-reload, run tests,
read logs through Dev MCP), powered by quarkus-langchain4j — "we streamlined the inner
loop for you, and for your agents." Point the audience at
`kdubois/quarkus-workshop-langchain4j` as the take-home.

## Settled decisions (log)

1. Spine is wind-turbine Race Day, not the a2a lab concierge.
2. Inference runs on a hosted OpenAI-compatible endpoint (EnMaaS, GLM 5.3) in dev and
   prod, selected by `quarkus.langchain4j.chat-model.provider=openai` in both. The
   Ollama integration stays in the same jar as the offline fallback: flipping the
   provider in dev starts the Ollama Dev Services container, which bind-mounts
   `$OLLAMA_MODELS` so the model pull happens once per machine (spike-verified; see
   "Spike log"). Default test runs have no LLM provider at all; LLM tests stay behind
   `-Poptional`. (Revised 2026-10-28: the hosted endpoint is the default everywhere;
   the earlier "Ollama in dev" setup pulled a 4 GB container per fresh start and was
   pinned back to openai on 2026-10-25.)
3. One provider integration everywhere: `quarkus-langchain4j-openai`.
4. reShapr woven into Act 3 (generated+governed face) + benchmark stat in Act 0; local
   Docker Compose only; Helm chart gets a slide mention in Act 6.
5. Act 5 is the race director (revised 2026-10-28): a separate race-ops application
   operating the event through the admin MCP tools, with a deterministic watcher for
   engagement analytics and human-approval cards on the console. It replaces the A2A
   commentary mesh, which was bolted on — it duplicated commentary the game already
   had, and its analytics restated the leaderboard. The MCP resource subscription
   spike still applies: subscribe with an event filter, poll as fallback.
6. Decision gate (jev-laya + kev-runtime) is cut (Act 4).
7. Act 6 target is OpenShift (pre-warmed cloud cluster, freshly provisioned); vanilla
   Kubernetes only if time permits later.
8. quarkus-langchain4j and quarkus-agent-mcp are showcased Quarkus projects (closing),
   not demo material.
9. Run-of-show document and slide deck are deferred until the implementation is done
   (talk is demo-heavy; things may pivot).
10. Audience path is the cluster instance only: the QR is held until Act 6, and the
    audience joins while the production pieces are presented. Local demos use
    GameLoader, so the audience never reaches the laptop and no tunnel or LAN
    workaround is needed.
11. Build in `quinoa-wind-turbine` upstream, no fork; race-ops is a subproject of this
    repo (top-level `race-ops/` directory with its own pom, sharing the wrapper)
    instead of a second repository. The director console (config-gated, default off) is
    the only deliberate change to the game; `A2ACommentator` is dropped. race-ops must
    not import game classes, checked by a test. Deploy scripts, reShapr compose and the
    run-of-show live here too.
12. Model choice is GLM 5.3 (hosted) for the talk, granite4.2:3b with thinking disabled
    as the local fallback; both handle tool calling.
13. Race fairness is engagement-based (verified 2026-10-28, see "Spike log"): team
    distance normalizes by the number of players who actually tapped, so headcount
    imbalance is mathematically fair. The director watches per-player engagement, not
    team size. The boost exists to compensate quitters dragging a team's average, not
    to fix player counts.
14. Sabotage detection is out (2026-10-27): it needed a game change (`get_modifiers`)
    for a beat that mattered to a Confluent talk, not this one. The boost becomes a
    director proposal instead of a hidden cheat (2026-10-28).

## Spike log

### Ollama Dev Services (2026-10-23, done)

Question: can dev mode auto-provision local inference while prod stays on the hosted
openai provider with a config-only difference? Yes. Everything below was verified
against LC4J 1.14.1 with a live race.

- `quarkus.langchain4j.chat-model.provider` selects the default provider when several
  integrations are on the classpath. It is fixed at build time, so it lives in
  `application.properties`. Without it, the build fails and lists the available
  providers. No game code changed: `RaceCommentaryAi` and the commentary chain are
  untouched.
- Both Ollama dev-service build steps are gated by the global
  `quarkus.langchain4j.devservices.enabled`. The Ollama-specific
  `devservices.enabled=true` does nothing on its own.
- End-to-end run: `quarkus:dev` started `ollama/ollama` (2 GB image, about a minute,
  one time), pulled qwen3:0.6b (522 MB, ~25 s), preloaded it, and a 29 s race with six
  GameLoader players produced commentary records with `"source":"ai"` at ~3 s per line
  on CPU.
- Model pulls happen once per machine, not per dev session: the container bind-mounts
  `$OLLAMA_MODELS` (default `~/.ollama`) as `/root/.ollama`, so a fresh container finds
  models pulled earlier. A restart within a dev session reuses the running container. A
  host `ollama serve` on port 11434 is detected and used instead of starting a container
  (the host app was uninstalled 2026-10-25, so this machine always uses the container).
- Layout gotcha (hit 2026-10-25): the host Ollama 0.33 and the newer
  `ollama/ollama:latest` container image read different storage layouts from the same
  mounted directory — the host reads the top-level `blobs`/`manifests`, the container
  only the `models/` subdirectory. granite4.2:3b, imported through the host binary, was
  invisible to the container, so a start without the host server pulled the 2.2 GB model
  once into `models/`. After the one-time pull, a fresh container session starts in
  ~30 s with the model present.
- qwen3:0.6b is not good enough for real commentary — it stated wrong finishing orders
  mid-race. It was the wiring test only.
- Tests: `quarkus.langchain4j.ollama.enable-integration=${game.ai.enabled}` keeps every
  default test run free of LLM providers, local or remote. The optional
  `AiCommentatorTest` turns both integrations on but the provider knob keeps it on
  openai against its stub server. Default suite 85/85 and optional 8/8 after the change.

### MCP resource subscription from an external process (2026-10-25, done)

Question: can a process outside the app subscribe to `race://current` and receive
update notifications over the HTTP transport, or does race-ops need to poll? It can.
Verified with plain curl against `/mcp`, no SDK:

- The Streamable HTTP handshake works from outside: `initialize` returns a
  `Mcp-Session-Id`, the server advertises `resources.subscribe`, and
  `resources/subscribe` for `race://current` returns success. Reads and the
  notification stream share one session: POST requests and a long-lived
  `GET /mcp` SSE stream run concurrently.
- During a 29 s race with six GameLoader players, the stream delivered 118
  `notifications/resources/updated` events, about 4 per second. The notification
  carries the resource URI only, so the client follows up with `resources/read`
  (or `get_race_state`) to fetch the snapshot; that read worked mid-stream and
  returned the finished state afterwards.
- Design consequence for race-ops: subscribe, don't poll. But at ~4 notifications
  per second, nothing LLM-backed may run per update. The watcher needs an event filter
  first (milestones, lead changes, finish — the same shape as wind-turbine's
  `CommentaryTriggerDetector`), and it is free to consume every snapshot since it has
  no model.

### Local model for offline commentary (2026-10-25, done)

Question: which already-downloaded model on the presenter laptop can carry the live
commentary if the venue has no usable endpoint? Answer: granite4.2:3b with thinking
disabled, imported from the ramalama GGUF (no download). Everything below ran on the
48 GB M4 Pro against the host Ollama (`ollama serve`, Metal).

- Candidates on disk, no downloads: gpt-oss:20b, granite-code:34b/latest,
  granite3.3:latest, granite4:latest, qwen:latest, qwen2.5-coder:1.5b in the Ollama
  store; granite-4.2-8b-MLX-4bit in the LM Studio store (MLX format, not usable by
  Ollama); granite-4.2-3b-Q4_K_M.gguf in the ramalama store.
- gpt-oss:20b is dead: the 13-month-old blob fails to load on Ollama 0.33 with
  "tensor size overflow". Old blobs can break across Ollama upgrades.
- Prompt benchmark, then live races through the game's own commentary pipeline:
  qwen3:0.6b (2-6 s/line) produced fact-mush and referred to teams by number.
  granite3.3:latest (1-1.4 s) invented facts. granite4:latest (0.3-3 s) produced nine
  clean lines in its race. granite4.2:3b has the best fact density.
- granite4.2 is a reasoning model with thinking on by default: 9-24 s per line until
  `quarkus.langchain4j.ollama.chat-model.model-options.think=false` dropped it to
  0.4-2.8 s. That setting is now in the config.
- Live race with granite4.2:3b: lead, milestone and finish lines all from the model
  in 0.6-2.8 s, correct facts, team names used. One line leaked a raw float
  ("2.75555555555555 percent"). Fixed at the source: the facts JSON now rounds
  distances to one decimal in `AiCommentator` (the model echoes the precision it
  receives) and the system prompt asks for at most one decimal; the verification race
  produced only clean numbers, including computed gaps.
- granite4.2:3b is in the Ollama registry (2.2 GB), so a fresh machine still
  auto-provisions it. Pre-pull it once into `$OLLAMA_MODELS` on the stage laptop.

### Race fairness math (2026-10-28, done)

Question: does room size or a lopsided team split change the race, and what should the
director watch? No, and per-player engagement.

- Team distance is `min(100, power * 100 / (tapPower * tapsNeededPerPlayer * players))`
  in `GameConfig.RaceRules`. The topology counts `players` as everyone who sent at
  least one power event in the race (`TeamProgress.add` once per player, removed only
  on a team switch), and `power` is the team total. So distance reduces to the team's
  average power per tapping player: headcount cancels, and a 300 vs 480 split with
  equal averages is a dead heat.
- The real bias is engagement: a player who taps once and pockets the phone counts in
  the denominator forever, dragging their team's average. The director's fairness
  signal is the per-player power distribution (it already has the leaderboard), which
  distinguishes "fewer tappers" from "quitters" — a diagnosis a human operator can't
  make mid-talk.
- Small fields are noisy (one super-tapper swings a 5-player team); at hundreds per
  team, averages converge. The start rule therefore needs no headcount balance:
  minimum actives per team, join plateau, wall-clock backstop.
- A boost multiplies a team's incoming power, so it scales the average directly — the
  right compensation for quitters, not for player counts.

## Remaining work

Done so far: the MCP subscription spike, the Ollama wiring with the hosted endpoint as
default, the platform alignment (LC4J 1.15.0.CR1 through the quarkus-langchain4j-bom,
Quarkus 3.40.1.1, Quinoa 2.9.3), and the upstream game features (AI themes, hall of
fame over MCP, join QR, Confluent/Strimzi deploy profiles), all verified and deployed
to the rehearsal cluster.

1. **Document surgery** (this change): delete `docs/roadmap.md` (all three features
   shipped), fold its still-true notes into `AGENTS.md`, rewrite this plan around the
   director.
2. **Director skeleton and read path.** `race-ops/` with its own pom sharing the
   wrapper; MCP client; `race://current` subscription with the event filter; engagement
   analytics as plain code; stub tests with recorded snapshots, no game import (checked
   by a test). Done when a dev race shows filtered events and engagement stats with
   zero model calls.
3. **The director agent.** LLM decisions over the admin toolset, OIDC token handling —
   spike first: dev direct grant locally, a confidential client with the `admin` role
   on the cluster, and a token lifetime that survives a talk. Policy split between
   what it may do alone and what needs approval. Done when a rehearsal race runs
   start to finish with the presenter only approving.
4. **The console.** Status line and approval cards in the dashboard webui,
   config-gated, default off; a `/ops` page served by race-ops as the dev surface and
   second-screen option; path-based route under the game's host to avoid CORS. Done
   when the kill beat works: director dies, console empties, ticker continues, manual
   controls take over.
5. **Deploy race-ops.** One replica (five would mean five directors); the MCP URL
   points at the source-balanced route so session stickiness holds across the game's
   five pods; the model secret is reused. Done when the Act 6 sequence runs on a
   cluster end to end.
6. **reShapr.** Local Docker Compose, import wind-turbine `/q/openapi`, one Custom
   Tool, an agent calling it, our own before/after token count. Done when the number
   is measured, not quoted.
7. **Stage readiness.** Provision the fresh cluster; run and pre-warm
   `scripts/deploy-openshift.sh`; deploy a Jaeger collector and set OTel sampling to 1
   (the exporter currently points at a service that doesn't exist); load-test 500 and
   1000 GameLoader players from outside; rehearse the audience trickle-in with the
   director deciding the start.
8. **Run-of-show and slides** after the implementation is done: per-act timing,
   terminal commands, env files, fallback triggers; slide deck brief in the Slides
   workspace (existing pipeline, kubecon-japan-2026 style); rehearsal passes.

## Assets and roles

- `redhat-developer-demos/quinoa-wind-turbine` — spine; game, MCP server, AI
  commentary, OpenShift deploy. Quarkus 3.40.1.1, LC4J 1.15.0.CR1 through the
  quarkus-langchain4j-bom, quarkus-mcp-server 2.0.3, Quinoa 2.9.3.
- `race-ops/` in this repo — the director application, its deploy manifests, the
  reShapr compose and the run-of-show. Lives with the game as a subproject; nothing
  stays in a second repository.
- `kdubois/mcp-demo/netatmo` — typed tools deep-dive, McpAssured tests, OIDC admin
  tools. Knative/k8s manifests exist.
- `kdubois/mcp-demo/homebot` — MCP client + browser-as-MCP-client, JSON-RPC trace
  panel, guided MCP tour, env-based provider switch.
- `kdubois/a2a-agent-lab-2026` — pattern source for LC4J agent structure and
  stub-based agent tests. Quarkus 3.40.1, LC4J 1.15.0.CR1. A2A itself only appears if
  the director/analytics split earns it.
- `kdubois/quarkus-kubernetes-kafka-db` — vanilla-k8s deploy patterns (backup path).
- `kdubois/quarkus-workshop-langchain4j` — audience take-home pointer.
- Showcase-only (closing): `kdubois/quarkus-langchain4j-source`,
  `kdubois/quarkus-agent-mcp`.

## Risks and fallbacks

- Hosted endpoint fails → template commentator (built into wind-turbine) plus the
  Ollama fallback for a fully local run; pre-recorded video last.
- Director fails or misbehaves → its actions are approval-gated and the manual
  controls already exist, so the show continues by hand. Rehearse the kill.
- OIDC token expires mid-talk → refresh or a long-lived token, resolved in the item-3
  spike.
- Act 6 cluster fails → what is lost is the audience QR and live play. Fall back to
  the presenter's dashboard on the laptop plus rehearsal video. The cluster route is
  exercised in every rehearsal, so the failure mode is known before stage day.
- Audience joins mid-race → rehearse the trickle-in: commentary, leaderboard and the
  director's start decision must look right while phones arrive over several minutes.
- reShapr import fails → benchmark slide carries the point alone.
- Talk runs long → cut order: Act 4 is already cut → compress Act 3 netatmo depth.
- Version mismatch on stage: resolved — wind-turbine is aligned to LC4J 1.15.0.CR1
  (imported through the quarkus-langchain4j-bom; both test suites and a live race
  verified after the upgrade, Ollama provider path included). 2.0.0.Beta1 pairs with
  Quarkus 4 Beta and stays out.
- "Zero code changes" honesty: the prod profile must differ from dev in config only —
  build race-ops and the wind-turbine changes that way from day one.

## Open items

- Cluster for the talk: provision fresh (the sandbox cluster is throwaway, rehearsal
  only). Still to decide: ROSA/ARO/self-managed, and who pays for it. Longest lead
  time; start now.
- Production inference framing: EnMaaS endpoint today, presented as-is or as
  OpenShift AI serving — decide in rehearsal.
- Stage AV: one projector or a second screen for the `/ops` page. Ask the KubeCon AV
  people; the console band works either way.
- Final title wording ("agentic" vs "multi-agent") once the director's shape is
  visible.
