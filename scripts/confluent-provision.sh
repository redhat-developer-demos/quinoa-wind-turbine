#!/usr/bin/env bash
#
# Provision the Confluent Cloud topics and ACLs the game needs, for a repeatable Confluent deployment.
# Run it once per Cloud cluster, before the app rolls out, because Confluent Cloud doesn't create
# topics on first use.
#
# Prerequisites:
#   - the Confluent CLI, logged in (`confluent login`)
#   - an environment and Kafka cluster selected (`confluent environment use <id>`,
#     `confluent kafka cluster use <id>`)
#   - a service account for the app and its numeric id (`confluent iam service-account list`)
#
# Usage:
#   scripts/confluent-provision.sh <service-account-id>
#
# The app authenticates with an API key that belongs to that service account, so the ACLs below grant
# the service account access to exactly the topics and consumer groups the app uses. Create the key
# with `confluent api-key create --service-account <id> --resource <cluster-id>` and put the key,
# secret and bootstrap endpoint in the confluent-credentials secret (see docs/openshift-install.md).
set -euo pipefail

SA="${1:-${CONFLUENT_SERVICE_ACCOUNT:-}}"
if [ -z "$SA" ]; then
  echo "usage: $0 <service-account-id>" >&2
  exit 1
fi

echo "Creating topics..."
# create ignores an existing topic, so the script is safe to re-run.
confluent kafka topic create power          --partitions 1 || true
confluent kafka topic create game-events    --partitions 1 || true
confluent kafka topic create race-progress  --partitions 1 --config cleanup.policy=compact || true
confluent kafka topic create race-commentary --partitions 1 --config retention.ms=3600000 || true

echo "Granting topic ACLs to service account $SA..."
# The app produces to and consumes from the four game topics.
for t in power game-events race-progress race-commentary; do
  confluent kafka acl create --allow --service-account "$SA" --operations READ,WRITE,DESCRIBE --topic "$t"
done
# Kafka Streams creates and uses its own changelog and repartition topics, prefixed with the
# application id (quinoa-wind-turbine).
confluent kafka acl create --allow --service-account "$SA" \
  --operations CREATE,READ,WRITE,DELETE,DESCRIBE,DESCRIBE_CONFIGS,ALTER_CONFIGS \
  --topic quinoa-wind-turbine- --prefix

echo "Granting consumer-group ACLs to service account $SA..."
# wind-turbine-        : the shared commentary generator and the per-replica broadcast groups
# commentary-          : the per-replica commentary delivery groups
# quinoa-wind-turbine  : the Kafka Streams application group and its internal groups
for g in wind-turbine- commentary- quinoa-wind-turbine; do
  confluent kafka acl create --allow --service-account "$SA" --operations READ,DESCRIBE \
    --consumer-group "$g" --prefix
done

echo "Done. Verify with: confluent kafka acl list --service-account $SA"
