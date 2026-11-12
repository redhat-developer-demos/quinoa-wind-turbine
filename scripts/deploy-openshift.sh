#!/usr/bin/env bash
#
# Deploy the game to OpenShift with Strimzi Kafka, in one re-runnable pass. Existing passwords and keys
# are read back and reused, so a re-run doesn't rotate the dashboard password, the session key or the
# Infinispan password. An existing realm is left alone.
#
# This is the fallback path. The live wind-turbine namespace is deployed from Git instead: the Argo CD
# pointer applies deploy/overlays/openshift-cluster, and the realm content is reconciled by the hook Job
# in deploy/base/keycloak-hook.yaml. The script builds the images on the cluster with s2i and patches the
# Deployments afterwards, which is what a from-scratch install on a cluster without that GitOps setup
# still needs. A fix made here does not reach the live namespace: change the overlay instead.
#
# Prerequisites: `oc login` as a cluster-admin, the repo's Maven wrapper (./mvnw), and python3 for the
# JSON handling of the Keycloak Admin API calls.
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

# Projects a service account token for Keycloak into a deployment's pod. The audience is the realm
# URL, which is what Keycloak's Kubernetes identity provider requires, and Quarkus' OIDC client
# reloads the file as the token nears expiry. The service account itself comes from
# quarkus.openshift.service-account in the app's configuration. The Quarkus OIDC client insists on
# a client id, so the callers set it to the service account's subject, which Keycloak accepts as the
# client's external identity (the internal id, race-ops, is rejected as an unknown client). A strategic merge patch is
# idempotent, but a later Maven deploy replaces the Deployment, so run this after every deploy.
mount_keycloak_token() {
  local name=$1
  oc patch deployment "$name" -n "$NAMESPACE" -p "{\"spec\":{\"template\":{\"spec\":{\"volumes\":[{\"name\":\"keycloak-token\",\"projected\":{\"sources\":[{\"serviceAccountToken\":{\"audience\":\"$KC_URL\",\"expirationSeconds\":600,\"path\":\"token\"}}]}}],\"containers\":[{\"name\":\"$name\",\"volumeMounts\":[{\"name\":\"keycloak-token\",\"mountPath\":\"/var/run/secrets/tokens\",\"readOnly\":true}]}]}}}}"
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

# Waits for the asynchronous s2i build of an app, then for its rollout. A build that fails does not
# fail the Maven deploy; the rollout would then just re-deploy the previous image and the script
# would report success. Fail loudly instead. The binary build streams the project directory, so a
# failed build is usually a broken source upload: keep the tree lean (mvn clean, no local .quinoa
# or node_modules).
wait_for_build_and_rollout() {
  local name=$1 build
  log "Wait for the $name image build"
  build=$(oc get builds -n "$NAMESPACE" -l "buildconfig=$name" \
    --sort-by=.metadata.creationTimestamp -o name | tail -1)
  [ -n "$build" ] || die "no $name build was triggered by the deploy"
  oc wait --for=condition=Complete "$build" -n "$NAMESPACE" --timeout=900s \
    || die "$name image build $build did not complete; check 'oc logs $build -n $NAMESPACE'"
  log "Wait for the $name rollout"
  oc rollout status "deployment/$name" -n "$NAMESPACE" --timeout=600s
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

# How race-ops proves its identity to Keycloak: "kubernetes" (the projected service account token,
# the default) or "spiffe" (a SPIFFE JWT-SVID from SPIRE). "spiffe" needs the zero trust workload
# identity manager from src/main/kubernetes/spire and Keycloak's preview SPIFFE feature
# (spec.features.enabled: [spiffe] in the Keycloak CR). Switching back is a re-run with the default.
RACE_OPS_AUTH=${RACE_OPS_AUTH:-kubernetes}
case "$RACE_OPS_AUTH" in kubernetes|spiffe) ;; *) die "RACE_OPS_AUTH must be kubernetes or spiffe" ;; esac

# Whether race-ops and the two specialists talk A2A over mTLS with SPIFFE X.509 SVIDs ("true"), and
# the specialists then accept only race-ops' SPIFFE ID, or over plain HTTP inside the cluster
# ("false", the default). It needs the zero trust workload identity manager from
# src/main/kubernetes/spire, and it is a build-time choice: the specialists' mTLS settings live in
# the `mtls` profile, which is built in next to openshift-cluster. Switching back is a re-run
# without it.
A2A_MTLS=${A2A_MTLS:-false}
case "$A2A_MTLS" in true|false) ;; *) die "A2A_MTLS must be true or false" ;; esac
APP_PROFILE=openshift-cluster
A2A_ALLOWED_CALLER=""
if [ "$A2A_MTLS" = true ]; then
  APP_PROFILE=openshift-cluster,mtls
  A2A_TRUST_DOMAIN=$(oc get zerotrustworkloadidentitymanager cluster -o jsonpath='{.spec.trustDomain}') \
    || die "A2A_MTLS=true needs the zero trust workload identity manager (src/main/kubernetes/spire)"
  A2A_ALLOWED_CALLER="ns/$NAMESPACE/sa/race-ops"
