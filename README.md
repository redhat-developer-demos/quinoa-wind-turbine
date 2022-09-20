

# Quinoa WindTurbine Racer Demo

This project uses Quarkus, the Supersonic Subatomic Java Framework.

## Running the application in dev mode

Install first:
- JDK 17
- Maven
- Quarkus CLI

You can run your application in dev mode that enables live coding using:
```shell script
quarkus dev
```

## Deploy on OpenShift Sandbox

Create a new Openshift sandbox, then you need to log in from your terminal.

### Managed Kafka

1. Create Kafka instance
2. Add Access:
   1. All Accounts
   2. Add perms
   3. Consumer group is * allow All
   4. Topic is * allow All
   5. Save
3. Add topic `power`, `game-events` and `user-actions` with default config
4. In Openshift add "Red Hat OpenShift Streams for Apache Kafka"
5. Select instance
6. Deploy Windturbine app
7. Add link in Topology view
8. Start rollout on Windturbine Deployment config

### Infinispan server

1. Create an infinispan cluster in the sandbox console using the "Infinispan Helm chart"
2. Name it `infinispan`

### Generate certificate

```bash
openssl req -newkey rsa:2048 -new -nodes -x509 -days 3650 -keyout key.pem -out cert.pem
```

### Create Secret

```bash
oc create secret generic https-secret --from-file=cert.pem=./cert.pem --from-file=key.pem=./key.pem
```

### Copy sandbox openshift resource file 

```bash
cp src/main/kubernetes/openshift.sandbox.yml src/main/kubernetes/openshift.yml
```

### Deploy

```bash
quarkus build  -Dquarkus.kubernetes.deploy=true -Dquarkus.profile=openshift-sandbox
```

### Update:

```bash
quarkus build -Dquarkus.container-image.build=true -Dquarkus.profile=openshift-sandbox
```

### Delete deployed  app
```bash
oc delete all -l app.kubernetes.io/name=quinoa-wind-turbine
```

## Deploy on Cluster with admin rights

### SSL (if the cluster doesn't have a signed certificate):

Let'encrypt (if the cluster doesn't have a signed certificate):
```bash
oc apply -fhttps://raw.githubusercontent.com/tnozicka/openshift-acme/master/deploy/single-namespace/{role,serviceaccount,issuer-letsencrypt-live,deployment}.yaml
oc create rolebinding openshift-acme --role=openshift-acme --serviceaccount="$( oc project -q ):openshift-acme" --dry-run -o yaml | oc apply -f -
```

If the cluster has a signed certificate, create a route with an "edge" tls termination.

### Install Kafka

- From the operator hub, install Strimzi operator.
- Create a Kafka instance named `my-cluster`

### Infinispan server

1. Create an infinispan cluster in the console using the "Infinispan Helm chart"
2. Name it `infinispan`

### Copy sandbox openshift resource file

```bash
cp src/main/kubernetes/openshift.cluster.yml src/main/kubernetes/openshift.yml
```

### Deploy

```bash
quarkus build  -Dquarkus.kubernetes.deploy=true -Dquarkus.profile=openshift-cluster
```

### Update:

```bash
quarkus build -Dquarkus.container-image.build=true -Dquarkus.profile=openshift-cluster
```

### Delete deployed  app
```bash
oc delete all -l app.kubernetes.io/name=quinoa-wind-turbine
```
