#!/usr/bin/env bash
# Gives a deployment SVID files: a JWT-SVID for a Quarkus OIDC client that authenticates to Keycloak
# with `credentials.jwt.source=spiffe-jwt`, and/or the X.509 SVID and trust bundle for mTLS.
#
#   scripts/mount-spiffe-svid.sh DEPLOYMENT NAMESPACE [--jwt AUDIENCE] [--x509]
#
# The SPIFFE CSI driver mounts the Workload API socket, not files, and the apps read their
# credentials from files. So the pod gets:
#   - an init container that fetches the first SVIDs (retrying while SPIRE registers the new
#     pod), so the files exist before the app starts;
#   - a sidecar that fetches fresh ones every minute and swaps the files in (a JWT-SVID lives 5
#     minutes and SPIRE hands out the cached one until it is half-way through its life; an X.509
#     SVID lives an hour and is renewed at half-life);
#   - a memory-backed volume shared with the app at /var/run/secrets/svid.
# Files: jwt_svid.token (--jwt); svid.pem, svid.key and bundle.pem (--x509).
# Both containers use the SPIRE agent image, because the zero trust workload identity manager ships
# no spiffe-helper. They only call `spire-agent api fetch`; the image has sh and awk.
# A strategic merge patch is idempotent, but a later Maven deploy replaces the Deployment, so run
# this after every deploy. It does not set the app's environment; the caller does.
set -euo pipefail

name=${1:?deployment}
namespace=${2:?namespace}
shift 2
audience=""
x509=0
while [ $# -gt 0 ]; do
  case $1 in
    --jwt) audience=${2:?--jwt needs an audience}; shift 2 ;;
    --x509) x509=1; shift ;;
    *) echo "error: unknown option $1" >&2; exit 1 ;;
  esac
done
[ -n "$audience" ] || [ "$x509" = 1 ] || { echo "error: give --jwt AUDIENCE and/or --x509" >&2; exit 1; }

image=$(oc get deployment zero-trust-workload-identity-manager-controller-manager \
  -n zero-trust-workload-identity-manager \
  -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="RELATED_IMAGE_SPIRE_AGENT")].value}')
[ -n "$image" ] || { echo "error: the zero trust workload identity manager is not installed" >&2; exit 1; }

patch=$(NAME=$name AUDIENCE=$audience X509=$x509 IMAGE=$image python3 -c '
import json, os

socket = "-socketPath /spiffe-workload-api/spire-agent.sock"
fetch_jwt = (
    "fetch_jwt() { t=$(/spire-agent api fetch jwt -audience \"$AUDIENCE\" " + socket + " "
    "| awk \"/^token\\\\(/{getline; gsub(/^[ \\\\t]+/,\\\"\\\"); print; exit}\"); "
    "[ -n \"$t\" ] && printf %s \"$t\" > /svid/.jwt_svid.tmp && mv /svid/.jwt_svid.tmp /svid/jwt_svid.token; }; "
)
# The agent writes svid.0.pem, svid.0.key and bundle.0.pem into a directory; move them in under
# the names the apps read. A reader that lands between two moves sees a mismatched pair and
# rereads on its next check.
fetch_x509 = (
    "fetch_x509() { mkdir -p /svid/.x509 && /spire-agent api fetch x509 " + socket + " -write /svid/.x509 >/dev/null "
    "&& mv /svid/.x509/bundle.0.pem /svid/bundle.pem && mv /svid/.x509/svid.0.key /svid/svid.key "
    "&& mv /svid/.x509/svid.0.pem /svid/svid.pem; }; "
)
steps = []
fetch = ""
if os.environ["AUDIENCE"]:
    fetch += fetch_jwt
    steps.append("fetch_jwt")
if os.environ["X509"] == "1":
    fetch += fetch_x509
    steps.append("fetch_x509")
fetch += "fetch() { " + " && ".join(steps) + "; }; "

init_script = fetch + "for i in $(seq 1 60); do fetch && exit 0; sleep 2; done; echo no SVID >&2; exit 1"
refresh_script = fetch + "while true; do fetch && sleep 60 || sleep 5; done"

def container(name, script):
    return {
        "name": name,
        "image": os.environ["IMAGE"],
        "command": ["sh", "-c", script],
        "env": [{"name": "AUDIENCE", "value": os.environ["AUDIENCE"]}],
        "resources": {"requests": {"cpu": "10m", "memory": "16Mi"}, "limits": {"memory": "64Mi"}},
        "securityContext": {"allowPrivilegeEscalation": False, "capabilities": {"drop": ["ALL"]}},
        "volumeMounts": [
            {"name": "spiffe-workload-api", "mountPath": "/spiffe-workload-api", "readOnly": True},
            {"name": "svid", "mountPath": "/svid"},
        ],
    }

print(json.dumps({"spec": {"template": {"spec": {
    "volumes": [
        {"name": "spiffe-workload-api", "csi": {"driver": "csi.spiffe.io", "readOnly": True}},
        {"name": "svid", "emptyDir": {"medium": "Memory"}},
    ],
    "initContainers": [container("svid-init", init_script)],
    "containers": [
        {"name": os.environ["NAME"],
         "volumeMounts": [{"name": "svid", "mountPath": "/var/run/secrets/svid", "readOnly": True}]},
        container("svid-refresh", refresh_script),
    ],
}}}}))
')

oc patch deployment "$name" -n "$namespace" -p "$patch"
