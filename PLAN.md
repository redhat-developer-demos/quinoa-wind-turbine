# KubeCon US 2026 — Agentic AI inner loop (Race Day) — demo plan

Status: plan finalized 2026-10-23, revised the same day for the actual audience path, and
revised again after the Ollama, MCP subscription and local-model spikes (see
"Spike log"). This repo
(`redhat-developer-demos/quinoa-wind-turbine`) hosts the game, this plan and, as a
subproject, the `race-ops` multi-agent application. Implementation has started: the local
inference wiring is in; see "Build order" for what remains.

## Session

Title (working): streamlined inner-loop experience for agentic AI — develop, test, and
iterate on multi-agent systems on a laptop, then deploy the same image to Kubernetes with
zero code changes.

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
inner loop on the laptop — GameLoader simulates the players, inference is local, iteration
is fast. The second half is the production story: the same application, pre-deployed to
OpenShift with a config-only difference, then the QR code comes out and the audience joins
the live race from their phones while the production pieces are presented. Every demo is a
zoom-in on one face of the same app. The a2a lab concierge scenario is dropped as the
spine.

## Acts

### Act 0 — Inner hell (2 min, slides)
Keys, config sprawl, per-run inference cost, "works on my machine." Opens with the
reShapr benchmark stat: 31 tool calls and ~67k tokens vs 1 call and ~1.8k tokens for the
same task, because APIs weren't designed for agents. Cite it as reShapr's measurement.

### Act 1 — The game runs on your laptop, inference included (5 min)
The presenter plays via GameLoader (`scripts/GameLoader.java`) simulating phones; the
audience watches the projector. Dev mode auto-provisions the local model: Quarkus starts
an Ollama container, pulls the model and wires the base URL. There is no `.env` and no
API key to show. Point out the client code is identical to production. Both provider
integrations ship in the same jar (`quarkus-langchain4j-ollama` and
`quarkus-langchain4j-openai`); `quarkus.langchain4j.chat-model.provider` picks ollama in
dev and openai in prod, config only.

### Act 2 — Iterate and test for free (4 min)
Hot-reload the commentary prompt mid-race (commentator personality changes between
heats). Run the test suite: `AiCommentatorTest` against the stub OpenAI server,
`RaceToolsTest` via McpAssured, Playwright e2e through Quinoa. All local, all free.

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

### Act 4 — OPTIONAL: decision gate (cut candidate)
Integrate the kev/laya decision model into the commentary pipeline: a small local
decision model answers "is this event worth an LLM call?" — milestones and lead changes
get the model, routine updates get template lines. `decision.backend` flips between
hosted Jev, local kev, Laya sidecar, deterministic stub. Only if time permits; never a
standalone segment. The talk is complete without it.

### Act 5 — Multi-agent over A2A (6 min)
race-ops: a separate application that knows the game only through its MCP server.
- Supervisor (Quarkus): `@SupervisorAgent`, routing rules in system message,
  specialists as `@A2AClientAgent` interfaces (patterns from a2a lab exercise 5).
  `DebugAgentListener` shows routing decisions live.
- Commentary agent (A2A server): LLM commentary, same local OpenAI-spec endpoint.
- Analytics agent (A2A server): standings and momentum, deliberately no LLM — the
  supervisor routes by task shape, not everything needs a model.
- Sabotage-detection agent (A2A server): watches boost events, flags the admin's secret
  `boost_team` use. High comedy value, exercises the OIDC admin MCP path.
- Trigger: race events via MCP resource subscription (`race://current` notifications),
  confirmed working from an external process (see "Spike log"). Notifications carry the
  URI only and arrive at ~4/s mid-race, so race-ops reads the snapshot on notification
  but must not run the supervisor per update — filter to meaningful events (milestones,
  lead changes) before any LLM call; polling `get_race_state` stays the fallback.
- Output: race-ops panel or ticker in the webui via SSE/WebSocket.
- Commentary coexistence: `A2ACommentator` as a third `Commentator` implementation in
  wind-turbine (existing seam: `TemplateCommentator`, `AiCommentator`,
  `CommentaryCoordinator`). Chain: A2A mesh when up → in-app LLM → template lines.
  Killing the mesh degrades down the chain live. This is the only deliberate change to
  wind-turbine itself.
