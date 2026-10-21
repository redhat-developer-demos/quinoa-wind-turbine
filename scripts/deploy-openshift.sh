#!/usr/bin/env bash
#
# Deploy the game to OpenShift with Strimzi Kafka, in one re-runnable pass. Existing passwords and keys
# are read back and reused, so a re-run doesn't rotate the dashboard password, the session key or the
# Infinispan password. A finished realm import is left alone.
#
# Prerequisites: `oc login` as a cluster-admin, and the repo's Maven wrapper (./mvnw).
#
# Usage:
#   scripts/deploy-openshift.sh
#
# Settings, all optional:
#   NAMESPACE            namespace for the game (default wind-turbine)
#   KEYCLOAK_NAMESPACE   namespace of an existing Keycloak (default keycloak)
#   KEYCLOAK_CR          name of that Keycloak CR (default keycloak)
#   SKIP_KEYCLOAK=1      skip the realm import and deploy with OIDC off
#
# The script stops at the first failed step. It deletes nothing and never recreates the realm.
set -euo pipefail

cd "$(dirname "$0")/.."

NAMESPACE="${NAMESPACE:-wind-turbine}"
KEYCLOAK_NAMESPACE="${KEYCLOAK_NAMESPACE:-keycloak}"
KEYCLOAK_CR="${KEYCLOAK_CR:-keycloak}"
REGISTRY=image-registry.openshift-image-registry.svc:5000

log() { echo "==> $*"; }
die() { echo "error: $*" >&2; exit 1; }

# Prints the value of one key in a secret in the game namespace, or nothing when it's missing.
existing_secret() {
  oc get secret "$1" -n "$NAMESPACE" -o "jsonpath={.data.$2}" 2>/dev/null | base64 -d 2>/dev/null || true
}

# Waits until the operator behind a subscription is installed. The CRDs exist only after this.
wait_for_operator() {
  local subscription=$1 csv=""
  for _ in $(seq 1 60); do
    csv=$(oc get subscription "$subscription" -n openshift-operators -o jsonpath='{.status.installedCSV}' 2>/dev/null || true)
    [ -n "$csv" ] && break
    sleep 5
  done
  [ -n "$csv" ] || die "subscription $subscription has no installed CSV"
  oc wait "csv/$csv" -n openshift-operators --for=jsonpath='{.status.phase}'=Succeeded --timeout=300s
}

oc whoami >/dev/null 2>&1 || die "not logged in; run oc login first"

log "Operator subscriptions"
oc apply -f - <<'EOF'
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: amq-streams
  namespace: openshift-operators
spec:
  channel: stable
  name: amq-streams
  source: redhat-operators
  sourceNamespace: openshift-marketplace
  installPlanApproval: Automatic
---
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: infinispan
  namespace: openshift-operators
spec:
  channel: stable
  name: infinispan
  source: community-operators
  sourceNamespace: openshift-marketplace
  installPlanApproval: Automatic
EOF
wait_for_operator amq-streams
wait_for_operator infinispan

log "Namespace $NAMESPACE"
oc get project "$NAMESPACE" >/dev/null 2>&1 || oc new-project "$NAMESPACE" >/dev/null
oc project "$NAMESPACE" >/dev/null

log "App secrets (existing values are kept)"
GAME_ADMIN_PASSWORD=$(existing_secret game-admin password)
GAME_ADMIN_PASSWORD=${GAME_ADMIN_PASSWORD:-$(openssl rand -hex 8)}
SESSION_ENCRYPTION_KEY=$(existing_secret game-session encryption-key)
SESSION_ENCRYPTION_KEY=${SESSION_ENCRYPTION_KEY:-$(openssl rand -hex 16)}
oc process -f src/main/kubernetes/secrets.yaml \
  -p GAME_ADMIN_PASSWORD="$GAME_ADMIN_PASSWORD" \
  -p SESSION_ENCRYPTION_KEY="$SESSION_ENCRYPTION_KEY" \
  | oc apply -n "$NAMESPACE" -f -

log "Infinispan (existing password is kept)"
INFINISPAN_PASSWORD=$(existing_secret infinispan-credentials password)
INFINISPAN_PASSWORD=${INFINISPAN_PASSWORD:-$(openssl rand -hex 8)}
oc process -f src/main/kubernetes/infinispan/infinispan.yaml \
  -p INFINISPAN_PASSWORD="$INFINISPAN_PASSWORD" \
  | oc apply -n "$NAMESPACE" -f -
