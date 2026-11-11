#!/usr/bin/env bash
# Seeds the game's secrets into the shared dev-mode Vault (sub-task 8, step 4) and creates
# the policy and role the Vault Secrets Operator authenticates with.
#
#   scripts/seed-vault.sh [SOURCE_NAMESPACE]
#
# Values are read from the live namespace's Secrets (default: wind-turbine), so a re-seed
# after a Vault restart keeps the values the running deployments already use; a secret that
# does not exist there gets a generated value instead, except commentary-openai, which is
# optional and skipped when absent. No value is ever committed or printed.
#
# The Vault is dev-mode with the root token "root" (the Helm values in
# progressive-delivery's bootstrap/overlays/default/openshift-gitops-argocd.yaml) and holds
# nothing across a pod restart: run this again after one. The VaultStaticSecrets that sync
# the values into the cluster live in deploy/base; this script only writes Vault.
set -euo pipefail

SOURCE_NAMESPACE=${1:-wind-turbine}
VAULT_PORT=${VAULT_PORT:-8200}
ROOT_TOKEN=${ROOT_TOKEN:-root}

die() { echo "error: $*" >&2; exit 1; }
log() { echo "--> $*"; }

command -v oc >/dev/null || die "oc is required"
command -v python3 >/dev/null || die "python3 is required"
command -v curl >/dev/null || die "curl is required"
oc whoami >/dev/null 2>&1 || die "not logged in; run oc login first"

log "Port-forwarding to Vault in openshift-gitops"
oc port-forward svc/vault "$VAULT_PORT:8200" -n openshift-gitops >/dev/null 2>&1 &
PF=$!
trap 'kill $PF 2>/dev/null || true' EXIT
for _ in $(seq 1 30); do
  curl -sf -o /dev/null "http://127.0.0.1:$VAULT_PORT/v1/sys/health" && break
  sleep 1
done
curl -sf -o /dev/null "http://127.0.0.1:$VAULT_PORT/v1/sys/health" || die "Vault is not reachable on port $VAULT_PORT"

SOURCE_NAMESPACE=$SOURCE_NAMESPACE VAULT_ADDR="http://127.0.0.1:$VAULT_PORT" ROOT_TOKEN=$ROOT_TOKEN \
  python3 <<'PY'
import base64
import json
import os
import secrets
import string
import subprocess
import urllib.request

vault = os.environ["VAULT_ADDR"]
token = os.environ["ROOT_TOKEN"]
source = os.environ["SOURCE_NAMESPACE"]


def put(path, payload):
    request = urllib.request.Request(
        vault + "/v1/" + path,
        data=json.dumps(payload).encode(),
        headers={"X-Vault-Token": token, "Content-Type": "application/json"},
        method="PUT")
    urllib.request.urlopen(request)


def live(name):
    """The live Secret's decoded keys, or None when it does not exist."""
    result = subprocess.run(["oc", "get", "secret", name, "-n", source, "-o", "json"],
                            capture_output=True, text=True)
    if result.returncode:
        return None
    data = json.loads(result.stdout).get("data") or {}
    return {key: base64.b64decode(value).decode() for key, value in data.items()}


def random(count):
    return "".join(secrets.choice(string.ascii_letters + string.digits) for _ in range(count))


def store(name, values):
    put("secret/data/wind-turbine/" + name, {"data": values})
    print(f"    secret/wind-turbine/{name}: {', '.join(sorted(values))}")


admin = live("game-admin") or {"password": random(16)}
store("game-admin", admin)

session = live("game-session") or {"encryption-key": random(32)}
store("game-session", session)

infinispan = live("infinispan-credentials")
if infinispan is None:
    password = random(16)
    infinispan = {
        "username": "developer",
        "password": password,
        "identities.yaml": (
            "credentials:\n"
            "  - username: developer\n"
            f"    password: {password}\n"
            "    roles:\n"
            "      - deployer\n"),
    }
store("infinispan-credentials", infinispan)

commentary = live("commentary-openai")
if commentary:
    store("commentary-openai", commentary)
else:
    print("    secret/wind-turbine/commentary-openai: skipped (no live secret; optional)")

# The VaultAuth in deploy/base logs in as the namespace's default service account.
put("sys/policies/acl/wind-turbine", {
    "policy": 'path "secret/data/wind-turbine/*" { capabilities = ["read"] }'})
put("auth/kubernetes/role/wind-turbine", {
    "bound_service_account_names": ["default"],
    "bound_service_account_namespaces": ["wind-turbine", "wind-turbine-gitops"],
    "policies": ["wind-turbine"],
    "ttl": "1h"})
print("    role wind-turbine: default service account in wind-turbine and wind-turbine-gitops")
PY

log "Done. The VaultStaticSecrets in deploy/base sync these into the cluster"
