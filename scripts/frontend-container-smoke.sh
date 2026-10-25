#!/usr/bin/env bash
# Repeatable smoke for the frontend runtime image (STORY-INFRA-007, ADR-011). Builds the backend and
# frontend images, starts disposable PostgreSQL, backend and frontend containers on a private network
# (only the frontend publishes a port, on a random nonstandard loopback port), and checks static
# assets, proxied reads, keyless refusal and the same-origin session boundary. A second frontend
# fronting a controlled Node echo upstream verifies header/cookie/body/path forwarding without
# ArenaNet. Needs Docker, Bash, curl, GNU date; no GW2 key. Only resources created here (unique
# gw2-fsmoke-<id> names, tracked by immutable ID) are removed, anonymous volumes included.
set -euo pipefail
cd "$(dirname "$0")/.."

ID="${GW2_SMOKE_ID:-$(date +%s)-$$}"
[[ "$ID" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ && ${#ID} -le 48 ]] || {
  echo 'GW2_SMOKE_ID must be at most 48 lowercase letters/digits/hyphens' >&2; exit 1;
}
NS="gw2-fsmoke-$ID"
BACK_IMAGE="$NS-backend:latest"
FRONT_IMAGE="$NS-frontend:latest"
PG_IMAGE="${GW2_SMOKE_PG_IMAGE:-postgres:17}"
ECHO_IMAGE="${GW2_SMOKE_ECHO_IMAGE:-node:22-alpine}"
SCHEMA="smoke_${ID//-/_}"
TIMEOUT="${GW2_SMOKE_TIMEOUT_SECONDS:-120}"
DB_PASSWORD="smoke-$RANDOM$RANDOM"
PG_ID='' BACK_ID='' FRONT_ID='' ECHO_ID='' FRONT2_ID='' NETWORK_ID=''
BACK_IMG_CREATED=false FRONT_IMG_CREATED=false

for img in "$BACK_IMAGE" "$FRONT_IMAGE"; do
  if docker image inspect "$img" >/dev/null 2>&1; then
    echo "Image $img already exists; choose another GW2_SMOKE_ID" >&2; exit 1
  fi
done

TEMP_ROOT=$(cd "${TMPDIR:-/tmp}" && pwd -P)
WORK=$(mktemp -d "$TEMP_ROOT/gw2-fsmoke.XXXXXXXX")
WORK=$(cd "$WORK" && pwd -P)
[[ "$WORK" == "$TEMP_ROOT"/gw2-fsmoke.* ]] || { echo 'Unexpected temporary path' >&2; exit 1; }

cleanup() {
  for c in "$FRONT2_ID" "$ECHO_ID" "$FRONT_ID" "$BACK_ID" "$PG_ID"; do
    [ -z "$c" ] || docker rm -fv "$c" >/dev/null 2>&1 || true
  done
  [ -z "$NETWORK_ID" ] || docker network rm "$NETWORK_ID" >/dev/null 2>&1 || true
  [ "$BACK_IMG_CREATED" = false ] || docker rmi "$BACK_IMAGE" >/dev/null 2>&1 || true
  [ "$FRONT_IMG_CREATED" = false ] || docker rmi "$FRONT_IMAGE" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

now_ms() { date +%s%3N; }
curl() { command curl --connect-timeout 2 --max-time 5 "$@"; }
fail() {
  echo "FAIL: $*" >&2
  for n in app front; do docker logs "$NS-$n" 2>&1 | tail -20 >&2 || true; done
  exit 1
}
expect() { # expect <description> <actual> <expected>
  echo "$1 -> $2"
  [ "$2" = "$3" ] || fail "$1: expected $3, got $2"
}

echo "== build images"
t0=$(now_ms); docker build -q -t "$BACK_IMAGE" . >/dev/null; BACK_IMG_CREATED=true
echo "backend_build_ms=$(( $(now_ms) - t0 ))"
t0=$(now_ms); docker build -q -t "$FRONT_IMAGE" frontend >/dev/null; FRONT_IMG_CREATED=true
echo "frontend_build_ms=$(( $(now_ms) - t0 ))"
echo "frontend_image_bytes=$(docker image inspect "$FRONT_IMAGE" --format '{{.Size}}')"
echo "frontend_runtime_user=$(docker run --rm --entrypoint id "$FRONT_IMAGE" -un)"

echo "== start disposable PostgreSQL ($PG_IMAGE) and backend"
NETWORK_ID=$(docker network create "$NS")
PG_ID=$(docker run -d --name "$NS-pg" --network "$NS" -e POSTGRES_PASSWORD="$DB_PASSWORD" \
  -e POSTGRES_USER=gw2 -e POSTGRES_DB=gw2 "$PG_IMAGE")
for _ in $(seq "$TIMEOUT"); do
  docker exec "$NS-pg" pg_isready -U gw2 -d gw2 -h 127.0.0.1 >/dev/null 2>&1 && break
  sleep 1
done
sleep 2
docker exec "$NS-pg" pg_isready -U gw2 -d gw2 -h 127.0.0.1 >/dev/null || fail "PostgreSQL not ready"
docker exec "$NS-pg" psql -q -U gw2 -d gw2 -h 127.0.0.1 -c "CREATE SCHEMA $SCHEMA" >/dev/null
# No published port: the backend is reachable only through the frontend container.
BACK_ID=$(docker run -d --name "$NS-app" --network "$NS" \
  -e DATABASE_URL="jdbc:postgresql://$NS-pg:5432/gw2?currentSchema=$SCHEMA" \
  -e DATABASE_USER=gw2 -e DATABASE_PASSWORD="$DB_PASSWORD" \
  -e GW2_GLOBAL_REFRESH_INITIAL_DELAY_MS=86400000 "$BACK_IMAGE")

echo "== start frontend (only published port)"
# The Nginx entrypoint resolves the upstream name at start, so wait for the backend to be reachable.
for _ in $(seq "$TIMEOUT"); do
  [ "$(docker inspect -f '{{.State.Running}}' "$NS-app")" = true ] || fail "backend exited"
  getent_ok=$(docker run --rm --network "$NS" --entrypoint sh "$FRONT_IMAGE" -c "getent hosts $NS-app" 2>/dev/null || true)
  [ -n "${getent_ok:-}" ] && break
  sleep 1
done
start=$(now_ms)
FRONT_ID=$(docker run -d --name "$NS-front" --network "$NS" -p 127.0.0.1::8080 \
  -e GW2_BACKEND_ORIGIN="http://$NS-app:8080" "$FRONT_IMAGE")
PORT=$(docker port "$NS-front" 8080/tcp | head -1 | sed 's/.*://')
BASE="http://127.0.0.1:$PORT"
code=''
deadline=$(( $(now_ms) + TIMEOUT * 1000 ))
while [ "$(now_ms)" -lt "$deadline" ]; do
  [ "$(docker inspect -f '{{.State.Running}}' "$NS-front")" = true ] || fail "frontend exited"
  code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/system/status" || true)
  [ "$code" = 200 ] && break
  sleep 0.2
done
[ "$code" = 200 ] || fail "not ready through the proxy within ${TIMEOUT}s"
echo "startup_to_readiness_ms=$(( $(now_ms) - start )) (frontend start until proxied /api/system/status is 200, backend already started)"
echo "published_ports: frontend=$(docker port "$NS-front" | tr '\n' ' ') backend='$(docker port "$NS-app" | tr '\n' ' ')' postgres='$(docker port "$NS-pg" | tr '\n' ' ')'"
[ -z "$(docker port "$NS-app")" ] && [ -z "$(docker port "$NS-pg")" ] || fail "backend/db must publish no ports"
echo "uid=$(docker exec "$NS-front" id -u)"
[ "$(docker exec "$NS-front" id -u)" != 0 ] || fail "frontend runs as root"

echo "== static assets and navigation"
expect "GET / " "$(curl -s -o "$WORK/index.html" -w '%{http_code}' "$BASE/")" 200
grep -q '<div id="app"' "$WORK/index.html" || fail "index.html is not the application shell"
asset=$(grep -o '/assets/[^"]*\.js' "$WORK/index.html" | head -1)
[ -n "$asset" ] || fail "no built script referenced"
expect "GET $asset" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE$asset")" 200
expect "GET /assets/nope.js" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/assets/nope.js")" 404
expect "GET /#/crafting server path" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/")" 200
expect "GET /crafting direct URL" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/crafting")" 200
expect "GET /api/unknown stays JSON/404, not shell" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/does-not-exist")" 404

echo "== proxied API against the real backend"
expect "global read /api/system/status" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/system/status")" 200
expect "keyless account read /api/account/bank" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/account/bank")" 401
# Matching Origin passes the same-origin guard and then needs a key (400); no real key is used.
expect "session proof, matching Origin, no key" "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Origin: $BASE" "$BASE/api/session")" 400
foreign=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Origin: http://evil.example' "$BASE/api/session")
noorigin=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/session")
echo "session proof, foreign Origin -> $foreign; no Origin -> $noorigin"
[ "$foreign" = 403 ] && [ "$noorigin" = 403 ] || fail "foreign or missing Origin must be refused with 403"

echo "== forwarding semantics against a controlled echo upstream (no ArenaNet)"
cat > "$WORK/echo.mjs" <<'FIXTURE'
import { createServer } from 'node:http'
createServer(async (request, response) => {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  const body = Buffer.concat(chunks).toString('utf8')
  const h = request.headers
  response.writeHead(218, {
    'Set-Cookie': 'GW2_SESSION=echo; Path=/; HttpOnly; SameSite=Strict',
    'Content-Type': 'text/plain'
  })
  response.end(`method=${request.method} uri=${request.url} host=${h.host} origin=${h.origin} cookie=${h.cookie} key=${h['x-gw2-api-key']} ctx=${h['x-account-context']} body=${body}\n`)
}).listen(8080, '0.0.0.0')
FIXTURE
ECHO_ID=$(MSYS_NO_PATHCONV=1 docker create --name "$NS-echo" --network "$NS" --entrypoint node "$ECHO_IMAGE" /tmp/echo.mjs)
# Git Bash converts the host source path, but the container destination must stay a Linux path.
MSYS2_ARG_CONV_EXCL="$NS-echo:" docker cp "$WORK/echo.mjs" "$NS-echo:/tmp/echo.mjs"
docker start "$NS-echo" >/dev/null
FRONT2_ID=$(docker run -d --name "$NS-front2" --network "$NS" -p 127.0.0.1::8080 \
  -e GW2_BACKEND_ORIGIN="http://$NS-echo:8080" "$FRONT_IMAGE")
PORT2=$(docker port "$NS-front2" 8080/tcp | head -1 | sed 's/.*://')
deadline=$(( $(now_ms) + TIMEOUT * 1000 ))
echo_status=''
while [ "$(now_ms)" -lt "$deadline" ]; do
  [ "$(docker inspect -f '{{.State.Running}}' "$NS-echo")" = true ] || fail "echo fixture exited"
  [ "$(docker inspect -f '{{.State.Running}}' "$NS-front2")" = true ] || fail "fixture frontend exited"
  echo_status=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT2/api/ping" || true)
  [ "$echo_status" = 218 ] && break
  sleep 0.2
done
[ "$echo_status" = 218 ] || fail "echo fixture did not become ready"
out=$(curl -s -D "$WORK/h2" -X POST -H "Origin: http://127.0.0.1:$PORT2" -H 'Cookie: GW2_SESSION=abc' \
  -H 'X-GW2-API-Key: smoke-not-a-real-key' -H 'X-Account-Context: ctx-1' --data 'body=1' \
  "http://127.0.0.1:$PORT2/api/session/renew?a=1&b=two")
echo "echo: $out"
want="method=POST uri=/api/session/renew?a=1&b=two host=127.0.0.1:$PORT2 origin=http://127.0.0.1:$PORT2 cookie=GW2_SESSION=abc key=smoke-not-a-real-key ctx=ctx-1 body=body=1"
[ "$out" = "$want" ] || fail "forwarded request differs; wanted: $want"
grep -qi '^HTTP/1.1 218' "$WORK/h2" || fail "upstream status not preserved"
grep -qi '^set-cookie: GW2_SESSION=echo; Path=/; HttpOnly; SameSite=Strict' "$WORK/h2" || fail "Set-Cookie not preserved"
echo "forwarding: status 218, path/query/body, Host with port, Origin, Cookie, key, context, Set-Cookie preserved"
docker logs "$NS-front2" 2>&1 | grep -q 'smoke-not-a-real-key' && fail "key value appeared in proxy logs"
echo "PASS"
