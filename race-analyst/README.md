# race-analyst

The engagement analyst for Race Day (see [../PLAN.md](../PLAN.md)). A separate Quarkus app that
race-ops calls over A2A when it needs to explain why a team is behind. There is no model here: the
numbers and the diagnosis are plain code, which is the point.

The game normalizes team distance by the number of players who tapped, so a team falls behind for
two different reasons. Its players can tap softer than the other team's, or some of them can pocket
the phone and stop. A stalled player still counts in the team average, so quitting costs the team
more than a low tap rate. The analyst separates those cases; the director's model only phrases the
answer.

## What the analyst computes

`EngagementAnalytics` compares two snapshots of the same race. For each team it reports the tappers
(players with at least one tap), the active players (power grew over the window), the stalled
players (power did not change), the engagement rate, total power and distance. A player who switched
teams starts over and counts as active.

`Diagnosis` turns the report into one sentence. It names quitting when at least 30% of a team's
tappers stalled, a headcount gap when that is the cause, a low active share when most of the team
stopped, and the softer-tap case otherwise. A gap under one point is level.

## A2A contract

The app serves JSON-RPC on `/` and its card on `/.well-known/agent-card.json` (port 8082). Callers
must send `A2A-Version: 1.0`; the SDK treats a missing version as the older protocol and rejects the
task.

Send one task with the two snapshots as JSON text:

```json
{"base": {"raceId": "race-1", ...}, "current": {"raceId": "race-1", ...}}
```

The artifact is the report plus the diagnosis:

```json
{"engagement": {"raceId": "race-1", "status": "STARTED", "elapsedMs": 6000,
                "teams": [{"team": 1, "position": 1, "tappers": 3, "active": 2,
                           "stalled": 1, "engagementRate": 0.67, "power": 340,
                           "distance": 55.0}]},
 "diagnosis": "Team 2 is 8.0 points behind because 2 of its 4 players stopped tapping."}
```

`base` can be null on the first snapshot of a race; active and stalled then come back as 0, because
there is nothing to compare against yet.

The JSON does not have to be the whole request. The service reads the first `{` to the last `}`, so a
sentence around the payload is accepted. An orchestrator handing the task over tends to add one, and
race-ops now goes through the supervisor rather than calling this app directly for that decision.

## Running

```bash
# the game must be up for race-ops, not for this app
./mvnw -f race-analyst/pom.xml quarkus:dev   # port 8082
```

Start order for the full mesh is the game, then race-analyst, then race-creative, then race-ops.
race-ops works without either specialist; it falls back to the in-process report and a preset
theme, and says so on the console.

## Configuration

`a2a.agent.url` is the URL published in the card. `a2a.authorization.required=false` is required for
this app: it holds no state and only race-ops reaches it, and without the flag the SDK answers every
task with "Task not found". The `openshift-cluster` profile disables the route and keeps port 8082,
so the caller's URL differs only by host.

## Tests

```bash
./mvnw -f race-analyst/pom.xml test   # 15 tests
```

`AnalystEndpointTest` boots the app and sends a real `SendMessage` task, the same shape race-ops
uses. `NoForeignImportsTest` fails the build if this app starts importing game or race-ops classes.
Unit tests cover the analytics and every diagnosis branch, with no broker, game or model.

## Cluster

`scripts/deploy-openshift.sh` deploys race-analyst before race-ops: one replica, no route, reached
over the cluster service DNS as `http://race-analyst:80/`. The container listens on 8082, but
Quarkus publishes the generated Service on port 80 and 3.40 has no setting to change it, so both
the advertised card URL and race-ops's `openshift-cluster` URL carry 80.
