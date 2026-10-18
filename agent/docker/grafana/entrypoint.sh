#!/bin/sh
set -eu

# Read the host's existing token through a read-only Docker secret mount.
# $GW2_WORKFLOW_TOKEN is expanded by Grafana's datasource provisioner.
GW2_WORKFLOW_TOKEN="$(tr -d '\r\n' < /run/secrets/workflow-service-token)"
test -n "$GW2_WORKFLOW_TOKEN"
export GW2_WORKFLOW_TOKEN
exec /run.sh "$@"