oc wait --for=condition=wellFormed --timeout=240s infinispan/infinispan -n "$NAMESPACE"

log "Kafka and topics"
oc apply -n "$NAMESPACE" -f src/main/kubernetes/openshift.cluster.yml
oc wait --for=condition=Ready --timeout=360s kafka/my-cluster -n "$NAMESPACE"

OIDC_ENABLED=false
KC_URL=""
if [ "${SKIP_KEYCLOAK:-0}" = 1 ] || ! oc get keycloak "$KEYCLOAK_CR" -n "$KEYCLOAK_NAMESPACE" >/dev/null 2>&1; then
  log "No Keycloak $KEYCLOAK_CR in $KEYCLOAK_NAMESPACE; the admin MCP tools will reject calls"
else
  log "Keycloak realm wind-turbine in $KEYCLOAK_NAMESPACE"
  KC_HOST=$(oc get route "$KEYCLOAK_CR" -n "$KEYCLOAK_NAMESPACE" -o jsonpath='{.spec.host}')
  KC_URL="https://$KC_HOST/realms/wind-turbine"
  OIDC_ENABLED=true

  # The realm import reads the developer password from this secret, so it must match the dashboard's.
  oc create secret generic game-admin -n "$KEYCLOAK_NAMESPACE" \
    --from-literal=password="$GAME_ADMIN_PASSWORD" --dry-run=client -o yaml \
    | oc apply -n "$KEYCLOAK_NAMESPACE" -f -

  # A finished import stays as it is. Re-applying it would change nothing, and reimporting the realm
  # would invalidate the cached OAuth client of any MCP agent.
  import_done=$(oc get keycloakrealmimport wind-turbine -n "$KEYCLOAK_NAMESPACE" \
    -o jsonpath='{.status.conditions[?(@.type=="Done")].status}' 2>/dev/null || true)
  if [ "$import_done" = True ]; then
    log "Realm wind-turbine is already imported"
  else
    oc process -f src/main/kubernetes/keycloak/realm-import.yaml -p KEYCLOAK_CR_NAME="$KEYCLOAK_CR" \
      | oc apply -n "$KEYCLOAK_NAMESPACE" -f -
    oc wait --for=jsonpath='{.status.conditions[?(@.type=="Done")].status}'=True \
      --timeout=240s keycloakrealmimport/wind-turbine -n "$KEYCLOAK_NAMESPACE"
  fi
fi

# The s2i build replaces the Deployment, which drops variables set with `oc set env`. Baking the OIDC
# settings into the build keeps them across every redeploy.
OIDC_ARGS="-Dquarkus.openshift.env.vars.game-oidc-enabled=$OIDC_ENABLED"
if [ "$OIDC_ENABLED" = true ]; then
  OIDC_ARGS="$OIDC_ARGS -Dquarkus.openshift.env.vars.game-oidc-url=$KC_URL"
fi

log "Build and deploy the app"
# $OIDC_ARGS is unquoted on purpose: it holds several -D options, and none contains a space.
./mvnw package -Dquarkus.profile=openshift-cluster -Dquarkus.kubernetes.deploy=true -DskipTests \
  -Dquarkus.container-image.registry="$REGISTRY" \
  -Dquarkus.container-image.group="$NAMESPACE" \
  $OIDC_ARGS

log "Wait for the rollout"
oc rollout status deployment/quinoa-wind-turbine -n "$NAMESPACE" --timeout=600s

APP_HOST=$(oc get route quinoa-wind-turbine -n "$NAMESPACE" -o jsonpath='{.spec.host}')
log "Health check https://$APP_HOST/q/health/ready"
for _ in $(seq 1 30); do
  if curl -fsS "https://$APP_HOST/q/health/ready" | grep -Eq '"status": ?"UP"'; then
    echo "App is up: https://$APP_HOST"
    echo "Dashboard password: oc get secret game-admin -n $NAMESPACE -o jsonpath='{.data.password}' | base64 -d"
    exit 0
  fi
  sleep 10
done
die "health check failed for https://$APP_HOST"
