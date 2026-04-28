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

Create an infinispan cluster from the Web Console using the "Infinispan Helm chart" and name it `infinispan`.

## SSL

If the cluster does not have a signed certificate, install Let's Encrypt support:

```bash
oc apply -f https://raw.githubusercontent.com/tnozicka/openshift-acme/master/deploy/single-namespace/{role,serviceaccount,issuer-letsencrypt-live,deployment}.yaml
oc create rolebinding openshift-acme --role=openshift-acme --serviceaccount="$( oc project -q ):openshift-acme" --dry-run -o yaml | oc apply -f -
```

If the cluster already has a signed certificate, create a route with an `edge` TLS termination.