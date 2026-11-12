# Workload identity for the KubeCon demo: plan

## Overview

**Goal.** Tell one identity story from laptop to OpenShift: people authenticate to the game
through a browser login, and the agents (race-ops, race-analyst, race-creative) authenticate
as workloads, with no shared secrets and no trust placed in the network.

**Scope (confirmed).**

1. Bob uses a pre-registered public client for the browser login.
2. The shared Red Hat build of Keycloak (RHBK) is upgraded from 26.4 to 26.6.
3. race-ops and race-creative authenticate to Keycloak with their Kubernetes service account
   token (federated client authentication). The `race-ops-client` secret goes away.
4. The zero trust workload identity manager (SPIRE) is installed, and race-ops switches from
   the service account token to a SPIFFE JWT-SVID.
5. The A2A calls between race-ops and the two specialists use mTLS with X.509 SVIDs, and
   race-creative accepts only race-ops.
6. (Added 2026-11-11) The deployment moves from shell scripts to Git: Argo CD, which the
   cluster already runs, plus operators for secrets and the Keycloak configuration.

**Status at a glance** (updated 2026-11-11).

| Sub-task | State | Open |
|---|---|---|
| 0 Spike | done | answered in sub-tasks 5 and 6 |
| 1 Bob pre-registered client | done | optional: remove the anonymous registration policies |
| 2 RHBK 26.6 upgrade | done | none |
| 3 Secretless race-ops and race-creative | done, verified by events | from-scratch deploy script run (in 7) |
| 4 Install ZTWIM (SPIRE) | done, verified on the cluster | none (race-analyst's service account rolled out with the mTLS build) |
| 5 SPIFFE JWT-SVID for race-ops | done, verified by events and a director-started race | from-scratch deploy script run (in 7) |
| 6 A2A mTLS and caller allowlist | done, verified on the cluster (access control, mesh race, fallback, SVID rotation) | docs (in 7); the script's own `A2A_MTLS=true` path not run end to end |
| 7 Demo script, docs, full redeploy | pending | needs 4 to 6, and the deployment decision in 8 (the docs describe the mechanism) |
| 8 Declarative deployment instead of the scripts | steps 1–7 done (deploy/ tree, Argo CD app, Kafka and Infinispan, Vault secrets, CI images, all four apps running with the mTLS mesh) | steps 8 to 10 |

**Approach.** Each step leaves a working demo behind it and keeps the previous mechanism as
a fallback until the next step is rehearsed. Step 3 is the part that does not depend on
anything in preview. Steps 4 and 5 sit on a feature that Keycloak still calls preview (SPIFFE
client authentication), so they are additive.

**Not in scope.** Kafka (Confluent API keys), Infinispan, the Player WebSocket, replacing the
dashboard form login, on-behalf-of token exchange.

## Facts this plan rests on

Verified on the cluster (`https://api.cluster-dnw9g.dyn.redhatworkshops.io:6443`, OpenShift 4.22.20):

- Keycloak CR `keycloak/keycloak` started this work on `26.4.18.redhat-00001` and now runs
  RHBK 26.6.9 (sub-task 2), operator channel `stable-v26.6`. The CR has no `features`; the
  `federated-jwt` authenticator is enabled without one, and `SPIFFE` is a disabled preview.
- The Keycloak instance is shared: its log shows a separate `sso` realm with an `idp-4-ocp`
  client. The upgrade affects it too.
- `openshift-zero-trust-workload-identity-manager` is in the catalog (channel `stable-v1`,
  `v1.1.3`) and not installed.
- The Keycloak CR terminates TLS itself (`keycloak-tls`, ingress disabled), unlike
  `src/main/kubernetes/keycloak/keycloak.yaml` in the repo. The cluster instance was not
  created from that file.

From upstream and Red Hat documentation:

- Keycloak 26.4: federated client authentication is a preview behind the features
  `client-auth-federated:v1`, `spiffe:v1`, `kubernetes-service-accounts:v1`. Keycloak 26.6:
  supported for Kubernetes service accounts and OIDC; SPIFFE stays preview until the IETF
  draft is final. (Tried on 26.6.9: the Kubernetes path works with no flag.)
- A realm created before the feature was enabled needs its clients authentication flow
  copied and edited ("Signed JWT - Federated" added as an alternative), then bound. Reported
  in a Keycloak discussion, not in the official docs; confirmed on this cluster in sub-task 3.
- Keycloak takes the trust domain of a SPIFFE client assertion from `sub`, and the assertion type
  is `urn:ietf:params:oauth:client-assertion-type:jwt-spiffe`. Upstream describes a JWT-SVID as
  having no `iss`, but the JWT-SVIDs this SPIRE issues do carry one (the `jwtIssuer` of the
  `SpireServer`, here `https://oidc-discovery.<apps domain>`; seen in sub-task 4). Keycloak
  accepts them anyway (sub-task 5): the `iss` is only logged.
- Bob supports a pre-registered client through `oauth.clientId` (and `oauth.scope`,
  `oauth.clientSecret`) per MCP server entry. Bob's redirect URIs are
  `http://127.0.0.1:33418/callback`, plus `33419`-`33427` as fallbacks.

Still unverified: how the Quarkus A2A server and client can use SVID files for mTLS.

## Decisions (confirmed by the user)

- The cluster is a throwaway, so the shared Keycloak is upgraded in place (sub-task 2).
- The sub-task order stands.
- race-creative gets its own Keycloak client and service account (sub-task 3).
- Deployment moves to Git (sub-task 8): manifests in this repo, the Argo CD Application in
  `kdubois/progressive-delivery`, GitHub Actions for the image builds, no Quarkus manifest
  generation, Vault stays in dev mode, Rollouts is a bonus.

## Working assumptions (proposed, not yet confirmed by the user)

- race-creative keeps the `admin` role: the admin MCP permission checks that role, and a
  narrower one needs an app change.
- The anonymous client-registration policies stay as the fallback for MCP clients without a
  fixed client id (sub-task 1), unless the demo is Bob-only.

## Findings from the spike (sub-task 0)

- Quarkus OIDC client (3.40 guide): `quarkus.oidc-client.credentials.jwt.source=bearer` sends a
  file-based JWT as `client_assertion` (`token-path`, reloaded on expiry), and
  `source=spiffe-jwt` does the same with assertion type `jwt-spiffe`. No custom filter needed.
  `bearer` is proven in the app (sub-task 3); `spiffe-jwt` is not yet.
- Keycloak's Kubernetes identity provider needs both `issuer` (the issuer stamped into the
  tokens, `https://kubernetes.default.svc` here) and `issuerDiscoveryUrl`. It reads the
  discovery endpoint with its own pod's token, so no extra RBAC was needed.
- The client assertion's audience must be the realm URL.
- Quarkus requires `client-id`; Keycloak only accepts the token's subject there (sub-task 3).
- ZTWIM 1.1.3 (read from the operator's CSV and CRDs on the cluster, because docs.redhat.com
  refused fetches): one `ZeroTrustWorkloadIdentityManager` (trust domain, cluster name,
  bundle ConfigMap `spire-bundle`), plus `SpireServer`, `SpireAgent`, `SpiffeCSIDriver` and
  `SpireOIDCDiscoveryProvider`, all named `cluster`. Workloads get identities from
  `ClusterSPIFFEID` objects (group `spire.spiffe.io`). Pods fetch SVIDs from the Workload API
  socket that the `csi.spiffe.io` CSI driver mounts. The OIDC discovery provider serves the
  JWT-SVID keys on a Route, which is what Keycloak would read.
- Still open for sub-task 6: the Quarkus TLS registry reload and the A2A client's TLS
  configuration.

## Sub-tasks

### 0. Spike: confirm the unverified pieces

**Intent.** Remove the guesses before any cluster change. Everything here is read-only or
runs on a laptop or a scratch namespace.

**Expected outcomes.** A short findings section appended to this file that settles each open
question, and a go or no-go for sub-tasks 3 to 6.

**Todo list.**
- [x] Find how Quarkus' OIDC client sends a client assertion from a file (the
      `credentials.jwt` settings) and whether the assertion type can be set to `jwt-spiffe`.
      Yes: `source=bearer` and `source=spiffe-jwt`; no filter or sidecar needed.
- [ ] Check how the Quarkus REST/Vert.x server and the A2A JSON-RPC client can load key and
      certificate files that rotate (Quarkus TLS registry, reload period), and whether the
      A2A client transport accepts a custom TLS configuration. Do this at the start of
      sub-task 6.
- [x] Read the ZTWIM v1.1 resource names, how workloads get SVIDs, and the OIDC discovery
      provider. Done at the start of sub-task 4; see the findings. The JWT-SVID audience is
      whatever the workload asks for when it fetches.
- [x] Check whether the Keycloak Kubernetes identity provider can reach this cluster's
      service account issuer and JWKS. Yes, see the findings.
- [x] Check the RHBK 26.6 documentation for the exact feature names and whether federated
      client authentication needs a feature flag at all. It does not for Kubernetes service
      accounts; `SPIFFE` is a disabled preview feature.

