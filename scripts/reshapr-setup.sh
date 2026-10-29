#!/usr/bin/env bash
# Sets up the reShapr face of the race demo: imports the game's OpenAPI contract as two
# services and exposes one MCP server for each, so the same question can be asked against
# both on stage:
#
#   - "Wind Turbine Racer Raw" — the naive translation, every operation becomes a tool with
#     its full JSON response. No artifact is ever attached to this service, so it stays the
#     honest "before" (attaching a Custom Tool to a service bleeds into its default plan,
#     because an absent artifact selection means "all attached artifacts apply").
#   - "Wind Turbine Racer" — the reshaped face: one scripted Custom Tool, get_race_momentum,
#     orchestrating the race state and leaderboard reads into a compact answer. The raw
#     operations stay callable inside the script but are hidden from the client.
#
# Prerequisites: the game running locally (./mvnw quarkus:dev with -Dquarkus.http.host=0.0.0.0
# so the containers can reach it), the reshapr CLI (npm install -g @reshapr/reshapr-cli), and
# a container runtime (podman or docker). Re-running the script is safe: imports and the
# artifact update in place, and the plans and expositions are recreated.
#
# The game must listen on the machine's LAN address: the reShapr containers run in a VM
# (podman on macOS), and host.containers.internal from those containers does not reach a
# dev-mode Quarkus bound to localhost.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
ARTIFACT="$REPO_DIR/reshapr/get-race-momentum.yaml"
SERVICE_VERSION="1.0"
RAW_SERVICE_NAME="Wind Turbine Racer Raw"
SHAPED_SERVICE_NAME="Wind Turbine Racer"
PLAN_NAME="race-momentum"
PROXY="http://localhost:7777/mcp"

log() { echo "==> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

command -v reshapr >/dev/null || die "the reshapr CLI is not installed; run: npm install -g @reshapr/reshapr-cli"
[ -f "$ARTIFACT" ] || die "missing $ARTIFACT"

# The address the reShapr containers use to call the game. On macOS the containers run in a
# VM behind the LAN address; host.containers.internal resolves but its forwarded connections
# are refused by a dev-mode Quarkus bound to localhost.
GAME_HOST=${GAME_HOST:-$(ipconfig getifaddr en0 2>/dev/null || true)}
[ -n "$GAME_HOST" ] || die "could not detect the LAN address; set GAME_HOST explicitly"
GAME_URL=${GAME_URL:-"http://$GAME_HOST:8080"}
curl -fsS --max-time 5 -o /dev/null "$GAME_URL/q/openapi" \
  || die "the game is not reachable at $GAME_URL (start it with -Dquarkus.http.host=0.0.0.0)"

if ! reshapr status >/dev/null 2>&1; then
  log "Starting reShapr locally (Docker Compose)"
  reshapr run
fi

log "Login to the local control plane"
reshapr login -s http://localhost:5555 -u admin -p password >/dev/null

service_id() {
  reshapr service list --output json | python3 -c "
import json, sys
services = json.load(sys.stdin)
match = [s for s in services if s['name'] == '$1' and s['version'] == '$SERVICE_VERSION']
print(match[0]['id'] if match else '')"
}

exposition_url() {
  # The proxy serves every exposition at /mcp/<id>; the list carries no endpoint URLs.
  reshapr expo list --output json | python3 -c "
import json, sys
expos = json.load(sys.stdin)
match = [e for e in expos if e['service']['name'] == '$1' and '$2' in e['name']]
print('$PROXY/' + match[0]['id'] if match else '')"
}

delete_exposition() {
  local id
  id=$(reshapr expo list --output json | python3 -c "
import json, sys
expos = json.load(sys.stdin)
match = [e for e in expos if e['service']['name'] == '$1' and '$2' in e['name']]
print(match[0]['id'] if match else '')")
  [ -z "$id" ] || reshapr expo delete "$id" >/dev/null
}

log "Import the raw translation: $RAW_SERVICE_NAME"
reshapr import -u "$GAME_URL/q/openapi" --backendEndpoint "$GAME_URL" \
  --sn "$RAW_SERVICE_NAME" --sv "$SERVICE_VERSION" >/dev/null
RAW_SERVICE_ID=$(service_id "$RAW_SERVICE_NAME")
[ -n "$RAW_SERVICE_ID" ] || die "the raw service was not discovered by the import"

log "Import the service to reshape: $SHAPED_SERVICE_NAME"
reshapr import -u "$GAME_URL/q/openapi" --backendEndpoint "$GAME_URL" >/dev/null
SHAPED_SERVICE_ID=$(service_id "$SHAPED_SERVICE_NAME")
[ -n "$SHAPED_SERVICE_ID" ] || die "the reshaped service was not discovered by the import"

log "Attach the get_race_momentum Custom Tool to $SHAPED_SERVICE_NAME (updates in place)"
reshapr attach -f "$ARTIFACT" --output json | python3 -c "import json, sys; print(json.load(sys.stdin)['name'])" >/dev/null

# Every raw operation is excluded from the reshaped plan: the scripted tool calls them
# internally (the allow-list gates that), but the client sees one tool. The list is read
# from the imported service so it stays correct when the API grows.
EXCLUDED_OPERATIONS=$(reshapr service get "$SHAPED_SERVICE_ID" --output json | python3 -c "
import json, sys
service = json.load(sys.stdin)
print(json.dumps([operation['name'] for operation in service['operations']]))")

log "Recreate the $PLAN_NAME configuration plan (one tool, nothing else)"
EXISTING_PLANS=$(reshapr config list --output json | python3 -c "
import json, sys
plans = json.load(sys.stdin)
print(' '.join(p['id'] for p in plans if p['name'] == '$PLAN_NAME' and p['serviceId'] == '$SHAPED_SERVICE_ID'))")
for plan in $EXISTING_PLANS; do
  reshapr config delete "$plan" --force >/dev/null
done
PLAN_ID=$(reshapr config create "$PLAN_NAME" \
  -s "$SHAPED_SERVICE_ID" \
  -d "The reshaped face: one agent-oriented tool" \
  --be "$GAME_URL" \
  --eo "$EXCLUDED_OPERATIONS" \
  --ia '["get-race-momentum.yaml"]' \
  --output json | python3 -c "import json, sys; print(json.load(sys.stdin)['id'])")
log "Plan $PLAN_NAME: $PLAN_ID"

log "Expose the reshaped plan; drop the auto-expositions so exactly two endpoints stay live"
delete_exposition "$SHAPED_SERVICE_NAME" "$PLAN_NAME"
delete_exposition "$SHAPED_SERVICE_NAME" "default"
reshapr expo create --configuration "$PLAN_ID" --gateway-group 1 --name "$PLAN_NAME" >/dev/null

RAW_URL=$(exposition_url "$RAW_SERVICE_NAME" "default")
RESHAPED_URL=$(exposition_url "$SHAPED_SERVICE_NAME" "$PLAN_NAME")
[ -n "$RAW_URL" ] || die "the raw exposition was not created by the import"
[ -n "$RESHAPED_URL" ] || die "the reshaped exposition was not created"

echo
log "Raw translation (every operation is a tool): $RAW_URL"
log "Reshaped (one get_race_momentum tool):        $RESHAPED_URL"
log "Ask the same question against both and compare; scripts/reshapr-measure.sh does exactly that."