- Runtime spread: build all-Quarkus first for reliability; once stable, port analytics
  to Python and sabotage-detection to Spring Boot (templates exist in the a2a lab) for
  the separation-of-concerns beat.
- Demo moments: lead-change fan-out with visible routing; sabotage agent names names;
  hot-reload one specialist while the mesh runs; kill the commentary agent and watch
  the fallback chain engage.

### Act 6 — Ship it (5 min)
Pre-warmed cloud OpenShift cluster (ROSA / ARO / self-managed — not CRC on stage, not
Developer Sandbox since the deploy needs AMQ Streams + Infinispan operators). The game is
pre-deployed before the talk; the image is built in rehearsal and is the same artifact that
ran on the laptop. On stage: talk through the production pieces, then reveal the QR and
start the race — the audience joins live while you continue. The config-only difference is
the point: `OPENAI_BASE_URL` + `OPENAI_API_KEY` (+ model name), nothing else. Time-lapse of
the one-pass install (`scripts/deploy-openshift.sh`) runs on a slide behind you while you
talk.

Production inference: model served via OpenShift AI (OpenAI-compatible endpoint with
bearer token). Keep both paths open — same cluster or external cluster — by staging two
secrets in the namespace (`OPENAI_BASE_URL` + `OPENAI_API_KEY`); the switch is identical
either way. Decide during rehearsal.

Observability beat: once phones are connected, open the Jaeger route mid-race and show one
trace from an audience tap through WebSocket → Kafka Streams → commentary model call.
Prometheus metrics from the 5 replicas.

Closing (2 min): quarkus-agent-mcp driving dev mode (scaffold, hot-reload, run tests,
read logs through Dev MCP), powered by quarkus-langchain4j — "we streamlined the inner
loop for you, and for your agents." Point the audience at
`kdubois/quarkus-workshop-langchain4j` as the take-home.

## Settled decisions (log)

1. Spine is wind-turbine Race Day, not the a2a lab concierge.
2. Local inference auto-provisioned by `quarkus-langchain4j-ollama` Dev Services
   (spike-verified 2026-10-23; reverses the earlier "no Ollama"). Dev mode starts the
   Ollama container, pulls the model and wires the base URL; model storage bind-mounts
   `$OLLAMA_MODELS` so pulls happen once per machine. Prod stays on the openai provider,
   selected by `quarkus.langchain4j.chat-model.provider`; the prod switch remains
   `OPENAI_BASE_URL` + `OPENAI_API_KEY` (+ model name), nothing else. Default test runs
   have no LLM provider at all; LLM tests stay behind `-Poptional`.
3. One provider integration everywhere: `quarkus-langchain4j-openai`.
4. reShapr woven into Act 3 (generated+governed face) + benchmark stat in Act 0; local
   Docker Compose only; Helm chart gets a slide mention in Act 6.
5. A2A: separate race-ops app, MCP-only view of the game; Quarkus-first then spread to
   Python/Spring; coexist-with-fallback commentary chain; MCP resource subscription
   confirmed by spike, polling stays the fallback.
6. Decision gate (jev-laya + kev-runtime) is an optional TODO and cut candidate.
7. Act 6 target is OpenShift (pre-warmed cloud cluster); vanilla Kubernetes only if time
   permits later; OpenShift AI serving for inference, both cluster paths open.
8. quarkus-langchain4j and quarkus-agent-mcp are showcased Quarkus projects (closing),
   not demo material.
9. Run-of-show document and slide deck are deferred until the implementation is done
   (talk is demo-heavy; things may pivot).
10. Audience path is the cluster instance only: the QR is held until Act 6, the race is
    started on stage, and the audience joins while the production pieces are presented.
    Local demos use GameLoader, so the audience never reaches the laptop and no tunnel or
    LAN workaround is needed.
11. Build in `quinoa-wind-turbine` upstream, no fork; race-ops is a subproject of this
    repo (top-level `race-ops/` directory with its own pom, sharing the wrapper) instead
    of a second repository. Only `A2ACommentator` (config-gated, default off) touches
    the game; race-ops must not import game classes, checked by a test. Deploy scripts,
    reShapr compose and the run-of-show live here too.
12. Model choice is GLM 5.3 (hosted, prod-style path) or granite4.2:3b with thinking
    disabled (local, Act 1 path); both handle tool calling, so supervisor routing is
    not a small-model risk.

## Spike log