**Relevant context.** `race-ops/src/main/resources/application.properties` (OIDC client),
`race-creative/src/main/resources/application.properties`, `race-ops/pom.xml` (A2A SDK
1.4.0.Final, `langchain4j-agentic-a2a`).

**Status.** `[-] mostly done`: the Keycloak and Quarkus questions are settled, and the two
SPIFFE-specific reads move to the start of sub-tasks 4 and 6. Go for sub-task 3 was given and
it worked; no-go or go for 4 to 6 depends on those reads.

### 1. Bob uses a pre-registered client

**Intent.** Keep the browser login (it is the right flow for a person) but stop depending on
dynamic client registration, which the MCP spec now deprecates and which needs the
"Trusted Hosts" workaround on this realm.

**Expected outcomes.** Bob connects to `wind-turbine-admin` and `wind-turbine-admin-cluster`
with a fixed client ID, opens the browser once, and gets a token with the `admin` role and
the `wind-turbine` audience. `scripts/open-dev-registration.sh` is no longer needed on the
path the demo uses.

**Todo list.**
- [x] In `.bob/mcp.json`, add `oauth.clientId` (the `mcp-agent` client) to the two admin
      entries, local and cluster.
- [x] Add Bob's redirect URIs to the `mcp-agent` client in
      `src/main/kubernetes/keycloak/realm-import.yaml` and the dev realm file
      `src/main/resources/keycloak/wind-turbine-realm.json`: `127.0.0.1:33418` is there,
      add `33419`-`33427`.
- [x] Apply the same change to the live realm through the Admin API (an imported realm is
      not updated by re-import). Done: the live `mcp-agent` has 11 redirect URIs, PKCE S256,
      public client and the audience mapper.
- [x] `docs/mcp.md` Bob section describes the pre-registered client; dynamic registration
      stays documented as the fallback.
- [x] Check the token lifetime covers a rehearsal. Access tokens last 5 minutes and Bob
      refreshes them; the SSO session idles out after 30 minutes and ends after 10 hours.
      A pause of more than 30 minutes between MCP calls means logging in again.
- [x] Test locally, then on the cluster. Both admin servers answered after the browser login,
      and Keycloak's session for `developer` lists the client `mcp-agent` (the pre-registered
      one, not a dynamic registration).
- [ ] Remove the anonymous client-registration policies, the "no Trusted Hosts" comment and
      `scripts/open-dev-registration.sh`. Open decision: that also removes the fallback for
      agents that cannot take a fixed client id, so keep them unless the demo is Bob-only.
      Two stale dynamically registered "IBM Bob" clients remain in the live realm and can be
      deleted.

**Relevant context.** `.bob/mcp.json`; `realm-import.yaml` client `mcp-agent`;
`scripts/open-dev-registration.sh`; AGENTS.md section on the MCP server.

**Status.** `[x] done` (the policy removal above is optional and undecided)

### 2. Upgrade the shared Keycloak to RHBK 26.6

**Intent.** Get supported federated client authentication for Kubernetes service accounts.

**Expected outcomes.** `keycloak/keycloak` runs 26.6.x, the `sso` realm still works for
cluster login (`idp-4-ocp`), and the `wind-turbine` realm still serves the game and Bob.

**Todo list.**
- [x] Decide whether the shared instance is acceptable to upgrade: yes, the cluster is a
      throwaway (see Decisions).
- [x] Back up the Keycloak PostgreSQL database (`keycloak-pgsql`) and export the realms.
      Done: `pg_dump` and partial exports of `wind-turbine` and `sso` in `/tmp/kc-backup`
      (mode 600, not in the repo).
- [x] Check the upgrade path. The packagemanifest shows no `replaces` or `skips` for the
      `stable-v26.6` entries, but OLM went from `v26.4.18-opr.1` straight to
      `v26.6.9-opr.1` anyway.
- [x] Change the subscription channel to `stable-v26.6`; the operator and `keycloak-0` rolled
      in about a minute. Startup log: `Keycloak 26.6.9.redhat-00002`, realms `master`, `sso`
      and `wind-turbine` migrated to 26.6.2.
- [x] Re-check: `sso` and `wind-turbine` discovery documents answer 200, `/admin/mcp` on the
      game answers 401 without a token, and the `race-ops` client-credentials grant still
      returned a token (with the then-current client secret, since replaced by sub-task 3).
      Bob's browser login on the `wind-turbine` realm also works after the upgrade
      (sub-task 1). The user confirmed the cluster console login through `sso` (provider
      `rhbk`, client `idp-4-ocp`) still works after the upgrade.
- [x] SPIFFE stays off until sub-task 5 (not enabled here).

**Findings.** On 26.6.9 the `federated-jwt` client authenticator is present and
`client-auth-federated`/`kubernetes-service-accounts` are not in the disabled list, so
sub-task 3 needs no feature flag. `SPIFFE` is a preview feature and disabled; sub-task 5
needs it enabled in the `Keycloak` CR. `CIMD` and `RESOURCE_INDICATORS` are also available
(both disabled), which matter for the MCP client story later.

**Relevant context.** Subscription `keycloak/rhbk-operator`; Keycloak CR `keycloak/keycloak`
(`additionalOptions`, no `features`); `docs/openshift-install.md` section on reusing a
Keycloak.

**Status.** `[x] done`

### 3. Secretless race-ops and race-creative with service account tokens

**Intent.** Remove the `race-ops-client` secret. The pods prove who they are with a token
OpenShift already gives them, and Keycloak trusts the cluster as an issuer.

