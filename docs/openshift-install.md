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

For projects created from the software template, the Helm chart in `manifests/helm/app` owns the deployment, so the secret and the variable belong in the chart. Don't put the password in the chart values, because the template publishes the gitops repository as public. Instead, generate one per namespace with the Vault `RandomSecret` and `VaultSecret` resources that the build chart already uses for the webhook secret, and add the variable to `deployment.yaml`:

```yaml
            - name: GAME_ADMIN_PASSWORD
              valueFrom:
                secretKeyRef:
                  key: password
                  name: game-admin
```

Presenters then read the password from the `game-admin` secret in their namespace. Until the chart does this, template deployments use the digest default.

## SSL

If the cluster does not have a signed certificate, install Let's Encrypt support:

```bash
oc apply -f https://raw.githubusercontent.com/tnozicka/openshift-acme/master/deploy/single-namespace/{role,serviceaccount,issuer-letsencrypt-live,deployment}.yaml
oc create rolebinding openshift-acme --role=openshift-acme --serviceaccount="$( oc project -q ):openshift-acme" --dry-run -o yaml | oc apply -f -
```

If the cluster already has a signed certificate, create a route with an `edge` TLS termination.