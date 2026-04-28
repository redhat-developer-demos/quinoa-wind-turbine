# GitOps and pipeline setup

This section covers the GitOps and Tekton flow used to deploy the demo.

## Quay.io

Create an account on [Quay.io](https://quay.io) if you do not already have one.

Create a repository with public access that will be used to push the application image:

- `quinoa-wind-turbine`

To create an encrypted password, login to quay.io in the web user interface, click on your username in the top right corner, select **Account Settings**, click **Generate Encrypted Password**, re-enter your password when prompted, and copy the generated password.

## Secrets

Create a secret with your Quay.io credentials:

```bash
oc create secret docker-registry quay-secret --docker-server=quay.io --docker-username=<QUAY_USERNAME> --docker-password=<ENCRYPTED_PASSWORD>
```

Create a secret with your GitHub Personal Access Token:

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

Save it to a file and create the secret:

```bash
oc create -f git-user-pass.yaml
```

Link both secrets to the `pipeline` Service Account:

```bash
oc secret link pipeline quay-secret
oc secret link pipeline git-user-pass
```

## Fork repositories

Fork this repository in order to enable webhooks.

Fork and clone the GitOps [manifests repo](https://github.com/redhat-developer-demos/quinoa-wind-turbine-manifests):

```bash
git clone https://github.com/<yourgithubuser>/quinoa-wind-turbine-manifests
cd quinoa-wind-turbine-manifests
```

## Create Tekton pipeline manifests

Change the GitOps repo references to your own forks:

```bash
sed -i 's/rhdevelopers/yourquayuser/g' tekton/pipeline-cached.yaml
sed -i 's/redhat-developer-demos/yourgithubuser/g' tekton/pipeline-cached.yaml
```

Apply the Tekton resources:

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

Update the manifests repo to point to your Quay and GitHub repositories:

```bash
sed -i 's/rhdevelopers/yourquayuser/g' k8s/deployment.yaml
sed -i 's/redhat-developer-demos/yourgithubuser/g' argo/wind-turbine-app.yaml
git add .
git commit -m "update reference to quay and github"
git push
```

## Create Argo CD application

Create the Argo CD application to deploy the game:

```bash
oc apply -f argo/wind-turbine-app.yaml
```

Start the pipeline or edit `Config.js` to switch to V2:

```js
export const ENABLE_SHAKING = true;