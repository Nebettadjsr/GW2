#!/usr/bin/env bash
# Repeatable smoke for the backend runtime image (STORY-INFRA-006). Builds the image, starts a
# disposable PostgreSQL container on a private network, and checks readiness, a global read, the
# keyless account refusal and a second start on the managed schema. Touches only resources it names
# with a unique gw2-smoke-<id> prefix and removes them on exit. Needs Docker; no GW2 key or network
# access to ArenaNet. Container measurements are printed as observations, not limits.
set -euo pipefail
cd "$(dirname "$0")/.."

ID="${GW2_SMOKE_ID:-$(date +%s)-$$}"
[[ "$ID" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ && ${#ID} -le 48 ]] || {
  echo 'GW2_SMOKE_ID must be at most 48 lowercase letters/digits/hyphens' >&2; exit 1;
}
NS="gw2-smoke-$ID"
IMAGE="$NS:latest"
PG_IMAGE="${GW2_SMOKE_PG_IMAGE:-postgres:17}"
SCHEMA="smoke_${ID//-/_}"
TIMEOUT="${GW2_SMOKE_TIMEOUT_SECONDS:-120}"
DB_PASSWORD="smoke-$RANDOM$RANDOM"
PG_ID=''
APP_ID=''
NETWORK_ID=''
IMAGE_CREATED=false

# Do not replace an existing tag when a caller deliberately reuses a smoke ID.
if docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "Image $IMAGE already exists; choose another GW2_SMOKE_ID" >&2
  exit 1
fi

cleanup() {
  # Immutable IDs are assigned only after successful creation. A name collision never grants ownership.
  [ -z "$APP_ID" ] || docker rm -fv "$APP_ID" >/dev/null 2>&1 || true
  [ -z "$PG_ID" ] || docker rm -fv "$PG_ID" >/dev/null 2>&1 || true
  [ -z "$NETWORK_ID" ] || docker network rm "$NETWORK_ID" >/dev/null 2>&1 || true
  [ "$IMAGE_CREATED" = false ] || docker rmi "$IMAGE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

now_ms() { date +%s%3N; }
fail() { echo "FAIL: $*" >&2; docker logs "$NS-app" 2>&1 | tail -30 >&2 || true; exit 1; }

echo "== build $IMAGE"
t0=$(now_ms)
docker build -q -f docker/backend/Dockerfile -t "$IMAGE" backend >/dev/null
IMAGE_CREATED=true
echo "build_ms=$(( $(now_ms) - t0 ))"
echo "image_bytes=$(docker image inspect "$IMAGE" --format '{{.Size}}')"
echo "runtime_user=$(docker image inspect "$IMAGE" --format '{{.Config.User}}')"

echo "== start disposable PostgreSQL ($PG_IMAGE)"
NETWORK_ID=$(docker network create "$NS")
PG_ID=$(docker run -d --name "$NS-pg" --network "$NS" -e POSTGRES_PASSWORD="$DB_PASSWORD" \
  -e POSTGRES_USER=gw2 -e POSTGRES_DB=gw2 "$PG_IMAGE")
for _ in $(seq "$TIMEOUT"); do
  docker exec "$NS-pg" pg_isready -U gw2 -d gw2 -h 127.0.0.1 >/dev/null 2>&1 && break
  sleep 1
done
sleep 2
docker exec "$NS-pg" pg_isready -U gw2 -d gw2 -h 127.0.0.1 >/dev/null || fail "PostgreSQL not ready"

# The startup gate adopts the connection's current schema and does not create it, so the isolated
# schema is created up front (in the disposable database, never in public).
docker exec "$NS-pg" psql -q -U gw2 -d gw2 -h 127.0.0.1 -c "CREATE SCHEMA $SCHEMA" >/dev/null

start_app() {
  if [ -n "$APP_ID" ]; then
    docker rm -fv "$APP_ID" >/dev/null
    APP_ID=''
  fi
  # The scheduler's first upstream refresh is pushed a day out so the smoke makes no ArenaNet call.
  start=$(now_ms)
  APP_ID=$(docker run -d --name "$NS-app" --network "$NS" -p 127.0.0.1::8080 \
    -e DATABASE_URL="jdbc:postgresql://$NS-pg:5432/gw2?currentSchema=$SCHEMA" \
    -e DATABASE_USER=gw2 -e DATABASE_PASSWORD="$DB_PASSWORD" \
    -e GW2_GLOBAL_REFRESH_INITIAL_DELAY_MS=86400000 "$IMAGE")
  PORT=$(docker port "$NS-app" 8080/tcp | head -1 | sed 's/.*://')
  for _ in $(seq $((TIMEOUT * 5))); do
    [ "$(docker inspect -f '{{.State.Running}}' "$NS-app")" = true ] || fail "container exited during startup"
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/api/system/status" || true)
    [ "$code" = 200 ] && break
    sleep 0.2
  done
  [ "$code" = 200 ] || fail "not ready within ${TIMEOUT}s"
  echo "startup_to_readiness_ms=$(( $(now_ms) - start ))"
}

check_requests() {
  echo "global_read GET /api/system/status -> $(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/api/system/status")"
  acct=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/api/account/bank")
  echo "account_without_key GET /api/account/bank -> $acct"
  [ "$acct" = 401 ] || fail "expected 401 for an account read without a validated key"
}

echo "== first start (new schema $SCHEMA)"
start_app
check_requests
docker logs "$NS-app" 2>&1 | grep '^\[schema\]' || true
docker logs "$NS-app" 2>&1 | grep -q 'versioned chain applied (found EMPTY)' || fail "first start did not initialize an empty schema"
echo "uid=$(docker exec "$NS-app" id -u)"

echo "== second start (managed schema reused)"
docker stop "$NS-app" >/dev/null
start_app
check_requests
docker logs "$NS-app" 2>&1 | grep '^\[schema\]' || true
docker logs "$NS-app" 2>&1 | grep -q 'unmanaged schema reset' && fail "second start reset the schema"
docker logs "$NS-app" 2>&1 | grep -q 'versioned chain applied (found MANAGED)' || fail "second start did not find a managed schema"
echo "PASS"
