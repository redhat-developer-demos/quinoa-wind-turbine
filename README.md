
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
   1. Go to console.redhat.com > Application And Data Services > Streams for Apache Kafka > Kafka Instances > Create Kafka instance
   2. Name doesn't matter, and you can use default settings
2. Once the cluster has been created, click on it and add Access:
   1. Go to 'Access' Tab > Manage access
   1. All Accounts
   1. Add permissions:
      1. `Consumer group is * allow All`
      2. `Topic is * allow All`
   1. Save
3. Add topics `power`, `game-events` and `user-actions` with default config
4. In Openshift add "Red Hat OpenShift Streams for Apache Kafka"
   1. If you don't have the rhoas cli client yet, get it here: <https://access.redhat.com/documentation/en-us/red_hat_openshift_application_services/1/guide/bb30ee92-9e0a-4fd6-a67f-aed8910d7da3#proc-installing-rhoas_installing-rhoas-cli>
   1. Log in to Red Hat Openshift Application Services:

      ```bash
      rhoas login
      ```

   1. Get an Api token at console.redhat.com/openshift/token
   1. Connect your sandbox to the Kafka cluster:

      ```bash
      rhoas cluster connect --token YOURTOKEN
      ```

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
quarkus build  -Dquarkus.kubernetes.deploy=true -Dquarkus.profile=openshift-sandbox -Dquarkus.container-image.group=[project name]
```

### Update:

```bash
quarkus build -Dquarkus.container-image.build=true -Dquarkus.profile=openshift-sandbox -Dquarkus.container-image.group=[project name]
```

### Delete deployed  app
```bash
oc delete all -l app.kubernetes.io/name=quinoa-wind-turbine
```

## Deploy on Cluster with admin rights

### Setup

To run this demo you need **OpenShift >=4.10** with cluster-admin privileges.

### Quay.io

#### Account

Create an account on [Quay.io](https://quay.io) if you do not already have one.

#### Repositories

Create two repositories with public access (pull), you will use credentials in the next step to push container images.

From right-side menu, click **Create New Repository**

Create a new repository:

* quinoa-wind-turbine

Flag it as **Public Repository** and click **Create Public Repository** 


#### Create secret

* Login to quay.io in the web user interface and click on your username in the top right corner.
* Select **account settings**.
* Click the blue hyperlink **Generate Encrypted Password**.
* Re-enter your password when prompted.
* Copy the password

![Create repo](https://github.com/blues-man/vote-app-gitops/raw/main/images/quay-encrypted-key.png)

### Setup OpenShift

Login to OpenShift Web Console to install prerequisites.

First, create a project for the demo:
```bash
oc new-project demo --description='wind-turbine-race'
```
*You can choose a different name, but then you have to update `argo/wind-turbine-app.yaml` before you deploy the app (see below in the **Flow** section).*

### Install Operators


#### OpenShift Pipelines

OpenShift Pipelines is provided as an add-on on top of OpenShift that can be installed via an operator available in the OpenShift OperatorHub. Follow these instructions in order to install OpenShift Pipelines on OpenShift via the OperatorHub.


From the left-side menu under **Administrator** perspective, go to **Operators**-> **OperatorHub**. In the search box, search for _pipelines_, then click to **OpenShift Pipelines Operator**:

![OperatorHub](https://redhat-scholars.github.io/openshift-starter-guides/rhs-openshift-starter-guides/4.7/_images/prerequisites_operatorhub.png)

From the description view, click *Install* to review all installation settings.

![Install Pipelines](https://redhat-scholars.github.io/openshift-starter-guides/rhs-openshift-starter-guides/4.7/_images/prerequisites_operatorhub_install_pipelines.png)

Ensure *Update Channel* is set to *stable* , and click *Install* to start installing the Operator.

![Install Operator](https://redhat-scholars.github.io/openshift-starter-guides/rhs-openshift-starter-guides/4.7/_images/prerequisites_operatorhub_install_operator.png)

After few seconds, the installation should be completed with success and you can verify it looking at *Status* column, check if the status is *Succeeded*.

![Pipelines Installed](https://redhat-scholars.github.io/openshift-starter-guides/rhs-openshift-starter-guides/4.7/_images/prerequisites_operatorhub_pipelines_installed.png)

#### OpenShift GitOps

Log into OpenShift Web Console as a cluster admin and navigate to the **Administrator** perspective and then **Operators** &rarr; **OperatorHub**. 

In the **OperatorHub**, search for *OpenShift GitOps* and follow the operator install flow to install it.

![OpenShift GitOps operator](https://raw.githubusercontent.com/siamaksade/openshift-gitops-getting-started/1.1/images/gitops-01.png)

![OpenShift GitOps operator](https://raw.githubusercontent.com/siamaksade/openshift-gitops-getting-started/1.1/images/gitops-02.png)

![OpenShift GitOps operator](https://raw.githubusercontent.com/siamaksade/openshift-gitops-getting-started/1.1/images/gitops-03.png)

##### Add permission to Argo CD service account

**IMPORTANT** Give permission to the Argo CD service account to control the cluster:
```bash
oc adm policy add-cluster-role-to-user cluster-admin -z openshift-gitops-argocd-application-controller -n openshift-gitops
```

Once OpenShift GitOps is installed, an instance of Argo CD is automatically installed on the cluster in the `openshift-gitops` namespace and link to this instance is added to the application launcher in OpenShift Web Console.

![Application Launcher](https://raw.githubusercontent.com/siamaksade/openshift-gitops-getting-started/1.1/images/gitops-04.png)

##### Log into Argo CD dashboard

Click on Argo CD from the OpenShift Web Console application launcher and then log into your OpenShift credentials using the OpenShift Auth option.

![Argo CD](https://raw.githubusercontent.com/siamaksade/openshift-gitops-getting-started/1.1/images/gitops-05.png)

![Argo CD](https://raw.githubusercontent.com/siamaksade/openshift-gitops-getting-started/1.1/images/gitops-06.png)

#### AMQ Streams

Install AMQ Streams from OperatorHub and create a KafkaCluster named `my-cluster`

#### Infinispan

1. Create an infinispan cluster from the Web Console using the "Infinispan Helm chart"
2. Name it `infinispan`

### Flow


Create a Secret with your Quay.io credentials with the encrypted password you copied before:

```bash
oc create secret docker-registry quay-secret --docker-server=quay.io --docker-username=<QUAY_USERNAME> --docker-password=<ENCRYPTED_PASSWORD>
```

Create a Secret with your GitHub Personal Access Token

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: git-user-pass
  annotations:
    tekton.dev/git-0: https://github.com
type: kubernetes.io/basic-auth
stringData:
  username: <github user>
  password: <github personal access token>
```
Save it to a file with your credentials and create the secret:

```bash
oc create -f git-user-pass.yaml
```

Link Secrets to pipeline Service Account.

NOTE: Pipelines Operator installs by default a `pipeline` Service Account in all projects. This service account is used to run non-privileged containers and builds across the cluster.  

```bash
oc secret link pipeline quay-secret
oc secret link pipeline git-user-pass
```

Fork this repo

In order to enable webhooks, fork this repo


Fork and clone GitOps [manifests repo](https://github.com/redhat-developer-demos/quinoa-wind-turbine-manifests)


```bash
git clone https://github.com/<yourgithubuser>/quinoa-wind-turbine-manifests
cd quinoa-wind-turbine-manifests
```

Create Tekton pipeline manifests

Change the GitOps repo to your fork:
```bash
sed -i 's/rhdevelopers/yourquayuser/g' tekton/pipeline-cached.yaml
sed -i 's/redhat-developer-demos/yourgithubuser/g' tekton/pipeline-cached.yaml
```

```bash
oc apply -f tekton/app-source-pvc.yaml 
oc apply -f tekton/build-cache-pvc.yaml 
oc apply -f tekton/git-update-deployment.yaml
oc apply -f tekton/maven-task-cached.yaml 
oc apply -f tekton/pipeline-cached.yaml 
oc apply -f tekton/triggerbinding.yaml
oc apply -f tekton/triggertemplate-cached.yaml
oc apply -f tekton/eventlistener.yaml
oc apply -f tekton/el-route.yaml

```

Update all references to quay.io with your repos for quinoa-wind-turbine references:

```bash
sed -i 's/rhdevelopers/yourquayuser/g' k8s/deployment.yaml
sed -i 's/redhat-developer-demos/yourgithubuser/g' argo/wind-turbine-app.yaml
git add .
git commit  -m "update reference to quay and github"
git push
```

Create Argo CD Application to deploy the game
```bash
oc apply -f argo/wind-turbine-app.yaml
```

Start the Pipeline or edit `Config.js` to switch to V2:

```js
export const ENABLE_SHAKING = true;
```


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

## When deploying from Quarkus

### Copy sandbox openshift resource file

```bash
cp src/main/kubernetes/openshift.cluster.yml src/main/kubernetes/openshift.yml
```

### Deploy

```bash
quarkus build  -Dquarkus.kubernetes.deploy=true -Dquarkus.profile=openshift-cluster -Dquarkus.container-image.group=[project name]
```

### Update:

```bash
quarkus build -Dquarkus.container-image.build=true -Dquarkus.profile=openshift-cluster  -Dquarkus.container-image.group=[project name]
```

### Delete deployed  app
```bash
oc delete all -l app.kubernetes.io/name=quinoa-wind-turbine
```
