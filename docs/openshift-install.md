# OpenShift installation

This guide deploys the game and its dependencies on OpenShift. The game needs Kafka and Infinispan.
Keycloak is optional and only gates the admin MCP tools, and OpenAI is optional for AI commentary.
With the default settings, the dashboard login, player controls, read-only MCP and template
commentary all work without either optional service.

The game runs against either Strimzi (Kafka on the cluster) or Confluent Cloud (Kafka off the
cluster), chosen by config profile. This guide covers the Strimzi path end to end; the Confluent
differences are called out under [Confluent Cloud](#confluent-cloud). Both paths use the same image
and the same JSON messages, so switching providers only changes configuration.

You need OpenShift 4.14 or later with cluster-admin, and the `oc` CLI logged in. The commands below
deploy into a namespace called `wind-turbine`; change it to suit.

## Deploy with the script

[`scripts/deploy-openshift.sh`](../scripts/deploy-openshift.sh) runs every step below in order, with
the waits in between. It is safe to re-run:

```bash
scripts/deploy-openshift.sh
```

- Existing dashboard, session and Infinispan passwords are read back and reused, so a re-run doesn't
  rotate them.
- A realm import that already reports `Done` is left alone. Reimporting would invalidate the cached
  OAuth client of any MCP agent.
- The OIDC settings are baked into the s2i build, so every redeploy keeps them.

Settings come from environment variables: `NAMESPACE` (default `wind-turbine`), `KEYCLOAK_NAMESPACE`
(default `keycloak`) and `KEYCLOAK_CR` (default `keycloak`). Set `SKIP_KEYCLOAK=1` to deploy without
the admin MCP tools. If no Keycloak CR exists in the Keycloak namespace, the script skips the realm
import and deploys with OIDC off.

The sections below describe the same steps by hand, for when you need to run one of them alone.

## Install the operators

Install these from OperatorHub. The Kafka and Infinispan operators own cluster-scoped CRDs, so
install them once per cluster:

- Red Hat AMQ Streams (or the community Strimzi operator) for Kafka. Both serve the same
  `kafka.strimzi.io` API, so the manifests here work with either.
- The community Infinispan operator for the counters cache.
- The Red Hat build of Keycloak operator, only if you want the admin MCP tools.

Install AMQ Streams and Infinispan cluster-wide, for example with a subscription in
`openshift-operators`:

```bash
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
```

Wait until both reach `Succeeded`:

```bash
oc get csv -n openshift-operators | grep -E 'amqstreams|infinispan'
```

Create the namespace for the game:

```bash
oc new-project wind-turbine
```

## Create the secrets

[`secrets.yaml`](../src/main/kubernetes/secrets.yaml) is an OpenShift template that generates the
dashboard password and the shared session key, so neither value is committed. Apply it first, because
the app's deployment reads both as required secret references and won't start without them:

```bash
oc process -f src/main/kubernetes/secrets.yaml | oc apply -f -
```

`game-admin` holds the dashboard password and replaces the digest in `application.properties`.
`game-session` holds `quarkus.http.auth.session.encryption-key`. Without a shared key each replica
encrypts the login cookie with its own, so a dashboard session would only work on the pod that
created it; the shared key also covers the `/ws/dashboard` handshake. Read the generated password
when you need it:

```bash
oc get secret game-admin -o jsonpath='{.data.password}' | base64 -d
```

## Deploy Infinispan

[`infinispan/infinispan.yaml`](../src/main/kubernetes/infinispan/infinispan.yaml) creates a
single-node Infinispan with persistent storage and its own credentials secret, so the deployment
doesn't depend on the operator-generated secret:

```bash
oc process -f src/main/kubernetes/infinispan/infinispan.yaml | oc apply -f -
oc wait --for=condition=wellFormed --timeout=240s infinispan/infinispan
```

The app defines its `users` and `races` counters at startup, so the template needs no server-side
counter config. The counters are persistent, so the race and user IDs survive a restart. To reuse an
existing Infinispan service instead, skip this file and override `infinispan-host`,
`infinispan-username` and the `infinispan-credentials` secret to match it. Each namespace gets its
own instance and counters, so two deployments keep independent game state.

## Deploy Kafka

[`openshift.cluster.yml`](../src/main/kubernetes/openshift.cluster.yml) holds a KRaft Kafka cluster
and the topics. The cluster is one dual-role node pool of three nodes, giving three brokers and
a three-member controller quorum with persistent volumes, and the plain internal listener on 9092
that the `openshift-cluster` profile points at:

```bash
oc apply -f src/main/kubernetes/openshift.cluster.yml
oc wait --for=condition=Ready --timeout=360s kafka/my-cluster
```

The topic operator creates `power`, `game-events`, `race-progress` (compacted), `race-commentary` and
`race-modifiers` (both one-hour retention), each with one partition and three replicas. The race
inputs stay on partition 0 so one Streams task owns the race. The `openshift-cluster` profile sets the Kafka Streams internal
topics to three replicas as well.

## Deploy the application

Build with Java 25 and deploy with the `openshift-cluster` profile. There are two ways to get the
image onto the cluster.

### Use the CI image

The [GitHub Actions workflow](../.github/workflows/build.yml) builds the image with Java 25 and
pushes it to `ghcr.io/<owner>/quinoa-wind-turbine` on every push to `main`. Point the deployment at
that image and deploy without an in-cluster build:

```bash
oc new-app --image=ghcr.io/<owner>/quinoa-wind-turbine:latest --name=quinoa-wind-turbine -n wind-turbine
```

Make sure the cluster can pull the image (a public package, or an image pull secret for a private
one).

### Build on the cluster

For a branch that isn't on `main`, build from source with the OpenShift s2i builder, which runs the
Maven and Quinoa build locally and then packages the jar into the Java 25 base image on the cluster.
Point the image at the internal registry so no external credentials are needed:

```bash
oc project wind-turbine
./mvnw clean package -Dquarkus.profile=openshift-cluster -Dquarkus.kubernetes.deploy=true -DskipTests \
  -Dquarkus.container-image.registry=image-registry.openshift-image-registry.svc:5000 \
  -Dquarkus.container-image.group=wind-turbine
```

The committed `quarkus.container-image.registry` stays `quay.io` for the CI build above; the override
here sends the s2i build to the internal registry instead. The profile already sets the Java 25 base
image, five replicas, resource requests and limits, a termination grace period that lets WebSocket
connections drain, and a longer startup probe.

Wait for the rollout and check the app:

```bash
oc rollout status deployment/quinoa-wind-turbine
HOST=$(oc get route quinoa-wind-turbine -o jsonpath='{.spec.host}')
curl -s "https://$HOST/q/health/ready"
curl -s "https://$HOST/api/config"
```

The route is source-balanced (`haproxy.router.openshift.io/balance=source`, set through
`quarkus.openshift.route.annotations`). MCP sessions live in the replica that created them, so
without it a request can land on a replica that doesn't know the session and MCP clients fall back
to re-initializing, which adds a hidden round trip to most tool calls.

## Keycloak for the admin MCP tools

The read-only MCP tools at `/mcp` need no login. The admin tools at `/admin/mcp` start, pause and
reset the race, and they need a bearer token with the `admin` role from the `wind-turbine` Keycloak
realm. Without Keycloak the app still starts and `/admin/mcp` rejects every call.

Instance creation is separate from the realm import, so you can reuse an existing Keycloak or create
a fresh one.

### Reuse an existing Keycloak

The Keycloak operator reconciles only the namespaces it watches, so the realm import and its password
secret go in that namespace. Copy the dashboard password into it and import the realm, naming the
existing Keycloak CR:

```bash
KC_NS=keycloak   # the namespace of the existing Keycloak
oc create secret generic game-admin \
  --from-literal=password="$(oc get secret game-admin -n wind-turbine -o jsonpath='{.data.password}' | base64 -d)" \
  -n "$KC_NS"
oc process -f src/main/kubernetes/keycloak/realm-import.yaml -p KEYCLOAK_CR_NAME=<existing-cr-name> \
  | oc apply -n "$KC_NS" -f -
oc wait --for=jsonpath='{.status.conditions[?(@.type=="Done")].status}'=True \
  --timeout=240s keycloakrealmimport/wind-turbine -n "$KC_NS"
```

A realm import is per-realm, so it adds `wind-turbine` without touching other realms on the instance.
The developer user's password comes from the `game-admin` secret, so the dashboard and the MCP login
share one password.

### Create a fresh Keycloak

On a cluster with no Keycloak, install the operator into the namespace, create the database secret,
and apply the instance then the realm import:

```bash
oc create secret generic keycloak-db --from-literal=username=keycloak --from-literal=password=<choose>
oc process -f src/main/kubernetes/keycloak/keycloak.yaml \
  -p KEYCLOAK_HOSTNAME=keycloak-wind-turbine.apps.<cluster domain> | oc apply -f -
oc process -f src/main/kubernetes/keycloak/realm-import.yaml | oc apply -f -
```

Keycloak keeps its data in PostgreSQL with a 1 GiB volume, because the realm import runs once and a
file database in the pod would lose it on a restart.

### Point the app at Keycloak

Enable OIDC on the app and give it the realm's external URL:

```bash
oc set env deployment/quinoa-wind-turbine -n wind-turbine \
  GAME_OIDC_ENABLED=true \
  GAME_OIDC_URL=https://<keycloak-host>/realms/wind-turbine
```

The app validates the issuer of every token, so `GAME_OIDC_URL` must use the same external host that
MCP clients get their tokens from, and the app reaches it through the router. An in-cluster build with
the Quarkus OpenShift extension replaces the deployment and drops variables set with `oc set env`, so
set them again after a redeploy or bake them into the build with
`-Dquarkus.openshift.env.vars.game-oidc-enabled=true`.

With OIDC on, an unauthenticated call to `/admin/mcp` returns 401 with a `WWW-Authenticate` header
that points to `/.well-known/oauth-protected-resource/admin/mcp`, which names the realm so MCP clients
can start their OAuth login.

## Optional AI commentary

Template commentary works with the default configuration. To enable the OpenAI-compatible provider,
create a secret and set the startup configuration:

```bash
oc create secret generic commentary-openai --from-literal=OPENAI_API_KEY='<your API key>'
oc set env deployment/quinoa-wind-turbine --from=secret/commentary-openai
oc set env deployment/quinoa-wind-turbine GAME_AI_ENABLED=true OPENAI_MODEL=gpt-6-luna
```

Set `OPENAI_BASE_URL` and `OPENAI_MODEL` for a compatible endpoint reachable from the pods. For a
server without reasoning parameters, set `OPENAI_REASONING_EFFORT` to an empty value. Keep the API
key out of source control.

## Verify the deployment

Run a race against the route. Log in as the dashboard admin, start the game, and drive simulated
players over WebSockets:

```bash
HOST=$(oc get route quinoa-wind-turbine -o jsonpath='{.spec.host}')
PW=$(oc get secret game-admin -o jsonpath='{.data.password}' | base64 -d)
curl -s -c /tmp/cj "https://$HOST/j_security_check" \
  --data-urlencode "j_username=developer" --data-urlencode "j_password=$PW" -o /dev/null
curl -s -b /tmp/cj -X POST -H 'Content-Type: application/json' -d '{"type":"start"}' "https://$HOST/api/game/event"
jbang scripts/GameLoader.java "https://$HOST" --players 12 --clicks 600 --power 10 --interval 75 --ws
curl -s "https://$HOST/api/race/state"
```

The race state reaches `FINISHED`, and `GET /api/race/leaderboard?team=1` returns the ranked players.
A read-only MCP `initialize` against `https://$HOST/mcp` returns the server info without a login.

## Confluent

The Confluent path runs Kafka off the cluster, so it needs no Strimzi operator and no Kafka custom
resources. The rest of the setup is the same: the Infinispan operator, the secrets, Infinispan, the
optional Keycloak, and the app. Only Kafka changes, and only through configuration. Pick the variant
that matches your cluster: managed Confluent Cloud, or a self-managed Confluent Platform cluster.

### Confluent Cloud

The app authenticates to Confluent Cloud with an API key that belongs to a service account. Create
the service account and an API key for the cluster with the Confluent CLI:

```bash
confluent login
confluent environment use <env-id>
confluent kafka cluster use <cluster-id>
confluent iam service-account create wind-turbine --description "Wind turbine race"
confluent api-key create --service-account <service-account-id> --resource <cluster-id>
```

Create the topics and the service account's ACLs with the provisioning script. It creates the same
topics with the same partitions and retention as the Strimzi path, and grants the service account
access to those topics, the Kafka Streams internal topics (prefix `quinoa-wind-turbine-`) and the
consumer groups (prefixes `wind-turbine-`, `commentary-` and `quinoa-wind-turbine`):

```bash
scripts/confluent-provision.sh <service-account-id>
```

Confluent Cloud doesn't create topics on first use, and Kafka Streams needs replication factor three
for its changelog topics, which the `confluent` profile sets. Run the script before the app rolls out.

Put the connection details in the `confluent-credentials` secret that the deployment reads:

```bash
oc create secret generic confluent-credentials -n wind-turbine \
  --from-literal=bootstrap-servers='<bootstrap endpoint>:9092' \
  --from-literal=api-key='<API key>' \
  --from-literal=api-secret='<API secret>'
```

Deploy the app with the `confluent` profile instead of `openshift-cluster`. Everything else from
[Create the secrets](#create-the-secrets) through [Deploy the application](#deploy-the-application)
applies, except you skip [Deploy Kafka](#deploy-kafka):

```bash
./mvnw clean package -Dquarkus.profile=confluent -Dquarkus.kubernetes.deploy=true -DskipTests \
  -Dquarkus.container-image.registry=image-registry.openshift-image-registry.svc:5000 \
  -Dquarkus.container-image.group=wind-turbine
```

The profile sets `QUARKUS_PROFILE=confluent` on the deployment and reads the bootstrap endpoint and
API key from the secret, so the same image runs against Confluent Cloud with no code or serializer
change. Verify it with the same race as the Strimzi path.

Confluent Cloud brokers use publicly trusted certificates, so remove the four `%confluent.*.ssl.truststore.*`
lines and the three `%confluent.quarkus.openshift.*confluent-ca*` lines from `application.properties`
before deploying to Cloud; those exist for the self-managed path below, which has a private CA.

### Self-managed Confluent Platform

A self-managed Confluent Platform cluster uses the same `confluent` profile, with two differences from
Cloud: the brokers usually present a private CA, so the client needs that CA in a truststore, and they
don't auto-create topics, so you create the five topics yourself. The profile already mounts a
`confluent-ca` secret as a PEM truststore and skips none of the SASL settings, so most of this is
providing the CA and the credentials.

Get the broker CA. If you have host access, copy it (for example
`/var/lib/confluent-access/kafka-ca.crt`); otherwise pull it from the broker's TLS chain, since the
brokers present the CA:

```bash
openssl s_client -connect <broker-host>:9094 -showcerts </dev/null 2>/dev/null > chain.pem
# the self-signed CA is the certificate whose subject equals its issuer
```

Create the CA truststore secret and the SASL credentials, then create the topics. The brokers speak
SASL_SSL/PLAIN, so supply the broker's SASL username and password (not the Control Center login):

```bash
oc create secret generic confluent-ca -n wind-turbine-confluent --from-file=kafka-ca.crt=./kafka-ca.crt
oc create secret generic confluent-credentials -n wind-turbine-confluent \
  --from-literal=bootstrap-servers='<broker-host>:9094,<broker-host>:9095,<broker-host>:9096' \
  --from-literal=api-key='<SASL username>' \
  --from-literal=api-secret='<SASL password>'

BOOTSTRAP='<broker-host>:9094,<broker-host>:9095,<broker-host>:9096' \
  SASL_USER='<SASL username>' SASL_PW='<SASL password>' CA=./kafka-ca.crt \
  jbang scripts/CreateTopics.java
```

`scripts/CreateTopics.java` creates the five topics with the same partitions and retention as the
Strimzi path, over SASL_SSL with the private CA. It needs kafka-clients 4.x (older clients fail on
Java 24+ because the Security Manager is gone), which the script pins. Deploy the app the same way as
for Cloud, into the namespace that holds these secrets:

```bash
oc project wind-turbine-confluent
./mvnw clean package -Dquarkus.profile=confluent -Dquarkus.kubernetes.deploy=true -DskipTests \
  -Dquarkus.container-image.registry=image-registry.openshift-image-registry.svc:5000 \
  -Dquarkus.container-image.group=wind-turbine-confluent
```

The `confluent` profile mounts the `confluent-ca` secret at `/mnt/confluent-ca` and points the Kafka
and Kafka Streams clients at it as a PEM truststore. The broker leaf cert includes the broker IP in
its SAN, so hostname verification stays on. Verify with the same race as the Strimzi path.

## Clean up

Deleting the app leaves the data behind, so remove the stateful resources explicitly when you want a
clean slate:

```bash
oc delete deployment,svc,route,imagestream,buildconfig -l app.kubernetes.io/name=quinoa-wind-turbine -n wind-turbine
oc delete -f src/main/kubernetes/openshift.cluster.yml -n wind-turbine   # Kafka and topics
oc process -f src/main/kubernetes/infinispan/infinispan.yaml | oc delete -f -
oc delete secret game-admin game-session infinispan-credentials -n wind-turbine
```

Deleting Kafka and Infinispan removes their persistent volumes and the game state with them. Reusing
Keycloak leaves the `wind-turbine` realm in place; delete the `KeycloakRealmImport` and the realm in
the Keycloak admin console if you want it gone.

## GitOps and pipelines

Deploying directly, as above, is enough to run the game. For the full GitOps and Tekton pipeline
setup, see [GitOps and pipeline setup](gitops-pipeline.md).