fi

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
      # oc process keeps the ${GAME_ADMIN_PASSWORD} placeholder, which the operator would resolve
      # for a KeycloakRealmImport. The Admin API needs it filled in.
      log "Create realm wind-turbine"
      REALM_JSON=$(oc process -f src/main/kubernetes/keycloak/realm-import.yaml \
          -p KEYCLOAK_CR_NAME="$KEYCLOAK_CR" -o json \
        | GAME_ADMIN_PASSWORD="$GAME_ADMIN_PASSWORD" \
          python3 -c 'import json, os, sys
realm = json.dumps(json.load(sys.stdin)["items"][0]["spec"]["realm"])
realm = realm.replace("${GAME_ADMIN_PASSWORD}", os.environ["GAME_ADMIN_PASSWORD"])
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

  # race-ops and race-creative authenticate to Keycloak with the service account token OpenShift
  # projects into their pods (federated client authentication), so there is no client secret to
  # create, store or rotate. The pieces are ensured here through the Admin API, not only imported:
  # they depend on this cluster's service account issuer, a realm that predates this script lacks
  # them, and Keycloak's single-file import skips existing realms wholesale (the job logs "Realm
  # already exists. Import skipped"). The clients' service-account users can't live in the realm
  # file either: Keycloak imports users before clients, so a declared service-account user collides
  # with the one creating the client makes. Every step is additive and idempotent, so this runs for
  # a realm created just now and for one that predates the script, adding no re-authentication for
  # MCP agents that registered their own clients dynamically.
  KC_ADMIN_USER=$(oc get secret keycloak-initial-admin -n "$KEYCLOAK_NAMESPACE" \
    -o jsonpath='{.data.username}' | base64 -d)
  KC_ADMIN_PASSWORD=$(oc get secret keycloak-initial-admin -n "$KEYCLOAK_NAMESPACE" \
    -o jsonpath='{.data.password}' | base64 -d)
  KC_TOKEN=$(curl -fsS "https://$KC_HOST/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli \
    -d username="$KC_ADMIN_USER" -d password="$KC_ADMIN_PASSWORD" \
    | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4) || true
  [ -n "${KC_TOKEN:-}" ] || die "could not get a Keycloak admin token on $KC_HOST"

  # Admin API call against the wind-turbine realm: kc METHOD PATH [curl options]. -f turns an
  # HTTP error into a failed command, so a caller can test for a missing resource.
  kc() {
    local method=$1 path=$2
    shift 2
    curl -fsS -H "Authorization: Bearer $KC_TOKEN" -H "Content-Type: application/json" \
      -X "$method" "https://$KC_HOST/admin/realms/wind-turbine$path" "$@"
  }

  # Keycloak trusts this cluster as an issuer of client assertions. The issuer is the one the
  # cluster stamps into its service account tokens, and Keycloak reads the keys from the cluster's
  # own discovery endpoint with its pod's token, which works because it runs in this cluster.
  SA_ISSUER=$(oc get --raw /.well-known/openid-configuration \
    | python3 -c 'import json, sys; print(json.load(sys.stdin)["issuer"])') \
    || die "could not read the cluster's service account issuer"
  if ! kc GET /identity-provider/instances/openshift-sa -o /dev/null 2>/dev/null; then
    log "Kubernetes identity provider openshift-sa (issuer $SA_ISSUER)"
    kc POST /identity-provider/instances -o /dev/null -d "{\"alias\":\"openshift-sa\",\"providerId\":\"kubernetes\",\"displayName\":\"OpenShift service accounts\",\"enabled\":true,\"config\":{\"issuer\":\"$SA_ISSUER\",\"issuerDiscoveryUrl\":\"https://kubernetes.default.svc\"}}" \
      || die "could not create the Kubernetes identity provider"
  fi

  # With RACE_OPS_AUTH=spiffe, Keycloak also trusts SPIRE as an issuer of client assertions. A SPIFFE
  # identity provider has no issuer: it names a trust domain and the bundle endpoint it reads the
  # signing keys from, here the JWKS the operator's OIDC discovery provider serves. The assertion
  # carries the SPIFFE ID as its subject, and Keycloak takes the trust domain from that.
  SPIFFE_SUB=""
  if [ "$RACE_OPS_AUTH" = spiffe ]; then
    SPIRE_TRUST_DOMAIN=$(oc get zerotrustworkloadidentitymanager cluster -o jsonpath='{.spec.trustDomain}') \
      || die "RACE_OPS_AUTH=spiffe needs the zero trust workload identity manager (src/main/kubernetes/spire)"
    SPIRE_BUNDLE_HOST=$(oc get route spire-oidc-discovery-provider -n zero-trust-workload-identity-manager \
      -o jsonpath='{.spec.host}') || die "could not read the SPIRE OIDC discovery route"
    SPIFFE_SUB="spiffe://$SPIRE_TRUST_DOMAIN/ns/$NAMESPACE/sa/race-ops"
    if ! kc GET /identity-provider/instances/spire -o /dev/null 2>/dev/null; then
      log "SPIFFE identity provider spire (trust domain $SPIRE_TRUST_DOMAIN)"
      kc POST /identity-provider/instances -o /dev/null -d "{\"alias\":\"spire\",\"providerId\":\"spiffe\",\"displayName\":\"SPIRE workload identity\",\"enabled\":true,\"config\":{\"trustDomain\":\"spiffe://$SPIRE_TRUST_DOMAIN\",\"bundleEndpoint\":\"https://$SPIRE_BUNDLE_HOST/keys\"}}" \
        || die "could not create the SPIFFE identity provider; is the spiffe feature enabled in the Keycloak CR?"
    fi
  fi

  # A realm created before federated client authentication existed has a client authentication
  # flow without the "Signed JWT - Federated" step, and a built-in flow can't be edited, so copy it,
  # add the step and bind the copy.
  CLIENT_FLOW=$(kc GET "" | python3 -c 'import json, sys; print(json.load(sys.stdin)["clientAuthenticationFlow"])') \
    || die "could not read the realm's client authentication flow"
  CLIENT_FLOW_EXECUTIONS=$(kc GET "/authentication/flows/$CLIENT_FLOW/executions") \
    || die "could not read the client authentication flow $CLIENT_FLOW"
  if ! grep -q '"providerId":"federated-jwt"' <<<"$CLIENT_FLOW_EXECUTIONS"; then
    log "Client authentication flow with Signed JWT - Federated"
    kc POST "/authentication/flows/$CLIENT_FLOW/copy" -o /dev/null -d '{"newName":"clients-federated"}' \
      || die "could not copy the client authentication flow"
    kc POST /authentication/flows/clients-federated/executions/execution -o /dev/null \
      -d '{"provider":"federated-jwt"}' || die "could not add the federated step to the flow"
    FEDERATED_EXECUTION_ID=$(kc GET /authentication/flows/clients-federated/executions | python3 -c '
import json, sys
print(next(e["id"] for e in json.load(sys.stdin) if e["providerId"] == "federated-jwt"))') \
      || die "the federated step did not appear in the flow"
    kc PUT /authentication/flows/clients-federated/executions -o /dev/null \
      -d "{\"id\":\"$FEDERATED_EXECUTION_ID\",\"requirement\":\"ALTERNATIVE\"}" \
      || die "could not enable the federated step"
    kc GET "" | python3 -c '
import json, sys
realm = json.load(sys.stdin)
realm["clientAuthenticationFlow"] = "clients-federated"
sys.stdout.write(json.dumps(realm))' \
      | kc PUT "" -o /dev/null -d @- || die "could not bind the client authentication flow"
  fi

  # Creates or converts the confidential client of one agent: no secret, it authenticates with the
  # token of the OpenShift service account of the same name (or, given an identity provider alias
  # and a subject, with that provider's), and its own service account holds the admin role the
  # game's MCP server requires. The PUT replaces the protocol mappers, so a re-run leaves exactly one.
  ensure_federated_client() {
    local client_id=$1 display_name=$2 body client_uuid sa_user_id role_id
    local idp=${3:-openshift-sa} subject=${4:-system:serviceaccount:$NAMESPACE:$1}
    body=$(cat <<EOF
{"clientId":"$client_id","name":"$display_name","enabled":true,"publicClient":false,
"standardFlowEnabled":false,"directAccessGrantsEnabled":false,"serviceAccountsEnabled":true,
"clientAuthenticatorType":"federated-jwt",
"attributes":{"jwt.credential.issuer":"$idp",
"jwt.credential.sub":"$subject"},
"protocolMappers":[{"name":"wind-turbine-audience","protocol":"openid-connect",
"protocolMapper":"oidc-audience-mapper","consentRequired":false,
"config":{"included.client.audience":"wind-turbine","id.token.claim":"false",
"access.token.claim":"true","introspection.token.claim":"true"}}]}
EOF
)
    client_uuid=$(kc GET "/clients?clientId=$client_id" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
    if [ -z "$client_uuid" ]; then
      log "Creating the federated client $client_id (Admin API, additive)"
      kc POST /clients -o /dev/null -d "$body" || die "could not create the $client_id Keycloak client"
    else
      log "Making the Keycloak client $client_id federated"
      kc PUT "/clients/$client_uuid" -o /dev/null -d "$body" || die "could not update the $client_id Keycloak client"
    fi

    # Creating the client with service accounts also creates its service-account user; the grant
    # itself is a no-op when the role is already mapped.
    sa_user_id=""
    for _ in 1 2 3 4 5; do
      sa_user_id=$(kc GET "/users?username=service-account-$client_id" \
        | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)
      [ -n "$sa_user_id" ] && break
      sleep 2
    done
    [ -n "$sa_user_id" ] || die "the $client_id service account user did not appear in Keycloak"
    role_id=$(kc GET /roles/admin | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4) || true
    [ -n "$role_id" ] || die "the wind-turbine realm has no admin role to grant"
    # The role-mapping endpoint takes an array of role representations, not one object.
    kc POST "/users/$sa_user_id/role-mappings/realm" -o /dev/null \
      -d "[{\"id\":\"$role_id\",\"name\":\"admin\"}]" \
      || die "could not grant the admin role to the $client_id service account"
  }
  # Record client logins, so Keycloak's events show which method each agent used.
  kc PUT /events/config -o /dev/null \
    -d '{"eventsEnabled":true,"eventsListeners":["jboss-logging"],"enabledEventTypes":["CLIENT_LOGIN","CLIENT_LOGIN_ERROR"],"eventsExpiration":86400,"adminEventsEnabled":false}' \
    || die "could not enable client login events"
  if [ "$RACE_OPS_AUTH" = spiffe ]; then
    ensure_federated_client race-ops "Race director" spire "$SPIFFE_SUB"
  else
    ensure_federated_client race-ops "Race director"
  fi
  ensure_federated_client race-creative "Race creative director"
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

wait_for_build_and_rollout quinoa-wind-turbine

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
  log "WARNING: no secret commentary-openai; the game, race-creative and the director run without a model"
fi

if [ "$OIDC_ENABLED" = true ]; then
  # The two agents that talk to Keycloak run as their own service accounts, because Keycloak maps
  # each client to one service account subject and warns against sharing the namespace default.
  # race-analyst gets one too, so SPIRE can give it an identity of its own.
  for agent in race-ops race-creative race-analyst; do
    oc create serviceaccount "$agent" -n "$NAMESPACE" --dry-run=client -o yaml \
      | oc apply -n "$NAMESPACE" -f -
  done

  # The two A2A specialists race-ops calls. Each is its own service, so one can be restarted or
  # killed on stage without touching the others; neither exposes a route.
  log "Build and deploy race-analyst"
  ./mvnw -f race-analyst/pom.xml package -Dquarkus.profile="$APP_PROFILE" \
    -Dquarkus.kubernetes.deploy=true -DskipTests \
    -Dquarkus.container-image.registry="$REGISTRY" \
    -Dquarkus.container-image.group="$NAMESPACE" \
    -Dquarkus.openshift.env.vars.a2a-allowed-caller="$A2A_ALLOWED_CALLER"
  if [ "$A2A_MTLS" = true ]; then
    scripts/mount-spiffe-svid.sh race-analyst "$NAMESPACE" --x509
  fi
  wait_for_build_and_rollout race-analyst

  log "Build and deploy race-creative"
  # The model turns on only when the shared secret exists; without it the app still boots and
  # proposes presets, so the theme beat never depends on the key being provisioned.
  CREATIVE_AI_ARGS=""
  if oc get secret commentary-openai -n "$NAMESPACE" >/dev/null 2>&1; then
    CREATIVE_AI_ARGS="-Dquarkus.openshift.env.vars.race-creative-ai-enabled=true"
  fi
  APP_HOST=$(oc get route quinoa-wind-turbine -n "$NAMESPACE" -o jsonpath='{.spec.host}')
  # The game runs five pods, and its Service round-robins: an MCP session opened on one pod is
  # unknown to the next request that lands elsewhere, and the tool call hangs until the client
  # times out. The route is annotated `balance: source`, so it keeps a caller on one pod. Both
  # MCP clients in the cluster therefore go through the route, not the Service.
  # $CREATIVE_AI_ARGS is unquoted for the same reason as $OIDC_ARGS above.
  ./mvnw -f race-creative/pom.xml package -Dquarkus.profile="$APP_PROFILE" \
    -Dquarkus.kubernetes.deploy=true -DskipTests \
    -Dquarkus.container-image.registry="$REGISTRY" \
    -Dquarkus.container-image.group="$NAMESPACE" \
    -Dquarkus.openshift.env.vars.a2a-allowed-caller="$A2A_ALLOWED_CALLER" \
    -Dquarkus.openshift.env.vars.quarkus-oidc-client-auth-server-url="$KC_URL" \
    -Dquarkus.openshift.env.vars.quarkus-oidc-client-client-id="system:serviceaccount:$NAMESPACE:race-creative" \
    -Dquarkus.openshift.env.vars.quarkus-langchain4j-mcp-race-admin-url="https://$APP_HOST/admin/mcp" \
    $CREATIVE_AI_ARGS
  # Patch before waiting: the s2i build is asynchronous, so the first pod of the new image already
  # starts with the token mounted.
  mount_keycloak_token race-creative
  if [ "$A2A_MTLS" = true ]; then
    scripts/mount-spiffe-svid.sh race-creative "$NAMESPACE" --x509
  fi
  wait_for_build_and_rollout race-creative
  if [ -n "$CREATIVE_AI_ARGS" ]; then
    oc set env deployment/race-creative -n "$NAMESPACE" --from=secret/commentary-openai
    oc rollout status deployment/race-creative -n "$NAMESPACE" --timeout=600s
  fi

  log "Build and deploy race-ops"
  # The director talks to the game through the same source-balanced route, so the MCP sessions
  # stay on one of the five pods. OIDC comes from the same Keycloak as the game's admin login.
  # Quarkus needs a client id and Keycloak only accepts the assertion's subject there. With
  # RACE_OPS_AUTH=spiffe the assertion is the JWT-SVID file the svid-refresh sidecar keeps current.
  RACE_OPS_CLIENT_ID="system:serviceaccount:$NAMESPACE:race-ops"
  RACE_OPS_AUTH_ARGS=""
  if [ "$RACE_OPS_AUTH" = spiffe ]; then
    RACE_OPS_CLIENT_ID=$SPIFFE_SUB
    RACE_OPS_AUTH_ARGS="-Dquarkus.openshift.env.vars.quarkus-oidc-client-credentials-jwt-source=spiffe-jwt -Dquarkus.openshift.env.vars.quarkus-oidc-client-credentials-jwt-token-path=/var/run/secrets/svid/jwt_svid.token"
  fi
  # $RACE_OPS_AUTH_ARGS is unquoted on purpose, like $OIDC_ARGS: several -D options, none with a space.
  ./mvnw -f race-ops/pom.xml package -Dquarkus.profile="$APP_PROFILE" \
    -Dquarkus.kubernetes.deploy=true -DskipTests \
    -Dquarkus.container-image.registry="$REGISTRY" \
    -Dquarkus.container-image.group="$NAMESPACE" \
    -Dquarkus.openshift.env.vars.quarkus-langchain4j-mcp-race-url="https://$APP_HOST/mcp" \
    -Dquarkus.openshift.env.vars.quarkus-langchain4j-mcp-race-admin-url="https://$APP_HOST/admin/mcp" \
    -Dquarkus.openshift.env.vars.quarkus-oidc-client-auth-server-url="$KC_URL" \
    -Dquarkus.openshift.env.vars.quarkus-oidc-client-client-id="$RACE_OPS_CLIENT_ID" \
    -Dquarkus.openshift.env.vars.race-ops-ai-enabled=true \
    $RACE_OPS_AUTH_ARGS

  # One call per Deployment: the sidecar and init container carry every kind of SVID the pod needs.
  RACE_OPS_SVID_ARGS=""
  if [ "$RACE_OPS_AUTH" = spiffe ]; then
    RACE_OPS_SVID_ARGS="--jwt $KC_URL"
  else
    mount_keycloak_token race-ops
  fi
  if [ "$A2A_MTLS" = true ]; then
    RACE_OPS_SVID_ARGS="$RACE_OPS_SVID_ARGS --x509"
  fi
  if [ -n "$RACE_OPS_SVID_ARGS" ]; then
    # shellcheck disable=SC2086 # several options, none with a space
    scripts/mount-spiffe-svid.sh race-ops "$NAMESPACE" $RACE_OPS_SVID_ARGS
  fi
  wait_for_build_and_rollout race-ops

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
  log "OIDC is off; skipping race-analyst, race-creative and race-ops (the director needs the admin tools)"
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