### Ollama Dev Services (2026-10-23, done)

Question: can dev mode auto-provision local inference while prod stays on the hosted
openai provider with a config-only difference? Yes. Everything below was verified
against LC4J 1.14.1 with a live race.

- `quarkus.langchain4j.chat-model.provider` selects the default provider when several
  integrations are on the classpath. It is fixed at build time, so it lives in
  `application.properties` with `%dev` picking ollama and the default picking openai.
  Without it, the build fails and lists the available providers. No game code changed:
  `RaceCommentaryAi` and the commentary chain are untouched.
- Both Ollama dev-service build steps are gated by the global
  `quarkus.langchain4j.devservices.enabled`. The repo had it set to false from the
  hosted-endpoint decision, and the dev service then skipped silently; the
  Ollama-specific `devservices.enabled=true` does nothing on its own.
- End-to-end run: `quarkus:dev` started `ollama/ollama` (2 GB image, about a minute,
  one time), pulled qwen3:0.6b (522 MB, ~25 s), preloaded it, and a 29 s race with six
  GameLoader players produced commentary records with `"source":"ai"` at ~3 s per line
  on CPU.
- Model pulls happen once per machine, not per dev session: the container bind-mounts
  `$OLLAMA_MODELS` (default `~/.ollama`) as `/root/.ollama`, so a fresh container finds
  models pulled earlier. A restart within a dev session reuses the running container. A
  host `ollama serve` on port 11434 is detected and used instead of starting a container.
- Stage cold start, with the model pre-pulled: a fresh dev session pays only the
  container start (the extension allows one minute) and the model preload.
- qwen3:0.6b is not good enough for real commentary — it stated wrong finishing orders
  mid-race. It was the wiring test; the model choice is still open.
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
  per second, nothing LLM-backed may run per update. The supervisor needs an event
  filter first (milestones, lead changes, finish — the same shape as wind-turbine's
  `CommentaryTriggerDetector`), with the analytics agent free to consume every
  snapshot since it has no model.

### Local model for Act 1 commentary (2026-10-25, done)

Question: which already-downloaded model on the presenter laptop can carry the live
commentary? Answer: granite4.2:3b with thinking disabled. Everything below ran on the
48 GB M4 Pro against the host Ollama (`ollama serve`, Metal), which the dev service
detects on port 11434 and reuses instead of starting a container.

- Candidates on disk, no downloads: gpt-oss:20b, granite-code:34b/latest,
  granite3.3:latest, granite4:latest, qwen:latest, qwen2.5-coder:1.5b in the Ollama
  store; granite-4.2-8b-MLX-4bit in the LM Studio store (MLX format, not usable by
  Ollama); granite-4.2-3b-Q4_K_M.gguf in the ramalama store.
- gpt-oss:20b is dead: the 13-month-old blob fails to load on Ollama 0.33 with
  "tensor size overflow". Old blobs can break across Ollama upgrades.
- Prompt benchmark, then live races through the game's own commentary pipeline:
  qwen3:0.6b (2-6 s/line) produced fact-mush and referred to teams by number.
  granite3.3:latest (1-1.4 s) invented facts. granite4:latest (0.3-3 s) produced nine
  clean lines in its race. granite4.2:3b, imported from the ramalama GGUF via
  `ollama create` (no download), has the best fact density.
- granite4.2 is a reasoning model with thinking on by default: 9-24 s per line until
  `quarkus.langchain4j.ollama.chat-model.model-options.think=false` dropped it to
  0.4-2.8 s. That setting is now in the config.
- Live race with granite4.2:3b: lead, milestone and finish lines all from the model
  in 0.6-2.8 s, correct facts, team names used. One line leaked a raw float
  ("2.75555555555555 percent"). Fixed at the source: the facts JSON now rounds
  distances to one decimal in `AiCommentator` (the model echoes the precision it
  receives) and the system prompt asks for at most one decimal; the verification race
  produced only clean numbers, including computed gaps. granite4:latest stays the
  one-line fallback for model trouble generally.
- granite4.2:3b is in the Ollama registry (2.2 GB), so a fresh machine still
  auto-provisions it; the ramalama import is only how this machine got it without a
  download. Pre-pull it once into `$OLLAMA_MODELS` before stage.

## Build order

1. Spike: MCP resource subscription from an external process — done, subscriptions work
   (see "Spike log"); Act 5 uses them with event filtering, polling as fallback.