**Expected outcomes.** race-ops and race-creative obtain admin MCP tokens with no client
secret anywhere. The `race-ops-client` Secret is deleted and a fresh deploy still works. Each
agent has its own Keycloak client and service account (race-creative no longer borrows
race-ops' identity).

**Todo list.**
- [x] Create a Kubernetes identity provider in the `wind-turbine` realm for this cluster's
      service account issuer.
- [x] Fix the realm's clients authentication flow if needed (see Facts), and bind it.
- [x] Give `race-ops` and a new `race-creative` client the "Signed JWT - Federated"
      authenticator, each with its expected subject
      (`system:serviceaccount:<namespace>:<name>`).
- [x] Decide the role for `race-creative`: it keeps `admin` for now. The admin MCP HTTP
      permission checks the role, so a narrower one needs an app change (a separate role for
      the theme tools); out of scope for this story.
- [x] Mount a projected service account token with the Keycloak audience into both pods, and
      point Quarkus' OIDC client at it (per sub-task 0).
- [x] Update `scripts/deploy-openshift.sh` so it creates the federated clients and no secret.
      No flag keeps the secret path: the old path is gone, the git history has it.
- [x] Remove the `race-ops-client` references from `realm-import.yaml`, `secrets.yaml`, the two
      `application.properties` files and the docs. The live Secrets in `keycloak` and
      `wind-turbine` are deleted.
- [x] Verify: secret deleted, both agents redeployed, a 12-player race started by the
      director itself (`start_race` through the admin MCP server), and both agents ran admin
      tools afterwards (`list_preset_themes`, `get_active_theme`).
- [x] Keycloak events show the federated authentication method. Event logging was off, so it
      is now on in the live realm and the deploy script enables it on every run
      (`CLIENT_LOGIN`, `CLIENT_LOGIN_ERROR`, one day). The events for both agents read
      `client_auth_method: federated-jwt`, `client_assertion_issuer:
      https://kubernetes.default.svc` and `client_assertion_sub:
      system:serviceaccount:wind-turbine:race-ops` (or `race-creative`). Fetch them with
      `GET /admin/realms/wind-turbine/events`. This is the evidence for the stage sequence.

**Findings.**
- Quarkus' OIDC client refuses to start without `client-id` (`'quarkus.oidc-client.client-id'
  property must be configured`), even with a JWT client assertion. Setting it to the
  internal id (`race-ops`) fails the token request with `client_not_found`; setting it to
  the external subject `system:serviceaccount:<namespace>:<name>` works. The deploy script
  passes it as an env var at build time, because only the script knows the namespace.
- The first rollout crash-looped on exactly that; no Deployment env refers to a secret any more.
- A close race (1% margin) produced no boost and no theme card, so this run did not exercise
  the mid-race boost or the approval path. race-creative did call an admin tool between
  heats.

**Relevant context.** `scripts/deploy-openshift.sh` (`mount_keycloak_token`,
`ensure_federated_client`); `race-ops/README.md` (cluster deployment section);
AGENTS.md MCP section.

**Status.** `[x] done` (deployed and verified live; a from-scratch run of the deploy script
is left to sub-task 7)

### 4. Install the zero trust workload identity manager

**Intent.** Give the three agents SPIFFE identities.

**Expected outcomes.** SPIRE runs on the cluster, and race-ops, race-analyst and
race-creative each receive an SVID for a fixed SPIFFE ID, with a documented trust domain.

**Todo list.**
- [x] Check the entitlement for the operator on this cluster. The catalog entry's
      `valid-subscription` lists both "OpenShift Container Platform" and "OpenShift Platform
      Plus", so no extra entitlement is needed.
- [x] Subscribe to `openshift-zero-trust-workload-identity-manager` (channel `stable-v1`, CSV
      `v1.1.3`, namespace `zero-trust-workload-identity-manager`) and create the custom
      resources. Server, 5 agents (one per node), 5 CSI driver pods and the OIDC discovery
      provider are all ready; the server's 2Gi volume bound on the default Ceph RBD class.
- [x] Choose the trust domain and SPIFFE ID shape: the trust domain is the cluster's apps domain
      (`apps.cluster-dnw9g.dyn.redhatworkshops.io`, immutable), the cluster name is
      `wind-turbine-demo`, and an ID is
      `spiffe://<trust domain>/ns/<namespace>/sa/<service account>`. SVIDs live 1h (X.509)
      and 5m (JWT).
- [x] Register the three workloads. The operator already registers every pod through a
      fallback `ClusterSPIFFEID`, so the three agents had IDs at once. `wind-turbine-agents`
      overrides it for them with Service DNS names (needed by the mTLS server certificates in
      sub-task 6) and the lifetimes above. It only takes effect with `className:
      zero-trust-workload-identity-manager-spire`; without it the entry stays unreconciled.
      race-analyst ran as the namespace `default`, so it now has its own service account:
      created and set on the live Deployment, and added to `scripts/deploy-openshift.sh` and
      the `openshift-cluster` profile in `race-analyst/application.properties` (takes effect on
      the next build).
- [x] Verify an SVID can be fetched from a pod through the Workload API. A throwaway pod
      labelled and running as `race-ops` with the CSI volume fetched an X.509 SVID for
      `spiffe://.../ns/wind-turbine/sa/race-ops` and a JWT-SVID, then was deleted.
      The discovery route answers: `/.well-known/openid-configuration` and `/keys` (200).
- [x] Add the install steps to `docs/openshift-install.md` as an optional section, with
      `src/main/kubernetes/spire/operator.yaml` and `operands.yaml` (a Template, applied
      with `oc process`; a server dry run of it matches the cluster).

**Findings.**
- Any pod running as a service account with a matching label can mint that identity, since
  attestation is by service account and pod labels. A pod's SPIFFE identity is only as strong
  as the RBAC that controls who may run pods as `race-ops`.
- The JWT-SVID has `iss` (see Facts), `aud` as requested and `sub` the SPIFFE ID.
- The namespace's default SCC (`restricted-v2`) admitted a pod with the `csi.spiffe.io`
  ephemeral volume, so the agents' Deployments can mount it in sub-tasks 5 and 6 without an SCC
  change. (The probe needed an explicit `runAsUser` only because the SPIRE agent image runs as
  root; the agents' own images do not.) The volume has to be added to the generated
  Deployments, as `mount_keycloak_token` does for the token.
- The SPIRE server is one replica on SQLite with a 2Gi volume. Whether registrations and the
  CA survive a restart of `spire-server-0` has not been tested.

**Relevant context.** `openshift/zero-trust-workload-identity-manager` upstream repository
(operator); the Deployments generated for race-ops, race-analyst and race-creative.

**Status.** `[x] done`

### 5. Move race-ops to a SPIFFE JWT-SVID for Keycloak

**Intent.** Replace the Kubernetes token with the SPIFFE identity, so the same identity is
used for Keycloak and for the mesh.

**Expected outcomes.** race-ops authenticates to Keycloak with a short-lived JWT-SVID. If this
preview feature fails on the day, a one-line switch returns to the sub-task 3 mechanism.

**Todo list.**
- [x] Enable the SPIFFE feature in the Keycloak CR: `spec.features.enabled: [spiffe]`. This
      restarts the Keycloak pod (and so also the `sso` realm for a minute).
- [x] Create a SPIFFE identity provider in the realm: alias `spire`, `providerId=spiffe`,
      `trustDomain=spiffe://<trust domain>`, `bundleEndpoint=https://oidc-discovery.<apps
      domain>/keys` (the OIDC discovery provider's JWKS route, reachable from the Keycloak pod).
- [x] Switch the `race-ops` client to that provider: `jwt.credential.issuer=spire`,
      `jwt.credential.sub=spiffe://<trust domain>/ns/wind-turbine/sa/race-ops`.
- [x] Supply the JWT-SVID to the Quarkus OIDC client: `credentials.jwt.source=spiffe-jwt`,
      `token-path` a file, `client-id` the SPIFFE ID. The file is kept current by
      `scripts/mount-spiffe-svid.sh`: an init container writes the first JWT-SVID before the
      app starts, and a sidecar (`svid-refresh`, the SPIRE agent image) renews it every 60 s
      into an in-memory `emptyDir`. The Workload API socket comes from the `csi.spiffe.io`
      volume.
- [x] Verify against Keycloak events: two `CLIENT_LOGIN` events for `race-ops` with
      `client_auth_method: federated-jwt` and `client_assertion_sub: spiffe://.../sa/race-ops`,
      and the director started a race by itself afterwards (log line `Director started
      race-30`).
- [x] Rehearse the fallback. Switching back is three things: the client's two
      `jwt.credential.*` attributes (`openshift-sa`, `system:serviceaccount:<ns>:race-ops`),
      the `client-id` env var, and removing the two `...JWT_SOURCE`/`..._TOKEN_PATH` env vars
      (the app falls back to `source=bearer` and the projected token). Done by hand and
      reversed: the pod rolled out, and a service account token for `race-ops` got an access
      token for the restored client. `RACE_OPS_AUTH=kubernetes` (the default of
      `scripts/deploy-openshift.sh`) does the same through the script, which was not run end
      to end.
- [x] Decide whether race-creative follows: no. It stays on the Kubernetes token, which keeps
      both mechanisms on stage and the fallback in use. Adding it is the same three steps.
- [x] Remove the temporary Keycloak debug logging (`additionalOptions` is back to
      `enable-recovery`).

**Findings.**
- Keycloak's SPIFFE strategy finds the client by the assertion `sub` alone (attribute
  `jwt.credential.sub`) and then reads the identity provider alias from
  `jwt.credential.issuer`. So `sub` must be unique across clients: a probe client with the same
  SPIFFE ID made Keycloak issue the token to the wrong client, which lacks the `admin` role, and
  `start_race` then failed with a `ForbiddenException`.
- JWT-SVIDs carry `iss`; Keycloak only logs it. The assertion type is `jwt-spiffe`, and
  `client_id` may be the SPIFFE ID or omitted.
- Quarkus caches the access token for about five minutes. After changing a client or an
  identity, restart race-ops.
- A new pod can briefly get "no identity issued" from the Workload API until its registration
  reaches the agent; the init container retries.
- The `svid-refresh` container needs `/spire-agent`, which the SPIRE agent image has.

**Relevant context.** Keycloak CR; sub-task 3 changes; `scripts/mount-spiffe-svid.sh`;
`scripts/deploy-openshift.sh` (`RACE_OPS_AUTH`); `race-ops/application.properties`.

**Status.** `[x] done`

### 6. mTLS and caller authorization for the A2A mesh

**Intent.** Close the `a2a.authorization.required=false` gap. race-creative can change the
game's look through its admin tools and currently trusts the network.

**Expected outcomes.** race-analyst and race-creative accept A2A calls only over mTLS from
race-ops' SPIFFE ID. A direct call from any other pod fails. The in-process fallbacks still
keep the race running when a specialist is down.

**Decision (mechanism).**
- **SVID files.** `scripts/mount-spiffe-svid.sh --x509` adds a `fetch_x509` step to the
  init container and the `svid-refresh` sidecar. It runs `spire-agent api fetch x509 -write`
  into the in-memory `/svid` volume (`svid.pem`, `svid.key`, `bundle.pem`), renewed every 60 s.
- **Servers (race-analyst, race-creative).** The Quarkus TLS registry config `mesh` reads
  those files with `reload-period=1m`. The `mtls` profile turns on `insecure-requests=disabled`
  and `ssl.client-auth=required`. A route filter, `CallerAllowList`, then requires the leaf
  certificate's URI SAN to equal `a2a.allowed-caller` (race-ops' SPIFFE ID) and answers 403
  otherwise. Trust in the caller is the SPIFFE bundle, so only SPIRE-issued certificates get
  that far.
- **Client (race-ops).** The A2A client (`JdkA2AHttpClient`) cannot take a custom
  `SSLContext`, so `MeshTls` calls `SSLContext.setDefault(...)` at startup with
  `SvidSslContext`: the SVID as client key, JVM anchors plus the SPIFFE bundle as trust, files
  re-read when their mtime changes (checked every 10 s).
- **Switch.** `A2A_MTLS=true scripts/deploy-openshift.sh` (default `false`). The `mtls` profile
  is off unless the script turns it on, so the default run and the tests are plain HTTP.

**Todo list.**
- [x] Pick the mechanism from sub-task 0 and write the decision into this file.
- [x] Serve the specialists over HTTPS with client authentication required, and change the
      `a2a.agent.url` values to match. The container port itself serves TLS, so the Service
      mapping 80 to 8082/8083 stays and the URL is `https://race-creative:80/`.
- [x] Change `race-ops.analyst.a2a-url` and `race-ops.creative.a2a-url` to HTTPS and give
      the A2A client the SVID as its client certificate and the SPIFFE bundle as its trust
      store (`%mtls` block in `race-ops/application.properties`).
- [x] Enforce the caller's SPIFFE ID in the specialists (`CallerAllowList`, 403 otherwise).
- [x] Keep `a2a.authorization.required=false` (the SDK's own authorization is for
      end-user tokens); the comment now points at the transport check instead.
- [x] Tests: `CallerAllowListTest` in both specialists (4 each), `SvidSslContextTest` in
      race-ops (2: same CA round trip, other CA rejected). Default suites pass: race-analyst 19,
      race-creative 27, race-ops 95. A locally built race-creative jar with fixture
      certificates: no certificate and a foreign CA are rejected in the handshake, a trusted
      CA with the wrong SPIFFE ID gets 403, race-ops' ID gets 200, plain HTTP is closed.
- [x] Roll out to the cluster (the three apps, `RACE_OPS_AUTH=spiffe` plus mTLS, by hand with
      the same commands as `scripts/deploy-openshift.sh`; the script's own `A2A_MTLS=true` path
      was not run end to end). Verified:
      - The X.509 SVID carries `DNS:race-creative`, `.wind-turbine`, `.wind-turbine.svc`,
        `.wind-turbine.svc.cluster.local` and the SPIFFE URI; `fetch_x509` works in the sidecar.
      - Through a port-forward to race-creative: no certificate and plain HTTP are refused,
        race-analyst's SVID (valid CA, wrong ID) gets 403 and a `Rejected a caller` warning,
        race-ops' SVID gets 200.
      - race-ops logs `A2A calls present the SPIFFE SVID`, `/ops/api/ops/state` reports the
        engagement from the analyst (`"source":"analyst"`), and no pod logs a TLS error or an
        "unreachable" warning.
- [x] Director-started race through the mesh (race-31, 24.2 s, 12 players, GameLoader over
      `--ws` against the route): the director started it after the field was stable for 5 s,
      the supervisor called the Race Creative Director over mTLS between heats, and race-ops
      turned the answer into an `ai-names` proposal card. No TLS error, no "unreachable"
      warning. The card was dismissed afterwards with
      `POST /ops/api/ops/proposals/{id}/dismiss`.
- [x] SVID rotation: during race-31 race-ops logged `Reloaded the SPIFFE SVID` (14:50:04 UTC) and
      the very next A2A call, to race-creative, succeeded with the new key pair.
- [x] Fallback (race-32, 18.7 s): with race-analyst and race-creative scaled to 0, the race ran
      to the finish. race-ops logged `The analyst is unreachable (Failed to send message:
      java.net.ConnectException); showing the in-process numbers`, `/ops/api/ops/state` reported
      `"source":"in-process"`, and the director still proposed a boost from the in-process
      numbers. Both specialists were scaled back to 1 and came up 2/2 with their SVID files.

**Findings.**
- With a specialist down, the supervisor's failed A2A call logs a long `ConnectException`
  stack trace next to the one-line warning. Harmless, but noisy in stage logs.
- `race-ops.*` belongs to the `RaceOpsConfig` mapping, which rejects unknown keys at startup
  (`SRCFG00050`). The first rollout crash-looped because the directory was named
  `race-ops.mesh.mtls-dir`; it then lived as the loose top-level property `mesh-tls.directory`,
  and is now the mapped key `race-ops.mesh-tls.directory` (`RaceOpsConfig.meshTls().directory()`),
  so the whole config surface sits in one interface. The unit tests never run the `mtls` profile,
  so only the cluster showed the original crash; the profile can be booted locally instead —
  build with `-Dquarkus.profile=mtls` (client-auth is build-time) and point the TLS registry at
  fixture certificates at runtime (done 2026-11-11: no cert and plain HTTP refused, trusted CA
  with the wrong SPIFFE ID 403, the allowed ID 200, a foreign CA refused at the handshake).
- `CallerAllowList` stays a route filter, not an `HttpSecurityPolicy`: the policy is the more
  idiomatic extension point, but it needs the `quarkus-security` extension, and with that
  capability present the A2A SDK's Quarkus glue rejects anonymous JSON-RPC calls with 403
  regardless of `a2a.authorization.required=false` (checked against SDK 1.4.0.Final, 2026-11-11:
  both specialists' endpoint tests fail the moment the extension is added). Do not add
  `quarkus-security` to the specialists; the rationale is in both classes' javadoc.
- `quarkus.http.ssl.client-auth` is a **build-time** property. The `mtls` profile has to be
  active when the app is built (`-Dquarkus.profile=openshift-cluster,mtls`); a runtime-only
  profile left the server not requesting a certificate, and every caller got 403.
- With `quarkus.profile=a,b`, the last profile wins on conflicting keys, so `mtls` goes last.
- The Quarkus OpenShift extension writes `QUARKUS_PROFILE` into the Deployment; the `mtls`
  profile overrides it with `openshift-cluster,mtls` (checked in `target/kubernetes/openshift.yml`).
- Quarkus' server TLS defaults to TLS 1.3 only.
- `JdkA2AHttpClient` posts through an internal `HttpClient` built from
  `SSLContext.getDefault()`; a custom client passed to it is ignored. That is why the client
  side sets the default context. The Vert.x provider (`a2a-java-sdk-http-client-vertx`) was not
  needed.
- The JDK checks the server's host name against the certificate. This relies on the
  `ClusterSPIFFEID` `autoPopulateDNSNames: true` putting the Service names in the SVID, which
  it does (verified on the cluster).

**Relevant context.** `race-creative/AGENTS.md` and `race-analyst/AGENTS.md` (the
authorization setting), both `application.properties` `%mtls` sections,
`race-ops/src/main/resources/application.properties` (`%mtls`),
`race-ops/src/main/java/org/raceops/mesh/{MeshTls,SvidSslContext}.java`,
`scripts/mount-spiffe-svid.sh`, `scripts/deploy-openshift.sh` (`A2A_MTLS`).

**Status.** `[x] done`: rolled out and verified on the cluster (access control, a mesh race, the
fallback, SVID rotation). The sub-task 6 docs are still to write (in 7).

### 7. Demo script and documentation

**Intent.** Make the story repeatable on stage and keep the repo's own docs accurate.

**Expected outcomes.** A rehearsed sequence with a fallback for each preview step, and
updated docs.

**Todo list.**
- [ ] Write the stage sequence: Bob's browser login; show the director running with no
      client secret in the namespace; call race-creative from a pod and show the rejection;
      show the SPIFFE ID in a Keycloak event.
- [ ] Update AGENTS.md (MCP section, mesh section, known issues), `race-ops/README.md`,
      the race-analyst and race-creative READMEs and `docs/openshift-install.md`.
- [ ] Update the `wind-turbine-mcp-demo` skill with the new login and the removed secret.
- [ ] Add the new known issues: SPIFFE client authentication is preview in Keycloak; the
      first token after a rollout may fail soft (as it does now).
- [ ] Run the default test suites for the game and each subproject, and a full cluster
      deployment from scratch with `scripts/deploy-openshift.sh`.

**Also document sub-task 6** (not written yet): `docs/openshift-install.md` (the `A2A_MTLS`
switch, the build-time `mtls` profile, the `mesh-tls.directory` property), `AGENTS.md` (the A2A
mesh bullet and the `a2a.authorization.required=false` explanation), and the three subproject
READMEs. If sub-task 8 lands first, describe the Git-based mechanism and keep the script
description only for the fallback path.

**Relevant context.** `AGENTS.md`, `.agents/skills/wind-turbine-mcp-demo/SKILL.md`.

**Status.** `[ ] pending`, to be done after sub-task 8 step 9 (the docs describe the deployment
mechanism). The stage sequence and the known-issues text do not depend on it and can go first.

### 8. Declarative deployment instead of the scripts

**Intent.** `scripts/deploy-openshift.sh` (538 lines) and `scripts/mount-spiffe-svid.sh` hold
the deployment as imperative shell, and every sub-task has added another flag. Replace them
with what the cluster already runs: Argo CD (OpenShift GitOps 1.19.7), plus the Vault Secrets
Operator for secrets and a hook Job for the Keycloak configuration.

**Expected outcomes.** A clean namespace comes up from Git alone. The Deployments no longer
need `oc patch` or `oc set env` after a build. `scripts/deploy-openshift.sh` stays as the
fallback until step 9.

**Decisions (confirmed by the user, 2026-11-11).**
1. **Repo.** The manifests live in **this repo** (a `deploy/` directory). The Argo CD
   **Application** lives in `github.com/kdubois/progressive-delivery` and points at this repo.
2. **Image build.** **GitHub Actions** builds all four images and pushes them to a registry.
3. **Quarkus manifest generation is not needed.** Quarkus only builds the image
   (`quarkus.kubernetes.deploy=false`, no generated Deployment); the Deployments are written by
   hand in Kustomize.
4. **Vault stays in dev mode.** Its secrets must be re-seeded after a Vault restart.
5. **Argo Rollouts is a bonus**, not needed now (step 10 stays optional).

**Research results** (a read-only pass; [V] verified in the repo, on the cluster or in a cited
source, [A] assumed).
- **What exists.** [V] Two ApplicationSets over `github.com/kdubois/progressive-delivery`:
  `workload-apps` generates an Application per `workloads/*` directory (a new directory needs
  no edit), `system-apps` lists its paths explicitly in
  `components/applicationsets/system-appset.yaml`. Both use automated prune and self-heal, a
  retry limit of 15, `ApplyOutOfSyncOnly=true` and no sync waves. The AppProjects `system` and
  `workloads` allow any source repo, destination and cluster resource. Operators already
  installed: amq-streams, infinispan, RHBK 26.6.9, ZTWIM, Vault Secrets Operator, cert-manager.
  Not installed: OpenShift Pipelines/Tekton, Builds for OpenShift, Ansible Automation
  Platform, Crossplane. Argo Rollouts is installed (the `openshift` traffic plugin and
  `metric-ai`). Argo CD Image Updater: CRD present, feature off (`imageUpdater.enabled: false`).
- **Inventory of the script.** (a) Plain resources: Kafka, KafkaNodePool, five topics,
  Infinispan, Routes, the service accounts, the SPIRE operands and `ClusterSPIFFEID`, the
  Keycloak CR and Postgres. (b) Quarkus manifests and s2i image builds. (c) Keycloak Admin API:
  the realm, the `openshift-sa` (providerId `kubernetes`) and `spire` (providerId `spiffe`)
  identity providers, the copied client authentication flow `clients-federated` with the
  `federated-jwt` execution as ALTERNATIVE bound as the realm's client authentication flow, the
  `race-ops` and `race-creative` clients with `jwt.credential.issuer`/`sub` attributes and an
  audience mapper, the `admin` role grants to `service-account-<client>`, and `PUT
  /events/config`. (d) Secrets: `game-admin` (also copied into the `keycloak` namespace for the
  realm import), `game-session` (`encryption-key`), `infinispan-credentials`, and the optional
  user-supplied `commentary-openai`. (e) Operator subscriptions and waits. (f) Patches that
  exist only because Quarkus replaces the Deployment on each deploy: the `csi.spiffe.io` volume,
  the in-memory `svid` volume, the `svid-init` init container and `svid-refresh` sidecar (image
  from `RELATED_IMAGE_SPIRE_AGENT` in the ZTWIM controller Deployment), the projected service
  account token volume (audience = the realm URL, 600 s), `oc set env --from=secret/commentary-openai`.
- **Values the script computes** and the overlay must carry: the apps domain, the SPIRE trust
  domain, the game Route host (used in race-ops and race-creative URLs and the `/ops` Route),
  the Keycloak realm URL, `A2A_ALLOWED_CALLER` (`spiffe://<trust domain>/ns/wind-turbine/sa/race-ops`).
- **Ordering constraints.** CRDs before CRs; namespace and secrets before Deployments; `game-admin`
  before the realm import; Keycloak with the `spiffe` feature before the `spire` IdP; realm, then
  IdPs, then the flow, then the clients, then the role grants; ZTWIM operator, operands and
  `ClusterSPIFFEID` before any pod that mounts the CSI volume; Kafka topics and Infinispan before
  the game; realm and clients before race-ops and race-creative start.
- **Quarkus cannot generate the SVID wiring.** [V] The OpenShift extension in 3.40 supports init
  containers, sidecars, `emptyDir`, secret and config map volumes, but has no `csi` or
  `projected` volume. The generated manifest also carries a build timestamp and a commit id, so a
  committed copy would change on every build. This is the root cause of group (f) and the
  reason for decision 3.
- **Keycloak.** `KeycloakRealmImport` only creates a realm and never updates it ([V],
  keycloak.org/operator/realm-import). The strongest verified feature support is the Terraform
  provider (v5.10.0: `keycloak_kubernetes_identity_provider`, `keycloak_spiffe_identity_provider`
  (preview), `client_authenticator_type = "federated-jwt"`, copying built-in flows). Ansible's
  `keycloak_client` documents only `client-secret`, `client-jwt` and `client-x509`, so it is the
  weakest here. Crossplane would need an install and wraps the same Terraform provider.
  keycloak-config-cli (adorsys, v6.5.1) is the lightest option, but its newest image is built
  for Keycloak 26.5.5 and its support for `federated-jwt` and the two provider types is [A]. The
  `<realm>-realm` roles problem that hit 26.4 is reported fixed in 26.5.4 and 26.6.0
  ([upstream issue 45966](https://github.com/keycloak/keycloak/issues/45966)); not re-tested.
- **Rollouts.** The game is a poor canary fit: sticky WebSocket and MCP sessions (the route uses
  `balance: source`), a per-pod theme, and one Kafka Streams task that moves with the pods.
  race-analyst, then race-creative and race-ops, suit blue-green with a manual promotion; one
  replica each means a canary has no meaning. The `ClusterSPIFFEID` selector matches a pod label,
  so a preview pod must carry the same labels or it gets no SVID.
- **Ansible.** Argo CD and the operators already cover the declarative surface. The remaining
  gap is the bootstrap before Argo CD exists, which `progressive-delivery` handles with its own
  bootstrap program. Recommendation: do not add Ansible.
- **Secrets.** Vault Secrets Operator is installed. [V] Its Vault is dev-mode and in memory, and
  the existing `VaultStaticSecret`s in `progressive-delivery` currently fail with
  `auth/kubernetes/login 403` (probably a Vault restart that lost its configuration, [A]).
  Decision 4 keeps dev mode, so step 4 starts by re-seeding it with that repo's bootstrap
  program and then checking the secrets sync.
- **Image build today.** [V] `.github/workflows/build.yml` pushes
  `ghcr.io/<owner>/quinoa-wind-turbine` for the game only; the three subprojects have no CI.
  `docs/gitops-pipeline.md` describes a Tekton flow that is not installed.

**Findings from steps 2–3** (all seen live on 2026-11-11).
- **Pin the remote base by full commit sha.** Argo CD tracks the pointer repo's revision
  (progressive-delivery), so a branch ref on the base only re-fetches on a hard refresh; and
  the repo-server's `git fetch --depth=1` cannot resolve a short sha at all (build error
  "couldn't find remote ref"). Every deploy-tree change lands as a bump of the pinned full
  sha in `workloads/wind-turbine/`, the same convention as image digests.
- **This operator's `ArgoCD` CR takes the Lua health check as a plain string**
  (`spec.resourceHealthChecks[].check`), not the `lua.luaCfg` nesting other versions document;
  the CRD rejects the nested form. And read the operators' real conditions: the Infinispan
  operator writes `WellFormed` with a capital W, and this AMQ Streams version (3.2.2) publishes
  no conditions on `KafkaNodePool` at all — the pool's check falls back to counting assigned
  node ids against `spec.replicas`, while the Kafka CR reports the brokers' readiness.
- **Do not `oc delete` a finalizer-guarded operator CR under Argo self-heal.** Self-heal
  recreated the Kafka CR about a second after deletion, before Strimzi's graceful shutdown
  ran: the new CR generated a new cluster CA, the still-running brokers kept the old CA's
  certificates, the operator's AdminClient failed every handshake, could not determine the
  active controller, and the CR sat `NotReady` forever. Recovery is namespace-level: delete
  the namespace, release the topic-operator finalizers (`strimzi.io/topic-operator` on the
  five KafkaTopics — they block namespace deletion once the entity operator is gone), then
  re-sync. Deleting the Infinispan CR or a topic individually self-heals cleanly in under a
  minute; only Kafka wedges.
- **An exhausted sync never recovers on its own.** When a sync fails through its 15 retries
  (here: "unable to create new content in namespace ... because it is being terminated"), the
  application goes dormant and stays OutOfSync even after the cause clears. Happened three
  times this session. Recovery: trigger a sync by patching `.operation` on the Application
  (`oc patch application <name> --type merge -p '{"operation":{"sync":{"prune":true}}}'`) or
  the `argocd` CLI. Budget for this whenever an app's sync fails through a transient state.
- **`oc` sessions expire mid-session** on this cluster (OAuth token). Any long poll should
  treat command failure as "check the session first", not as an empty reading — a blind loop
  once read "namespace gone" for 20 minutes while every call was actually Unauthorized.
- **VSO stops retrying a failed sync long before the cause clears** (step 4). After the Vault
  restart the three existing `VaultStaticSecret`s sat on a stale failure condition with an
  unchanging retry horizon for over an hour, and annotating the objects did not wake them;
  the login itself worked (tested directly with a service account token). A restart of the
  `vault-secrets-operator-controller-manager` deployment flushes the backoff. The same
  "stuck daemon" shape as the dormant Argo sync above.
- **VSO needs per-namespace token-request RBAC** (step 4): the operator requests short-lived
  tokens for the service account named in `VaultAuth`, and without a `Role` granting
  `create` on `serviceaccounts/token` for that account it falls back to its own identity and
  Vault answers the login with 403. `deploy/base/vso-rbac.yaml` carries the grant for the
  namespace `default` account; a new namespace needs the same.
- **The progressive-delivery bootstrap renamed a key**: the live `kubernetes-agent` secret
  holds `analysis_strong_api_key` while the current `bootstrap.java` writes
  `analysis_second_opinion_api_key`; the re-seed mapped the value across so the escalation
  tier keeps its key.

**Design.**
- `deploy/` in this repo, Kustomize `base/` plus `overlays/openshift-cluster/`, hand-written
  Deployments for the game and the three subprojects. The SVID wiring (CSI volume, `svid` volume,
  init container, sidecar) is a Kustomize **component** applied to race-ops, race-analyst and
  race-creative; the Keycloak token volume is a second component for race-creative. The env that
  the script bakes in with `-D…env.vars…` becomes plain `env` and `envFrom` in the overlay.
  `GAME_AI_ENABLED`/`commentary-openai` is a normal optional `envFrom`.
- The profiles stay build-time: `openshift-cluster,mtls` must be set when the image is built
  (sub-task 6 finding), so the GitHub Actions job builds the subprojects with
  `-Dquarkus.profile=openshift-cluster,mtls`. Document that the default (non-mtls) image is a
  different build.
- One Argo CD Application in `progressive-delivery` (`workloads/wind-turbine/` containing only
  the Application/Kustomization that points at this repo's `deploy/` overlay, picked up by the
  `workload-apps` ApplicationSet). Order inside it with sync waves: namespace (-20); operand CRs
  (Kafka, Infinispan, SPIRE operands, `ClusterSPIFFEID`) with
  `argocd.argoproj.io/sync-options: SkipDryRunOnMissingResource=true` (-10); secrets, service
  accounts, Services, Routes (0); the Keycloak configuration Job as a hook (5); Deployments (10).
  The operators themselves (Subscriptions) are optional and live under a separate `platform/`
  path, because this cluster already has them.
- Stock Argo CD has no health checks for the Strimzi, Infinispan, Keycloak and SPIRE CRs [A], so
  add Lua `resourceHealthChecks` to the `ArgoCD` CR in `progressive-delivery`, or a wave advances
  before Kafka is Ready.
- Keycloak: `KeycloakRealmImport` creates the realm only; the identity providers, flow, federated
  clients, role grants and event config are a hook Job that re-applies on every sync
  (`hook-delete-policy: BeforeHookCreation`). Tool: keycloak-config-cli first, Terraform as the
  fallback if the dry run on a scratch realm shows missing support.
- The `game-admin` secret must also exist in the `keycloak` namespace: a second
  `VaultStaticSecret`.
- The new image digest reaches the cluster by a commit to the overlay's `images:` block (the
  `progressive-delivery` convention); Argo CD Image Updater stays off.

**Todo list.** Each step leaves a working deployment; the script is the fallback until step 9.
Effort: S small, M medium, L large. Test in a second namespace (for example
`wind-turbine-gitops`) so the live demo namespace stays untouched until step 9.
- [x] 1 (S) Decide the repo, the build owner, Quarkus manifests, Vault and Rollouts. Done above.
- [x] 2 (S) Create `deploy/` with the namespace, service accounts and Routes, and the Application
      in `progressive-delivery`. Verify: Synced and Healthy. Done 2026-11-11: `deploy/base`
      (the three agent service accounts, two hostless routes) plus
      `overlays/openshift-cluster` and `overlays/test`; the overlays pin the route hosts, so
      the race-ops console keeps path-routing on the game's host. The tree is pushed on the
      `gitops-deploy` branch of `kdubois/quinoa-wind-turbine` — `kdubois/main` has diverged
      from local main (the running cluster image was built from the remote's `e6bb8c1`, which
      is not in local history), so the branch touches neither — and `workloads/wind-turbine/`
      in progressive-delivery points at it as a remote Kustomize base. A remote base climbs
      to `../../base` without tripping the load restrictor (verified with `oc kustomize`
      against the pushed ref: kustomize scopes the restrictor to the cloned repo). The
      Application `wind-turbine` is Synced/Healthy in `wind-turbine-gitops` (namespace wave
      -20, three service accounts, two admitted routes), a deleted service account self-heals
      in about 20 s, and the live namespace is untouched.
- [x] 3 (M) Kafka, topics and Infinispan with waves, `SkipDryRunOnMissingResource` and the Lua
      health checks. Verify: Kafka Ready and Infinispan `wellFormed` after a delete and re-sync.
      Done 2026-11-11: `deploy/base/kafka.yaml` (from `openshift.cluster.yml` minus its Route)
      and `deploy/base/infinispan.yaml` (the CR only), both wave -10 with
      `SkipDryRunOnMissingResource`; the test overlay carries `infinispan-credentials` inline
      until step 4 replaces it with a `VaultStaticSecret`. The Lua health checks live in the
      `ArgoCD` CR (`bootstrap/overlays/default/openshift-gitops-argocd.yaml` in
      progressive-delivery, synced by the `gitops-controller` system app), and the remote base
      in `workloads/wind-turbine/` is pinned to the full sha of the `gitops-deploy` commit.
      Verified: Kafka Ready, node pool healthy, five topics Ready, Infinispan wellFormed, app
      Synced/Healthy; a deleted Infinispan CR and KafkaTopic self-heal in under a minute; and
      after deleting the whole namespace (with the topic finalizers released), one manual sync
      rebuilt everything from Git alone — namespace, Kafka, topics, Infinispan, service
      accounts and routes, Synced/Healthy again. See the findings below.
- [x] 4 (M) Re-seed Vault and add the `VaultStaticSecret`s (`game-admin` in two namespaces,
      `game-session`, `infinispan-credentials`, optional `commentary-openai`). Verify: the same
      keys exist as the script creates. Done 2026-11-11: `bootstrap/bootstrap.java --vault-only`
      re-seeded the dev-mode Vault (its kubernetes auth, roles and KV data were gone with a
      restart — the operator pod had restarted six times), with the live secret values harvested
      from the cluster so nothing changed functionally; `scripts/seed-vault.sh` in this repo
      writes the game's four secrets to `secret/wind-turbine/*` plus the `wind-turbine` policy
      and role (the namespace `default` service account in `wind-turbine` and
      `wind-turbine-gitops`), reading values from the live namespace so a re-seed keeps what the
      deployments use. `deploy/base` gained the `VaultConnection`, `VaultAuth`, the VSO
      token-request `Role`/`RoleBinding` and four `VaultStaticSecret`s; the test overlay's
      inline `infinispan-credentials` is gone (the VSS replaced it, and the Infinispan CR stayed
      `WellFormed` through the swap). Verified: all four secrets exist in
      `wind-turbine-gitops` with the script's keys plus VSO's `_raw` hmac artifact, and their
      values are byte-identical to the live namespace's. The `game-admin` copy for the
      `keycloak` namespace moves to the live overlay at step 9: syncing it from the test app
      would adopt the live Keycloak's secret. See the findings.
- [x] 5 (M) GitHub Actions builds all four images (`build.yml` covers only the game today),
      with the `mtls` profile for the subprojects, and writes the digests into the overlay.
      Verify: the digest in Git is the pushed image. Done 2026-11-11, verified end to end:
      the run builds and pushes all four in about five minutes (the agents in two to three,
      the game's test suite is the long pole), the `pin-digests` job committed the digests to
      both overlays, and all four digests in Git match the registry's
      `Docker-Content-Digest` for the pushed tag. All four `ghcr.io/redhat-developer-demos`
      packages are public, and a throwaway pod on the cluster pulled a pinned digest and ran
      it. Built as a second workflow,
      `.github/workflows/images.yml`, next to the game's public image pipeline rather than in
      it: four matrix builds (game on `openshift-cluster`, the agents on
      `openshift-cluster,mtls`, because client-auth is build-time) that test before pushing,
      and a `pin-digests` job that records the pushed digests in both overlays' `images:`
      blocks — pre-created with placeholder digests, two lines per image, the shape the pin
      job's rewrite matches. Trigger: pushes to `gitops-deploy` filtered to source paths;
      GITHUB_TOKEN pushes trigger no workflows, so the digest commit cannot loop.
      **The branch lives in the upstream repo** (`redhat-developer-demos/quinoa-wind-turbine`,
      based on `origin/main`, which local main matches exactly — only the fork's main had
      diverged): the overlays pin `ghcr.io/redhat-developer-demos/*` digests, the Argo CD
      remote base follows in progressive-delivery, and the fork's `gitops-deploy` branch is
      deleted. The first run in the fork caught two things: the subprojects had **no
      container-image extension** (their cluster deploys are s2i BuildConfigs, so the CI flags
      were inert and no agent image was pushed) and adding
      `quarkus-container-image-podman` unconditionally **breaks the deploy script** —
      `quarkus-openshift` already brings `quarkus-container-image-openshift`, and two
      container-image extensions without an explicit builder fail the build. The game's own
      pattern fixes both: each subproject now carries the podman extension *and*
      `quarkus.container-image.builder=openshift` in application.properties, so the script's
      s2i default is unchanged while CI overrides the builder with `-D`. The second run then
      failed for want of a `src/main/docker/Dockerfile.jvm` — the podman builder builds from
      one, and the subprojects never had any because s2i needs none — so each now carries the
      game's Dockerfile shape with its own port (verified with a real local `podman build`;
      the earlier local check had passed only because it skipped
      `container-image.build=true`). At step 9, when the script retires, the s2i extension,
      the builder default and the internal-registry profiles go with it.
      Open: none. Pin bumps: always `git rev-parse` the full sha before writing it — one bump
      this session pinned a mistyped sha, caught because the fix landed before the app's
      refresh cycle reached the bad commit (an app refreshes straight to HEAD, so a superseded
      bad pin never renders).
- [x] 6 (M) Hand-written Deployments for the game and the three subprojects, first without
      OIDC. Verify: the game runs 5/5 and a redeploy keeps its env. Done 2026-11-12, verified
      on the cluster: the game runs 5/5 with the health probes green and all three
      secret-sourced env vars in the pod (`game-admin`, `game-session`,
      `infinispan-credentials` through the Vault-synced secrets) — and a redeploy keeps them,
      because the env is in Git, not in a post-deploy `oc set env`. Built 2026-11-11: one
      file per app in `deploy/base` (Deployment plus Service, the Deployments at sync wave
      10), mirroring the live deployments' ports, resources, replica counts and the game's
      probes. The image fields use a `:latest` placeholder that the overlays' `images:`
      blocks replace with the pinned digest (verified with `oc kustomize`). The game reads
      `game-admin`, `game-session` and `infinispan-credentials` from the Vault-synced secrets
      and `commentary-openai` through an optional `envFrom`; the agents run
      `QUARKUS_PROFILE=openshift-cluster` — not `openshift-cluster,mtls` — because without
      the mtls profile at runtime there is no TLS configuration, the baked-in client-auth is
      inert, and the mesh URLs in that profile reach the plain-HTTP specialists; the SVID
      volumes and the Keycloak and OIDC env follow in steps 7 and 8. No `JAVA_APP_JAR` env
      (the images set it).
      The agents got `quarkus-smallrye-health` with the **management interface**
      (`%openshift-cluster.quarkus.management.enabled=true`, port 9000) instead of the
      stopgap tcpSocket probes: HTTP health checks see a wedged app that still listens, and —
      the forward-looking reason — kubelet cannot present the client certificate the mtls
      profile will require on the main port, which is almost certainly why the live mTLS
      rollout ended up with no probes at all. The probes are httpGet on 9000, off the TLS
      port and off `CallerAllowList`'s router (the management interface has its own); dev and
      tests keep the endpoints on the main port. Verified locally: booted race-analyst on the
      cluster profile, the card on 8082 and `/q/health/ready` and `/live` on 9000 all answer
      200.
      Two things the rollout then taught: **the specialist images cannot run without TLS**
      (`quarkus.http.insecure-requests=disabled` is baked at build time with the mtls profile,
      so the first rollout crash-looped with "Cannot set quarkus.http.insecure-requests
      without enabling SSL" — race-ops boots plain because its %mtls block is client-side
      only), which pulled the SVID wiring into this step: a shared `svid-fetch` ConfigMap
      carries the fetch script once, and each agent Deployment gets the CSI Workload API
      socket, an in-memory SVID volume, an init container and a renewing sidecar, and runs
      `openshift-cluster,mtls`. And **the mesh code had never been committed**: the branch's
      images carried the mtls profile's configuration but none of the classes
      (`CallerAllowList`, `MeshTls`, `SvidSslContext` existed only in the working tree), so
      the deployed specialists' allowed-caller env was inert. The commit that brings them also
      adds a Block Secrets ignore rule (`.hashicorp/vault-radar/ignore.yaml`) for the three
      self-signed X.509 fixture directories, which the scanner flags otherwise. The allowlist
      now matches the **SPIFFE workload path** (`ns/<namespace>/sa/<service account>`) rather
      than the full ID — the specialists' trust store is the SPIFFE bundle alone, so the
      handshake already pins the trust domain, and no manifest or script hardcodes the
      cluster's apps domain (the deploy script builds the same shape); a new test pins the
      semantics, including that the same path under another trust domain is accepted.
      A third rollout lesson: **the langchain4j MCP client health check gates readiness on a
      designed-degraded state** — without the OIDC client (no Keycloak until step 8) the admin
      MCP client cannot initialize and its check reports DOWN, so the pods never become ready.
      The extension's own `quarkus.langchain4j.mcp.health.enabled` toggle is inert here: the
      key is shadowed by the clients map under the same prefix (no warning, no effect —
      established by reading `McpProcessor` and the generated `$$CMImpl` bytecode), so
      `quarkus.arc.exclude-types=…McpClientHealthCheck` in race-ops and race-creative does it
      instead; DirectorHealth and the console keep the signal where it belongs. Verified
      locally: `/q/health/ready` on the management interface answers UP with no checks.
- [x] 7 (M) The SVID and token volumes as Kustomize components. Verify: the SVID files appear
      and the A2A mTLS call works (reuse the checks in sub-task 6). Done 2026-11-12, absorbed
      into step 6 when the specialist images proved unable to boot without TLS: the SVID wiring
      (CSI volume, in-memory svid volume, init container, sidecar, as a shared `svid-fetch`
      ConfigMap rather than a Kustomize component, since the wiring is mandatory and not
      per-overlay) is in `deploy/base` now. Verified on the cluster: the SVID files appear in
      every agent pod, race-ops logs `A2A calls present the SPIFFE SVID`, an in-pod mTLS call
      from race-ops to race-analyst answers 200 and the wrong-path caller gets 403 with one
      rejection logged. One gotcha for the check itself: `https://race-analyst/…` defaults to
      port 443 — the Services publish 80, so the URL says `:80` (the plan's own note). The
      Keycloak projected token volume for race-creative moved to step 8, where the OIDC env
      that needs it arrives.
- [x] 8 (L) The Keycloak hook Job, dry-run against a scratch realm first. Verify: a re-sync is a
      no-op, and a deleted client comes back. **Riskiest step**: support for `federated-jwt` and
      the two provider types in the tool, and the realm-import roles behavior on 26.6.9.
      Dry run done 2026-11-12 against a scratch realm on the cluster Keycloak (RHBK
      26.6.9.redhat-00002): **keycloak-config-cli 6.5.1 handles all of it — no Terraform
      fallback needed.** The `federated-jwt` client lands with `clientAuthenticatorType` and
      both `jwt.credential.*` attributes, the `kubernetes` and `spiffe` identity providers
      import (spiffe needs the feature on the target Keycloak, which the cluster's has and a
      plain dev one does not — a dry run there answers "Invalid identity provider id
      [spiffe]"), and the tool logs no version complaint against 26.6.9. Findings:
      - **The checksum skip must be off**: by default the tool stores
        `de.adorsys.keycloak.config.import-checksum-<key>` in the realm's attributes and
        skips unchanged files — idempotent re-runs are free, but a deleted client does NOT
        come back. `--import.cache.enabled=false` makes every run reconcile (verified: the
        deleted client returned with the right authenticator and subject). This is the flag
        the GitOps hook needs; the property is not `import.state` or `import.remote-state`.
      - **The flow copy is not needed for fresh realms on this Keycloak generation**: the
        built-in `clients` flow already carries `federated-jwt` as an ALTERNATIVE (checked on
        a fresh realm's executions). The copy in the script exists only for realms that
        predate the feature; the live realm was already migrated by the script, so a hook
        config without `authenticationFlows` is a no-op against it — exactly the desired
        first-run behavior. The tool does support declaring flows if ever needed.
      - The scratch realm is deleted; no local credential copies remain.
      Two Argo wiring findings from bringing the hook live: **a commit that changes only hook
      resources never triggers a sync** — hooks are not diffed, and with
      `ApplyOutOfSyncOnly` and nothing else out of sync there is no sync operation for them
      to run in, so every pure-hook change needs a manual sync nudge (the same
      `.operation` patch as the dormant-app recovery); and **`$(VAR)` in container args
      expands only from `env` entries, not `envFrom`** — the hook's first runs failed with
      "Target host is not specified" until the three Keycloak values moved to `secretKeyRef`
      env entries.
      Built and pushed: `deploy/base/keycloak-hook.yaml` (Job, wave 5,
      `hook-delete-policy: BeforeHookCreation`), `deploy/base/keycloak-config.json` (IdPs,
      the two federated clients, the admin grants, the login events — written from the live
      realm's actual state, so its first run is a no-op), a `keycloak-config` ConfigMap with a
      stable name, a `keycloak-admin` `VaultStaticSecret` fed by
      `scripts/seed-vault.sh` (`live_keycloak_admin()` harvests `keycloak-initial-admin` from
      the `keycloak` namespace). The exact hook invocation, run locally against the live
      realm, changed nothing and left both clients' subjects intact.
      Remaining: the drift test through Argo (delete a federated client, nudge a sync, watch
      the hook restore it), then the OIDC env for the apps (step 9's live switch, or
      test-scoped clients if the test namespace needs working admin tools first).
      **Verified through Argo on 2026-11-12**: the hook ran at wave 5 and imported the realm
      in 2.6 s with no changes (the no-op first run); then `race-creative`'s client was
      deleted from the live realm — which also took its service-account user with it — and the
      next sync recreated the client with `federated-jwt`, the `system:serviceaccount:
      wind-turbine:race-creative` subject, the audience mapper, and the `admin` role grant on
      the recreated service-account user. Step 8's acceptance checks pass. Two more Argo
      findings: **`prune` deletes the completed hook Job**, so a `Synced` app with no
      `keycloak-config` Job is normal after any later sync and is not evidence the hook never
      ran (read `status.operationState`); and **a nudge patch can be swallowed** while the
      previous operation is still recorded — the patch returns the old phase, so check that
      `operationState.startedAt` actually moved before waiting on the hook.
      One recurrence worth a guard: the pointer's `ref=` needs the **full** sha — a short one
      broke manifest generation (`kustomize build` → shallow `git fetch` fails) and the app sat
      at "Unknown" until the pin was corrected (2026-11-12, second time it happened).
      **A separate bug found while checking the test namespace's dashboard**: the director
      console rendered an empty `div` whenever race-ops answered but had no engagement — the
      same blank space as the kill beat, so an idle director looked like a stopped one. The
      status line now always renders while the poll succeeds and reads "watching, no race
      yet" before the first snapshot (`820c2cf`; verified live in the served `App` chunk of
      the test namespace, game digest `616c04a6cc30`, 5/5 ready).
- [ ] 9 (M) Operators as an optional platform path, retire or shrink the script, switch the live
      namespace. **Ask the user first**: it replaces all Deployments. Verify: a clean namespace
      comes up from Git alone.
- [ ] 10 (M each, optional, bonus) Blue-green Rollouts for race-analyst, then race-creative and
      race-ops.

**Relevant context.** `scripts/deploy-openshift.sh`, `scripts/mount-spiffe-svid.sh`,
`src/main/kubernetes/**`, `.github/workflows/build.yml`, `docs/gitops-pipeline.md`, the
`progressive-delivery` repo (`components/applicationsets/`, `system/vault-config`, its bootstrap
program).

**Status.** `[-] steps 1–8 of 10 done; next is step 9` (the live namespace switch, gated on
explicit user approval — it replaces every live Deployment)

## Hand-off: where things stand (2026-11-11, end of day)

**Read first.** `AGENTS.md` (project rules), this file, and for any race
`.agents/skills/wind-turbine-mcp-demo/SKILL.md` (call `use_skill("wind-turbine-mcp-demo")`
before a race; the Ferrari boost is secret, Ferrari needs `"logo":"ibm"`).

**Cluster** (`https://api.cluster-dnw9g.dyn.redhatworkshops.io:6443`, `oc` logged in as admin;
game route `https://quinoa-wind-turbine-wind-turbine.apps.cluster-dnw9g.dyn.redhatworkshops.io`):
- Namespace `wind-turbine`: game (5 replicas), race-ops (mTLS + `RACE_OPS_AUTH=spiffe`),
  race-analyst and race-creative (mTLS, one replica each, all 2/2). Theme `ferrari-mercedes`,
  race-32 FINISHED, no pending director card. The SPIRE trust domain is
  `apps.cluster-dnw9g.dyn.redhatworkshops.io`.
- Namespace `wind-turbine-gitops`: the Argo CD side of sub-task 8 steps 2–3. The Application
  `wind-turbine` (ApplicationSet `workload-apps`) builds
  `kdubois/quinoa-wind-turbine/deploy/overlays/test` at the full sha pinned in
  `workloads/wind-turbine/kustomization.yaml`; Synced and Healthy with Kafka, the five topics,
  Infinispan, the agent service accounts and the routes. Scratch: delete the namespace (release
  the `strimzi.io/topic-operator` finalizers on the KafkaTopics first) and re-sync the app to
  remove or rebuild it.
- Git state for sub-task 8: `deploy/` lives on branch `gitops-deploy` of
  `kdubois/quinoa-wind-turbine` (pushed 2026-11-11, `8dcaf3d`), not on main — `kdubois/main`
  and local main have diverged, so decide how to reconcile them before step 9 flips the app to
  `deploy/overlays/openshift-cluster` on main. The working tree still carries the same
  `deploy/` files untracked, so local edits continue there and get committed to the branch.
- The admin MCP server `wind-turbine-admin-cluster` needs the user's browser login (developer);
  it expires. Ask the user to authenticate when a tool answers `Unauthorized`.
- Keycloak 26.6.9 with the `spiffe` feature; realm `wind-turbine` has IdPs `openshift-sa` and
  `spire`, clients `race-ops` (SPIFFE sub) and `race-creative` (service account sub).
- Rollback for race-ops auth (rehearsed): set the client's `jwt.credential.issuer`/`sub` to the
  service account, set `QUARKUS_OIDC_CLIENT_CLIENT_ID` to
  `system:serviceaccount:wind-turbine:race-ops`, remove the `…_JWT_SOURCE`/`…_TOKEN_PATH` env vars.
  Rollback for mTLS: rebuild the three apps without the `mtls` profile and without `--x509`.

**Uncommitted work in the repo** (review with `git status`): the pre-session sub-task 5 and 6
work beyond what this session committed to the `gitops-deploy` branch — `AGENTS.md`, `docs/`,
`.bob/mcp.json`, the realm and SPIRE manifests under `src/main/kubernetes/`, and this plan
file, which stays uncommitted by choice. Everything deployment-relevant (mesh code, agent
properties, Dockerfiles, the deploy tree, the image workflow) and the phone team-name fix
(2026-11-12, `befc4df` on the branch; the game image carries it after the next CI rebuild) is
on `gitops-deploy` in `redhat-developer-demos/quinoa-wind-turbine`.
The subproject test suites pass (race-analyst 20, race-creative 28, race-ops 95); the game suite was not
re-run this session.

**Not done / watch out.**
- `A2A_MTLS=true scripts/deploy-openshift.sh` has not been run end to end; the rollout used a
  throwaway copy of its steps (`/tmp/rollout-mtls.sh`, outside the repo, may be gone).
- Sub-task 4 left race-analyst's service account in the repo but the first build that uses it is the
  mTLS rollout, which did run (the pod exists).
- Do not run a full `scripts/deploy-openshift.sh` or replace the live Deployments without asking the
  user.
- `race-ops.*` is a config mapping that rejects unknown keys; new race-ops settings belong in
  `RaceOpsConfig` itself (the mesh-tls directory is `race-ops.mesh-tls.directory` there). The
  unit tests never run the `mtls` profile, so check a profile change on the cluster or boot it
  locally against fixture certificates (see the sub-task 6 findings for how).
- A pending director card blocks new proposals. Decide each card before the next race
  (`POST /ops/api/ops/proposals/{id}/dismiss` on the race-ops route, or the console).
- This plan file was overwritten once by something outside the session (edits made earlier the
  same day disappeared and had to be redone). Check `git diff`-style that your edits are still there
  after a long task, and keep a copy before large edits.
- docs.redhat.com blocks web fetches; avoid `web_fetch` on big Quarkus guides (huge output).

## Risks and open decisions

- **Shared Keycloak.** The upgrade also affects the `sso` realm used by cluster login. Decide
  in sub-task 2 whether to proceed on the shared instance.
- **Preview in the critical path.** SPIFFE client authentication is a preview in Keycloak;
  keep the service account token mechanism working as the fallback.
- **SPIFFE authenticates, it does not authorize.** The game's admin permission still rests on
  the Keycloak `admin` role; the plan does not add on-behalf-of user context.
- **SVIDs are bearer credentials.** An SVID stolen from a pod can be replayed until it
  expires; short lifetimes are the mitigation here.
- **Realm created before the feature.** The `wind-turbine` realm may need the flow edit
  described above; check this before assuming the client setting alone is enough.
- **The dev Keycloak container can serve a stale realm.** Keycloak's single-file realm
  import skips an existing realm wholesale, and Dev Services with container reuse keeps a
  container across dev-mode restarts — so a realm-file change (new redirect URIs, for
  instance) never lands in a long-lived container, and Bob's browser login fails with
  "Invalid parameter: redirect_uri" when it lands on a fallback callback port. Seen
  locally on 2026-11-12 with a three-day-old container predating sub-task 1's
  redirect-URI range. Fix it through the Admin API (PUT the client's redirectUris, like
  sub-task 1 did for the cluster realm) or delete the container and let a restart
  re-import the file.
- **Hot reload.** race-ops and race-creative cannot hot-reload while their MCP client is
  connected (AGENTS.md known issues); restart dev mode between changes.
