# OpenShift installation

This section covers the full cluster setup for the demo.

To run this demo you need **OpenShift >=4.10** with cluster-admin privileges.

## Setup OpenShift

Login to OpenShift Web Console to install prerequisites.

First, create a project for the demo:

```bash
oc new-project demo --description='wind-turbine-race'
```

You can choose a different name, but then you have to update the Argo CD application manifest before you deploy the app.

## Install Operators

Install the following operators from OperatorHub:

- OpenShift Pipelines
- OpenShift GitOps
- AMQ Streams
- Red Hat build of Keycloak, if you want the admin MCP tools (see [Keycloak for the admin MCP tools](#keycloak-for-the-admin-mcp-tools))

For Infinispan, create an infinispan cluster from the Web Console using the "Infinispan Helm chart" and name it `infinispan`.

### OpenShift GitOps

Once OpenShift GitOps is installed, give permission to the Argo CD service account to control the cluster:

```bash
oc adm policy add-cluster-role-to-user cluster-admin -z openshift-gitops-argocd-application-controller -n openshift-gitops
```

After the operator is installed, an instance of Argo CD is created in the `openshift-gitops` namespace and can be opened from the application launcher in the OpenShift Web Console.

Log into Argo CD using the OpenShift Auth option.

## Kafka and Infinispan

Install AMQ Streams from OperatorHub and create a KafkaCluster named `my-cluster`.

The game needs the `power`, `game-events` and `race-progress` topics before it starts, because the Kafka Streams topology waits for its input topics. With the `openshift-cluster` profile, the `KafkaTopic` resources in [`openshift.cluster.yml`](../src/main/kubernetes/openshift.cluster.yml) create them when you deploy. `race-progress` is compacted, since each replica only needs the latest race snapshot.

With the `confluent` profile, Confluent Cloud doesn't create topics on first use, so create them with the Confluent CLI:

```bash
confluent kafka topic create power --partitions 1
confluent kafka topic create game-events --partitions 1
confluent kafka topic create race-progress --partitions 1 --config cleanup.policy=compact
```

Kafka Streams also creates changelog topics for its state stores. The `confluent` profile sets their replication factor to 3, which Confluent Cloud requires, and the API key needs permission to create topics with the `quinoa-wind-turbine-` prefix.

Create an infinispan cluster from the Web Console using the "Infinispan Helm chart" and name it `infinispan`.

## Dashboard login

Without any configuration, the dashboard user is `developer` with the existing workshop password, which the app checks against the digest in `application.properties`. Because that digest is in a public repository and the password is shared across every deployment, give each deployment its own password when you can.

To do that, store the password in a secret and pass it to the app as `GAME_ADMIN_PASSWORD`, which replaces the digest:

```bash
oc create secret generic game-admin --from-literal=password=<choose a password>
oc set env deployment/quinoa-wind-turbine --from=secret/game-admin --prefix=GAME_ADMIN_
```

`--prefix` turns the secret's `password` key into the `GAME_ADMIN_PASSWORD` variable.

For projects created from the software template, the Helm chart in `manifests/helm/app` owns the deployment, so it creates the secret and sets the variable. The template publishes the gitops repository as public, so the password isn't in the chart values. The chart generates one per namespace with a Vault `RandomSecret` and `VaultSecret`, the same way the build chart creates the webhook secret, and `deployment.yaml` reads it:

```yaml
            - name: GAME_ADMIN_PASSWORD
              valueFrom:
                secretKeyRef:
                  key: password
                  name: game-admin
```

Presenters read the password from the `game-admin` secret in their namespace:

```bash
oc get secret game-admin -o jsonpath='{.data.password}' | base64 -d
```

## Keycloak for the admin MCP tools

The read-only MCP tools at `/mcp` need no login. The admin tools at `/admin/mcp` start, pause and reset the race, and they need a bearer token with the `admin` role from Keycloak. Without Keycloak the app still starts, and `/admin/mcp` rejects every call. These steps set up the Red Hat build of Keycloak in the demo namespace, with the same `wind-turbine` realm that Keycloak Dev Services uses in dev mode.

Install the Red Hat build of Keycloak operator from OperatorHub into the demo namespace.

Keycloak keeps its data in a small PostgreSQL database with a 1 GiB volume. It needs a database because the realm import runs only once: with the file-based dev database in the Keycloak pod, a restart would lose the realm and nothing would import it again. Create the database credentials:

```bash
oc create secret generic keycloak-db --from-literal=username=keycloak --from-literal=password=<choose a password>
```

The realm's `developer` user gets its password from the `game-admin` secret described in [Dashboard login](#dashboard-login), so the dashboard and the MCP login use the same password. Create that secret first if you haven't.

Then create PostgreSQL, Keycloak and the realm import. The file is an OpenShift template that takes Keycloak's external host name, which must be reachable from the laptops that run the MCP clients:

```bash
oc process -f src/main/kubernetes/keycloak/keycloak.yaml \
  -p KEYCLOAK_HOSTNAME=keycloak-demo.apps.<cluster domain> | oc apply -f -
```

The operator creates a route for that host name with edge TLS. Wait until the import has finished:

```bash
oc get keycloakrealmimports wind-turbine -o jsonpath='{.status.conditions}'
```

The Keycloak admin console login is in the `keycloak-initial-admin` secret.

Finally, point the app at the realm:

```bash
oc set env deployment/quinoa-wind-turbine GAME_OIDC_ENABLED=true \
  GAME_OIDC_URL=https://keycloak-demo.apps.<cluster domain>/realms/wind-turbine
```

The app validates the issuer of every token, so `GAME_OIDC_URL` must use the same external host name that the MCP clients get their tokens from. The app reaches it through the cluster router, which needs a certificate the app trusts. Redeploying the app with the Quarkus OpenShift extension replaces the deployment and drops variables set with `oc set env`, so either set them again or add them to the build, for example with `-Dquarkus.openshift.env.vars.game-oidc-enabled=true`.

For projects created from the software template, the Helm chart does all of this when `keycloak.enabled` is `true` in `manifests/helm/app/values.yaml`. Turn it on only on clusters where the operator is installed, because Argo CD can't sync the Keycloak resources without it.

## SSL

If the cluster does not have a signed certificate, install Let's Encrypt support:

```bash
oc apply -f https://raw.githubusercontent.com/tnozicka/openshift-acme/master/deploy/single-namespace/{role,serviceaccount,issuer-letsencrypt-live,deployment}.yaml
oc create rolebinding openshift-acme --role=openshift-acme --serviceaccount="$( oc project -q ):openshift-acme" --dry-run -o yaml | oc apply -f -
```

If the cluster already has a signed certificate, create a route with an `edge` TLS termination.