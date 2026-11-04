#!/usr/bin/env bash
#
# Deploy the game to OpenShift with Strimzi Kafka, in one re-runnable pass. Existing passwords and keys
# are read back and reused, so a re-run doesn't rotate the dashboard password, the session key or the
# Infinispan password. An existing realm is left alone.
#
# Prerequisites: `oc login` as a cluster-admin, the repo's Maven wrapper (./mvnw), and python3 for the
# one JSON rewrite that creates the Keycloak realm.
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
RACE_OPS_CLIENT_SECRET=$(existing_secret race-ops-client secret)
RACE_OPS_CLIENT_SECRET=${RACE_OPS_CLIENT_SECRET:-$(openssl rand -hex 16)}
oc process -f src/main/kubernetes/secrets.yaml \
  -p GAME_ADMIN_PASSWORD="$GAME_ADMIN_PASSWORD" \
  -p SESSION_ENCRYPTION_KEY="$SESSION_ENCRYPTION_KEY" \
  -p RACE_OPS_CLIENT_SECRET="$RACE_OPS_CLIENT_SECRET" \
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

  # And the race-ops client secret, so the confidential client and the deployment always match.
  oc create secret generic race-ops-client -n "$KEYCLOAK_NAMESPACE" \
    --from-literal=secret="$RACE_OPS_CLIENT_SECRET" --dry-run=client -o yaml \
    | oc apply -n "$KEYCLOAK_NAMESPACE" -f -

  # The realm is created through the Admin API, not with the operator's KeycloakRealmImport. That
  # import job writes the realm straight into Keycloak's database, and the master admin then holds
  # none of the realm's per-realm admin roles (<realm>-realm client), so every Admin API call
  # against the realm answers 403 — including the race-ops client this script has to create, which
  # made the whole deploy unusable. A realm created through the API grants its creator exactly
  # those roles. The realm content still lives in realm-import.yaml under spec.realm, the single
  # source of truth, and the file stays usable as a KeycloakRealmImport for a Keycloak whose admin
  # API is not reachable.
  KC_ADMIN_USER=$(oc get secret keycloak-initial-admin -n "$KEYCLOAK_NAMESPACE" \
    -o jsonpath='{.data.username}' | base64 -d)
  KC_ADMIN_PASSWORD=$(oc get secret keycloak-initial-admin -n "$KEYCLOAK_NAMESPACE" \
    -o jsonpath='{.data.password}' | base64 -d)
  KC_TOKEN=$(curl -fsS "https://$KC_HOST/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli \
    -d username="$KC_ADMIN_USER" -d password="$KC_ADMIN_PASSWORD" \
    | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4) || true
  [ -n "${KC_TOKEN:-}" ] || die "could not get a Keycloak admin token on $KC_HOST"

  REALM_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $KC_TOKEN" \
    "https://$KC_HOST/admin/realms/wind-turbine") || true
  case "$REALM_STATUS" in
    200)
      log "Realm wind-turbine exists"
      ;;
    404)
      # oc process keeps the ${GAME_ADMIN_PASSWORD} and ${RACE_OPS_CLIENT_SECRET} placeholders, which
      # the operator would resolve for a KeycloakRealmImport. The Admin API needs them filled in.
      log "Create realm wind-turbine"
      REALM_JSON=$(oc process -f src/main/kubernetes/keycloak/realm-import.yaml \
          -p KEYCLOAK_CR_NAME="$KEYCLOAK_CR" -o json \
        | GAME_ADMIN_PASSWORD="$GAME_ADMIN_PASSWORD" RACE_OPS_CLIENT_SECRET="$RACE_OPS_CLIENT_SECRET" \
          python3 -c 'import json, os, sys
realm = json.dumps(json.load(sys.stdin)["items"][0]["spec"]["realm"])
realm = realm.replace("${GAME_ADMIN_PASSWORD}", os.environ["GAME_ADMIN_PASSWORD"])
realm = realm.replace("${RACE_OPS_CLIENT_SECRET}", os.environ["RACE_OPS_CLIENT_SECRET"])
sys.stdout.write(realm)')
      curl -fsS -o /dev/null -H "Authorization: Bearer $KC_TOKEN" -H "Content-Type: application/json" \
        -X POST "https://$KC_HOST/admin/realms" -d "$REALM_JSON" \
        || die "could not create the wind-turbine realm"
      ;;
    403)
      die "the wind-turbine realm exists but $KC_ADMIN_USER cannot administer it; a realm imported through KeycloakRealmImport gets no per-realm admin roles, so delete it in Keycloak and re-run"
      ;;
    *)
      die "unexpected answer ($REALM_STATUS) reading the wind-turbine realm on $KC_HOST"
      ;;
  esac

  # The race-ops client and its service account are ensured here, not only imported: a realm that
  # predates this script is missing the client, and Keycloak's single-file import skips existing
  # realms wholesale (the job logs "Realm already exists. Import skipped"), so re-applying the CR
  # adds nothing. The role cannot live in the realm file at all: Keycloak imports users before
  # clients, so a declared service-account-race-ops user collides with the one creating the client
  # makes and the whole import fails on (realm_id, username). Both Admin API steps are additive and
  # idempotent, so this runs for a realm imported just now and for one that predates the script,
  # adding no re-authentication for MCP agents that registered their own clients dynamically.
  KC_ADMIN_USER=$(oc get secret keycloak-initial-admin -n "$KEYCLOAK_NAMESPACE" \
    -o jsonpath='{.data.username}' | base64 -d)
  KC_ADMIN_PASSWORD=$(oc get secret keycloak-initial-admin -n "$KEYCLOAK_NAMESPACE" \
    -o jsonpath='{.data.password}' | base64 -d)
  KC_TOKEN=$(curl -fsS "https://$KC_HOST/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli \
    -d username="$KC_ADMIN_USER" -d password="$KC_ADMIN_PASSWORD" \
    | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4) || true
  [ -n "${KC_TOKEN:-}" ] || die "could not get a Keycloak admin token on $KC_HOST"

  RACE_OPS_CLIENT_IN_REALM=$(curl -fsS -H "Authorization: Bearer $KC_TOKEN" \
    "https://$KC_HOST/admin/realms/wind-turbine/clients?clientId=race-ops" | grep -o '"id"' || true)
  if [ -z "$RACE_OPS_CLIENT_IN_REALM" ]; then
    log "Creating the race-ops client in the realm (Admin API, additive)"
    RACE_OPS_CLIENT_JSON=$(cat <<EOF
{"clientId":"race-ops","name":"Race director","enabled":true,"publicClient":false,
"standardFlowEnabled":false,"directAccessGrantsEnabled":false,"serviceAccountsEnabled":true,
"secret":"$RACE_OPS_CLIENT_SECRET",
"protocolMappers":[{"name":"wind-turbine-audience","protocol":"openid-connect",
"protocolMapper":"oidc-audience-mapper","consentRequired":false,
"config":{"included.client.audience":"wind-turbine","id.token.claim":"false",
"access.token.claim":"true","introspection.token.claim":"true"}}]}
EOF
)
    curl -fsS -o /dev/null -H "Authorization: Bearer $KC_TOKEN" -H "Content-Type: application/json" \
      -X POST "https://$KC_HOST/admin/realms/wind-turbine/clients" \
      -d "$RACE_OPS_CLIENT_JSON" || die "could not create the race-ops Keycloak client"
  fi

  # Creating the client with service accounts also creates its service-account user; the grant
  # itself is a no-op when the role is already mapped.
  SA_USER_ID=""
  for attempt in 1 2 3 4 5; do
    SA_USER_ID=$(curl -fsS -H "Authorization: Bearer $KC_TOKEN" \
      "https://$KC_HOST/admin/realms/wind-turbine/users?username=service-account-race-ops" \
      | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    [ -n "$SA_USER_ID" ] && break
    sleep 2
  done
  [ -n "$SA_USER_ID" ] || die "the race-ops service account user did not appear in Keycloak"
  ADMIN_ROLE_ID=$(curl -fsS -H "Authorization: Bearer $KC_TOKEN" \
    "https://$KC_HOST/admin/realms/wind-turbine/roles/admin" \
    | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4) || true
  [ -n "$ADMIN_ROLE_ID" ] || die "the wind-turbine realm has no admin role to grant"
  # The role-mapping endpoint takes an array of role representations, not one object.
  curl -fsS -o /dev/null -H "Authorization: Bearer $KC_TOKEN" -H "Content-Type: application/json" \
    -X POST "https://$KC_HOST/admin/realms/wind-turbine/users/$SA_USER_ID/role-mappings/realm" \
    -d "[{\"id\":\"$ADMIN_ROLE_ID\",\"name\":\"admin\"}]" \
    || die "could not grant the admin role to the race-ops service account"
fi

# The s2i build replaces the Deployment, which drops variables set with `oc set env`. Baking the OIDC
# settings into the build keeps them across every redeploy.
OIDC_ARGS="-Dquarkus.openshift.env.vars.game-oidc-enabled=$OIDC_ENABLED"
if [ "$OIDC_ENABLED" = true ]; then
  OIDC_ARGS="$OIDC_ARGS -Dquarkus.openshift.env.vars.game-oidc-url=$KC_URL"
fi

# The dashboard's director console: on when OIDC is (the console is pointless without the admin
# tools), reached same-origin through the path route that carries /ops to race-ops.
OPS_ARGS="-Dquarkus.openshift.env.vars.game-ops-enabled=$OIDC_ENABLED -Dquarkus.openshift.env.vars.game-ops-url=/ops"

log "Build and deploy the app"
# $OIDC_ARGS is unquoted on purpose: it holds several -D options, and none contains a space.
./mvnw package -Dquarkus.profile=openshift-cluster -Dquarkus.kubernetes.deploy=true -DskipTests \
  -Dquarkus.container-image.registry="$REGISTRY" \
  -Dquarkus.container-image.group="$NAMESPACE" \
  $OIDC_ARGS $OPS_ARGS

# The s2i build runs asynchronously, so a failed build does not fail the Maven deploy above; the
# rollout below would then just re-deploy the previous image and this script would report success.
# Fail loudly instead. The binary build streams the project directory, so a failed build is usually
# a broken source upload: keep the tree lean (mvn clean, no local .quinoa or node_modules).
log "Wait for the image build"
BUILD=$(oc get builds -n "$NAMESPACE" -l buildconfig=quinoa-wind-turbine \
  --sort-by=.metadata.creationTimestamp -o name | tail -1)
[ -n "$BUILD" ] || die "no build was triggered by the deploy"
oc wait --for=condition=Complete "$BUILD" -n "$NAMESPACE" --timeout=900s \
  || die "image build $BUILD did not complete; check 'oc logs $BUILD -n $NAMESPACE'"

log "Wait for the rollout"
oc rollout status deployment/quinoa-wind-turbine -n "$NAMESPACE" --timeout=600s

# The build replaced the Deployment, so the model credentials applied with oc set env are gone.
# Re-apply them to both deployments; without them the commentary falls back to template lines.
if oc get secret commentary-openai -n "$NAMESPACE" >/dev/null 2>&1; then
  log "Model credentials from secret commentary-openai"
  oc set env deployment/quinoa-wind-turbine -n "$NAMESPACE" --from=secret/commentary-openai
  # game.ai.enabled gates the game's own commentary; the token and endpoint come from the secret.
  oc set env deployment/quinoa-wind-turbine -n "$NAMESPACE" GAME_AI_ENABLED=true
  # Setting env changes the pod template, so this starts another rollout. Wait for it here as well,
  # or the health check below can pass against the previous pods.
  oc rollout status deployment/quinoa-wind-turbine -n "$NAMESPACE" --timeout=600s
else
  log "WARNING: no secret commentary-openai; the game and the director run without a model"
fi

if [ "$OIDC_ENABLED" = true ]; then
  log "Build and deploy race-ops"
  APP_HOST=$(oc get route quinoa-wind-turbine -n "$NAMESPACE" -o jsonpath='{.spec.host}')
  # The director talks to the game through its source-balanced route, so the MCP sessions stay
  # on one of the five pods. OIDC comes from the same Keycloak as the game's admin login.
  ./mvnw -f race-ops/pom.xml package -Dquarkus.profile=openshift-cluster \
    -Dquarkus.kubernetes.deploy=true -DskipTests \
    -Dquarkus.container-image.registry="$REGISTRY" \
    -Dquarkus.container-image.group="$NAMESPACE" \
    -Dquarkus.openshift.env.vars.quarkus-langchain4j-mcp-race-url="https://$APP_HOST/mcp" \
    -Dquarkus.openshift.env.vars.quarkus-langchain4j-mcp-race-admin-url="https://$APP_HOST/admin/mcp" \
    -Dquarkus.openshift.env.vars.quarkus-oidc-client-auth-server-url="$KC_URL" \
    -Dquarkus.openshift.env.vars.race-ops-ai-enabled=true

  log "Wait for the race-ops image build"
  ROPS_BUILD=$(oc get builds -n "$NAMESPACE" -l buildconfig=race-ops \
    --sort-by=.metadata.creationTimestamp -o name | tail -1)
  [ -n "$ROPS_BUILD" ] || die "no race-ops build was triggered by the deploy"
  oc wait --for=condition=Complete "$ROPS_BUILD" -n "$NAMESPACE" --timeout=900s \
    || die "race-ops image build $ROPS_BUILD did not complete; check 'oc logs $ROPS_BUILD -n $NAMESPACE'"

  log "Wait for the race-ops rollout"
  oc rollout status deployment/race-ops -n "$NAMESPACE" --timeout=600s

  if oc get secret commentary-openai -n "$NAMESPACE" >/dev/null 2>&1; then
    oc set env deployment/race-ops -n "$NAMESPACE" --from=secret/commentary-openai
    oc rollout status deployment/race-ops -n "$NAMESPACE" --timeout=600s
  fi

  # The director's path route: the game's host carries /ops to the race-ops service, so the
  # dashboard console fetches it same-origin.
  log "Director route under the game's host"
  oc process -f src/main/kubernetes/race-ops-route.yaml -p GAME_HOST="$APP_HOST" \
    | oc apply -n "$NAMESPACE" -f -
else
  log "OIDC is off; skipping race-ops (the director needs the admin tools)"
fi

APP_HOST=$(oc get route quinoa-wind-turbine -n "$NAMESPACE" -o jsonpath='{.spec.host}')
log "Health check https://$APP_HOST/q/health/ready"
for _ in $(seq 1 30); do
  if curl -fsS "https://$APP_HOST/q/health/ready" | grep -Eq '"status": ?"UP"'; then
    echo "App is up: https://$APP_HOST"
    echo "Dashboard password: oc get secret game-admin -n $NAMESPACE -o jsonpath='{.data.password}' | base64 -d"
    # Both metrics endpoints must answer, since a path under the wrong root would only show up
    # when a scrape runs. The game serves /metrics at its root, race-ops under its /ops prefix.
    # Download first: grep -q closes the pipe early, and pipefail would turn curl's SIGPIPE into
    # a failed deploy on a response that is larger than the pipe buffer.
    log "Metrics endpoints"
    METRICS_TMP=$(mktemp)
    curl -fsS -o "$METRICS_TMP" "https://$APP_HOST/metrics" \
      || die "https://$APP_HOST/metrics did not answer"
    grep -q '^jvm_' "$METRICS_TMP" || die "https://$APP_HOST/metrics returned no JVM metrics"
    if [ "$OIDC_ENABLED" = true ]; then
      curl -fsS -o "$METRICS_TMP" "https://$APP_HOST/ops/metrics" \
        || die "https://$APP_HOST/ops/metrics did not answer"
      grep -q '^jvm_' "$METRICS_TMP" || die "https://$APP_HOST/ops/metrics returned no JVM metrics"
      # Without the OIDC tenant the admin server answers 403; 401 means it is up and asks for a token.
      ADMIN_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -X POST "https://$APP_HOST/admin/mcp")
      [ "$ADMIN_STATUS" = 401 ] \
        || die "POST /admin/mcp answered $ADMIN_STATUS, expected 401; is OIDC enabled on the game?"
    fi
    rm -f "$METRICS_TMP"
    echo "Director page: https://$APP_HOST/ops/ops.html"
    exit 0
  fi
  sleep 10
done
die "health check failed for https://$APP_HOST"