2. Local inference: done. Ollama Dev Services auto-provision the model; granite4.2:3b
   with `think=false` carries the commentary (see "Spike log"). Pre-pull it once into
   `$OLLAMA_MODELS` on the stage laptop.
3. A2A layer: race-ops scaffold with MCP client → supervisor + three all-Quarkus
   specialists → `A2ACommentator` in wind-turbine → ops output panel → stub-based
   routing tests (jev-laya stub pattern).
4. Runtime spread: port analytics to Python, sabotage-detection to Spring Boot.
5. reShapr segment: local Docker Compose, import wind-turbine `/q/openapi`, one Custom
   Tool, measure the token shrink.
6. Optional: decision gate integration (Act 4).
7. Cluster: pre-warm OpenShift, OpenShift AI serving endpoint, race-ops manifests,
   staged secrets, rehearse the audience join and the Jaeger trace beat.
8. Then: run-of-show document (per-act timing, terminal commands, env files, fallback
   triggers), slide deck brief in the Slides workspace (existing pipeline,
   kubecon-japan-2026 style), rehearsal passes.

## Assets and roles

- `redhat-developer-demos/quinoa-wind-turbine` — spine; game, MCP server, AI
  commentary, OpenShift deploy. Quarkus 3.40.1.1, LC4J 1.15.0.CR1 through the
  quarkus-langchain4j-bom, quarkus-mcp-server 2.0.3, Quinoa 2.9.3.
- `kdubois/mcp-demo/netatmo` — typed tools deep-dive, McpAssured tests, OIDC admin
  tools. Knative/k8s manifests exist.
- `kdubois/mcp-demo/homebot` — MCP client + browser-as-MCP-client, JSON-RPC trace
  panel, guided MCP tour, env-based provider switch.
- `kdubois/a2a-agent-lab-2026` — pattern source for supervisor/A2AClientAgent/skills;
  Spring Boot and Python specialist templates. Quarkus 3.40.1, LC4J 1.15.0.CR1.
- `kdubois/quarkus-langchain4j-jev-laya` + `kdubois/kev-runtime` — optional Act 4;
  Quarkus 4.0.0.Beta1 / LC4J 2.0.0.Beta1 (frame as bleeding edge or align down).
- `kdubois/quarkus-kubernetes-kafka-db` — vanilla-k8s deploy patterns (backup path).
- `kdubois/quarkus-workshop-langchain4j` — audience take-home pointer.
- Showcase-only (closing): `kdubois/quarkus-langchain4j-source`,
  `kdubois/quarkus-agent-mcp`.
- `race-ops/` in this repo — multi-agent application, deploy scripts, run-of-show.
  Lives with the game as a subproject; nothing stays in a second repository.

## Risks and fallbacks

- Local model fails → template commentator (built into wind-turbine) + pre-recorded
  video.
- A2A mesh fails → pre-recorded video one keystroke away.
- Act 6 cluster fails → what is lost is the audience QR and live play. Fall back to the
  presenter's dashboard on the laptop plus rehearsal video. The cluster route is exercised
  in every rehearsal, so the failure mode is known before stage day.
- Audience joins mid-race → rehearse the trickle-in: commentary and leaderboard must look
  right while phones arrive over several minutes, not only with a full field.
- reShapr import fails → benchmark slide carries the point alone.
- Talk runs long → cut order: Act 4 (already optional) → compress Act 3 netatmo depth →
  quarkus-flow was already cut (third orchestration story).
- Version mismatch on stage: resolved — wind-turbine is aligned to the a2a lab's LC4J
  1.15.0.CR1 (imported through the quarkus-langchain4j-bom; both test suites and a live
  race verified after the upgrade, Ollama provider path included). 1.15.0.CR1 is the
  newest 1.x release; 2.0.0.Beta1 pairs with Quarkus 4 Beta and stays reserved for
  jev-laya, framed as bleeding edge.
- "Zero code changes" honesty: the prod profile must differ from dev in config only —
  build race-ops and the wind-turbine changes that way from day one.

## Open items

- OpenShift AI serving: same cluster vs external cluster (decide in rehearsal).
- Optional: decision gate (Act 4), vanilla-Kubernetes path.
- Where the cluster comes from (ROSA/ARO/self-managed) and who pays for it.
