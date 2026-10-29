#!/usr/bin/env bash
# Runs the before/after measurement for the reShapr demo: the same question, the same model,
# once against the raw translation of the game's API and once against the reshaped
# get_race_momentum tool. Prints tool calls and token usage for both — the talk's own number,
# measured on our API instead of quoted from reShapr's benchmark.
#
# Run scripts/reshapr-setup.sh first (it creates and prints the two endpoints), start a race
# so the answer is real, then run this. The model environment is the game's: OPENAI_BASE_URL,
# OPENAI_API_KEY, OPENAI_MODEL.
set -euo pipefail

command -v reshapr >/dev/null || die "the reshapr CLI is not installed; run: npm install -g @reshapr/reshapr-cli"
command -v jbang >/dev/null || die "jbang is not installed"

die() { echo "ERROR: $*" >&2; exit 1; }

[ -n "${OPENAI_BASE_URL:-}" ] || die "set OPENAI_BASE_URL (the game's AI commentary uses the same)"
[ -n "${OPENAI_API_KEY:-}" ] || die "set OPENAI_API_KEY"

exposition_url() {
  reshapr expo list --output json | python3 -c "
import json, sys
expos = json.load(sys.stdin)
match = [e for e in expos if e['service']['name'] == '$1' and '$2' in e['name']]
print('http://localhost:7777/mcp/' + match[0]['id'] if match else '')"
}

RAW_URL=$(exposition_url "Wind Turbine Racer Raw" "default")
RESHAPED_URL=$(exposition_url "Wind Turbine Racer" "race-momentum")
[ -n "$RAW_URL" ] || die "no raw endpoint; run scripts/reshapr-setup.sh first"
[ -n "$RESHAPED_URL" ] || die "no reshaped endpoint; run scripts/reshapr-setup.sh first"

exec jbang "$(dirname "$0")/ReshaprMeasure.java" "raw=$RAW_URL" "reshaped=$RESHAPED_URL"
