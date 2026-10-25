#!/usr/bin/env bash
# Repeatable offline smoke for the Docker Compose stack (STORY-INFRA-008). Starts compose.yaml under its
# own unique project (gw2-cstack-<id>) on an ephemeral loopback frontend port, checks the three
# services, static UI, proxied global read, keyless refusal, same-origin session boundary, that only
# the frontend publishes a port, then restarts the stack (volumes kept) and verifies the managed
# schema is reused. Needs Docker with Compose v2, Bash, curl, GNU date; no GW2 key or ArenaNet access.
# Only this project's containers, network, volumes and images are removed on exit (compose down -v -p
# <project> and its two built images); other projects are never touched.
set -euo pipefail
cd "$(dirname "$0")/.."

ID="${GW2_SMOKE_ID:-$(date +%s)-$$}"
[[ "$ID" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ && ${#ID} -le 40 ]] || {
  echo 'GW2_SMOKE_ID must be at most 40 lowercase letters/digits/hyphens' >&2; exit 1;
}
PROJECT="gw2-cstack-$ID"
TIMEOUT="${GW2_SMOKE_TIMEOUT_SECONDS:-300}"
# Explicit settings: ephemeral frontend port, loopback, throwaway password, no inherited .env values.
export GW2_STACK_HTTP_PORT='' GW2_STACK_HTTP_BIND=127.0.0.1 GW2_STACK_DB_PASSWORD="smoke-$RANDOM$RANDOM"
unset DATABASE_URL DATABASE_USER DATABASE_PASSWORD GW2_API_KEY
dc() { docker compose -p "$PROJECT" -f compose.yaml -f "$WORK/smoke.yaml" "$@"; }

if [ -n "$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT")" ]; then
  echo "Project $PROJECT already exists; choose another GW2_SMOKE_ID" >&2; exit 1
fi
for kind in network volume; do
  if [ -n "$(docker "$kind" ls -q --filter "label=com.docker.compose.project=$PROJECT")" ]; then
    echo "Project $PROJECT has existing $kind resources; choose another GW2_SMOKE_ID" >&2; exit 1
  fi
done
if docker network inspect "${PROJECT}_default" >/dev/null 2>&1; then
  echo "Network ${PROJECT}_default already exists" >&2; exit 1
fi
for volume in "${PROJECT}_gw2-postgres" "${PROJECT}_gw2-icons"; do
  if docker volume inspect "$volume" >/dev/null 2>&1; then
    echo "Volume $volume already exists" >&2; exit 1
  fi
done
for image in "$PROJECT-backend" "$PROJECT-frontend"; do
  if docker image inspect "$image" >/dev/null 2>&1; then
    echo "Image $image already exists" >&2; exit 1
  fi
done

TEMP_ROOT=$(cd "${TMPDIR:-/tmp}" && pwd -P)
WORK=$(mktemp -d "$TEMP_ROOT/gw2-cstack.XXXXXXXX")
WORK=$(cd "$WORK" && pwd -P)
[[ "$WORK" == "$TEMP_ROOT"/gw2-cstack.* ]] || { echo 'Unexpected temporary path' >&2; exit 1; }

HOLDER=''
cleanup() {
  [ -z "$HOLDER" ] || docker rm -f "$HOLDER" >/dev/null 2>&1 || true
  dc down -v --remove-orphans >/dev/null 2>&1 || true
  # Compose names built images <project>-<service>; the shared postgres image is never removed.
  docker rmi "$PROJECT-backend" "$PROJECT-frontend" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

# The production stack keeps its normal scheduler. Only this disposable smoke defers upstream work.
cat > "$WORK/smoke.yaml" <<'OVERRIDE'
services:
  backend:
    environment:
      GW2_GLOBAL_REFRESH_INITIAL_DELAY_MS: "86400000"
OVERRIDE

now_ms() { date +%s%3N; }
curl() { command curl --connect-timeout 2 --max-time 5 "$@"; }
fail() { echo "FAIL: $*" >&2; dc logs --tail 30 >&2 || true; exit 1; }
expect() { echo "$1 -> $2"; [ "$2" = "$3" ] || fail "$1: expected $3, got $2"; }

start_stack() {
  local t0; t0=$(now_ms)
  dc up -d --build --wait --wait-timeout "$TIMEOUT" >/dev/null || fail "stack did not become healthy"
  echo "up_to_healthy_ms=$(( $(now_ms) - t0 ))"
  PORT=$(dc port frontend 8080 | head -1 | sed 's/.*://')
  BASE="http://127.0.0.1:$PORT"
}

check_stack() {
  services=$(dc config --services | sort | tr '\n' ' ')
  expect "services" "$services" "backend frontend postgres "
  running=$(dc ps --status running --services | sort | tr '\n' ' ')
  expect "running services" "$running" "backend frontend postgres "
  published=$(dc ps --format '{{.Service}}={{.Publishers}}' | tr '\n' ' ')
  echo "publishers: $published"
  for s in backend postgres; do
    [ -z "$(docker port "$(dc ps -q "$s")")" ] || fail "$s must publish no host port"
  done
  expect "frontend published on loopback" "$(dc port frontend 8080 | sed 's/:[0-9]*$//')" 127.0.0.1
  expect "GET /" "$(curl -s -o "$WORK/index.html" -w '%{http_code}' "$BASE/")" 200
  grep -q '<div id="app"' "$WORK/index.html" || fail "index.html is not the application shell"
  expect "GET /crafting direct URL" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/crafting")" 200
  expect "proxied global read /api/system/status" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/system/status")" 200
  expect "keyless account read /api/account/bank" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/account/bank")" 401
  expect "session proof, matching Origin, no key" "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Origin: $BASE" "$BASE/api/session")" 400
  expect "session proof, foreign Origin" "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Origin: http://evil.example' "$BASE/api/session")" 403
  expect "session proof, no Origin" "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/session")" 403
  expect "backend uid (non-root)" "$(dc exec -T backend id -u)" 10001
  expect "icon volume writable" "$(dc exec -T backend sh -c 'touch /app/data/icons/.w && rm /app/data/icons/.w && echo ok')" ok
}

echo "== first startup ($PROJECT)"
start_stack
check_stack
dc logs backend 2>&1 | grep '\[schema\]' || true
dc logs backend 2>&1 | grep -q 'versioned chain applied (found EMPTY)' || fail "first start did not initialize an empty schema"
for s in postgres backend frontend; do
  echo "image_bytes $s=$(docker inspect -f '{{.Size}}' "$(dc images -q "$s" | head -1)" 2>/dev/null || echo n/a)"
done

if [ -d frontend/node_modules/playwright-core ]; then
  echo "== browser smoke (navigation and connection UI)"
  GW2_FRONTEND_URL="$BASE" node frontend/scripts/stack-browser-smoke.mjs || fail "browser smoke failed"
else
  echo "browser smoke SKIPPED (run npm ci in frontend/ to include it)"
fi

wait_proxy() {
  local deadline code
  deadline=$(( $(now_ms) + TIMEOUT * 1000 ))
  while [ "$(now_ms)" -lt "$deadline" ]; do
    code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/system/status" || true)
    [ "$code" = 200 ] && return
    sleep 0.2
  done
  fail "proxy did not recover after backend address change"
}

echo "== backend address change and backend-only recreation without frontend restart"
old_backend=$(dc ps -q backend)
old_frontend=$(dc ps -q frontend)
old_frontend_started=$(docker inspect -f '{{.State.StartedAt}}' "$old_frontend")
# Occupy the released address with a temporary owned container. Automatic allocation works on
# engines that reject --ip on default-IPAM networks. The backend process and data stay unchanged.
old_ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$old_backend")
docker network disconnect "${PROJECT}_default" "$old_backend"
HOLDER=$(docker run -d --network "${PROJECT}_default" --entrypoint sh "$PROJECT-frontend" -c 'sleep 300')
docker network connect --alias backend "${PROJECT}_default" "$old_backend"
moved_ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$old_backend")
[ "$moved_ip" != "$old_ip" ] || fail "backend reconnect did not change its IP"
wait_proxy
docker rm -f "$HOLDER" >/dev/null
HOLDER=''
dc up -d --no-deps --force-recreate --wait --wait-timeout "$TIMEOUT" backend >/dev/null || fail "backend recreation failed"
[ "$(dc ps -q backend)" != "$old_backend" ] || fail "backend was not recreated"
new_ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$(dc ps -q backend)")
[ "$new_ip" != "$moved_ip" ] || fail "backend recreation did not change its IP"
wait_proxy
[ "$(dc ps -q frontend)" = "$old_frontend" ] || fail "frontend was unexpectedly replaced"
[ "$(docker inspect -f '{{.State.StartedAt}}' "$old_frontend")" = "$old_frontend_started" ] || fail "frontend was unexpectedly restarted"
check_stack
echo "backend address $old_ip -> $moved_ip -> $new_ip; untouched frontend recovered via DNS"

echo "== restart (containers recreated, volumes kept)"
dc down >/dev/null
start_stack
check_stack
dc logs backend 2>&1 | grep '\[schema\]' || true
dc logs backend 2>&1 | grep -q 'unmanaged schema reset' && fail "restart reset the schema"
dc logs backend 2>&1 | grep -q 'versioned chain applied (found MANAGED)' || fail "restart did not find a managed schema"
echo "PASS"
