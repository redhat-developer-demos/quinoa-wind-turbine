#!/usr/bin/env bash
#
# Open anonymous OAuth client registration in the local dev Keycloak realm, so an MCP client that
# registers itself during login (Bob does) can authenticate.
#
# Keycloak's default realm template carries a "Trusted Hosts" client-registration policy, and the
# realm file doesn't declare one. When a dev realm keeps that policy, Keycloak rejects registration
# with "Policy 'Trusted Hosts' rejected request to client-registration service. Details: Host not
# trusted." The policy can't be neutralized from the realm file: Keycloak refuses a Trusted Hosts
# policy with both checks off. This script deletes the policy from the running realm instead, which
# leaves the rest of the realm alone and doesn't restart Keycloak or the app.
#
# Usage:
#   scripts/open-dev-registration.sh [APP_URL]
#
# APP_URL is the running app (default http://localhost:8080). The realm and its Keycloak URL are read
# from the app's OAuth protected resource metadata, so a Dev Services container on a random port works.
#
# Settings, all optional:
#   KEYCLOAK_ADMIN           master realm admin user (default admin)
#   KEYCLOAK_ADMIN_PASSWORD  that user's password (default admin)
#
# The dashboard login and the read-only MCP server don't need this. Dev Services defaults are
# bootstrap admin credentials, so this works on the local container and not on a shared deployment.
set -euo pipefail

APP_URL="${1:-http://localhost:8080}"
KEYCLOAK_ADMIN="${KEYCLOAK_ADMIN:-admin}"
KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"

log() { echo "==> $*"; }
die() { echo "error: $*" >&2; exit 1; }

json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)" 2>/dev/null; }

metadata=$(curl -fsS "$APP_URL/.well-known/oauth-protected-resource/admin/mcp" 2>/dev/null) \
    || die "no OAuth metadata at $APP_URL. Start dev mode with GAME_OIDC_ENABLED=true."

realm_url=$(printf '%s' "$metadata" | json "d['authorization_servers'][0]") \
    || die "the app doesn't advertise an authorization server. Is OIDC enabled?"

base_url="${realm_url%/realms/*}"
realm="${realm_url##*/}"
[ -n "$base_url" ] && [ -n "$realm" ] || die "cannot read the realm from $realm_url"
log "realm $realm at $base_url"

token=$(curl -fsS -d "client_id=admin-cli" -d "username=$KEYCLOAK_ADMIN" \
    -d "password=$KEYCLOAK_ADMIN_PASSWORD" -d "grant_type=password" \
    "$base_url/realms/master/protocol/openid-connect/token" 2>/dev/null \
    | json "d['access_token']") \
    || die "cannot get an admin token from $base_url. Set KEYCLOAK_ADMIN and KEYCLOAK_ADMIN_PASSWORD."
[ -n "$token" ] || die "no admin token for $KEYCLOAK_ADMIN on $base_url"

components=$(curl -fsS -H "Authorization: Bearer $token" \
    "$base_url/admin/realms/$realm/components?type=org.keycloak.services.clientregistration.policy.ClientRegistrationPolicy") \
    || die "cannot read the client-registration policies of realm $realm"

ids=$(printf '%s' "$components" | python3 -c "
import json, sys
for c in json.load(sys.stdin):
    if c.get('providerId') == 'trusted-hosts' and c.get('subType') == 'anonymous':
        print(c['id'])
")

if [ -z "$ids" ]; then
    log "no Trusted Hosts policy: registration is already open"
    exit 0
fi

for id in $ids; do
    curl -fsS -X DELETE -H "Authorization: Bearer $token" \
        "$base_url/admin/realms/$realm/components/$id" >/dev/null \
        || die "cannot delete component $id"
    log "deleted Trusted Hosts policy $id"
done

check=$(curl -fsS -X POST -H "Content-Type: application/json" \
    -d '{"client_name":"registration-check","redirect_uris":["http://localhost:33418/callback"],"token_endpoint_auth_method":"none","grant_types":["authorization_code"]}' \
    "$realm_url/clients-registrations/openid-connect") \
    || die "registration still fails; check the Keycloak log"

# Remove the client the check registered, so a re-run doesn't fill the realm with test clients.
check_id=$(printf '%s' "$check" | json "d['client_id']")
if [ -n "$check_id" ]; then
    curl -fsS -X DELETE -H "Authorization: Bearer $token" \
        "$base_url/admin/realms/$realm/clients/$check_id" >/dev/null || true
fi
log "anonymous registration works"
