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
# JBang builds the pod patch with SvidPatch.java.
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
command -v jbang >/dev/null 2>&1 || { echo "error: install JBang from https://www.jbang.dev" >&2; exit 1; }

image=$(oc get deployment zero-trust-workload-identity-manager-controller-manager \
  -n zero-trust-workload-identity-manager \
  -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="RELATED_IMAGE_SPIRE_AGENT")].value}')
[ -n "$image" ] || { echo "error: the zero trust workload identity manager is not installed" >&2; exit 1; }

patch=$(NAME=$name AUDIENCE=$audience X509=$x509 IMAGE=$image \
  jbang "$(dirname "$0")/SvidPatch.java")

oc patch deployment "$name" -n "$namespace" -p "$patch"
