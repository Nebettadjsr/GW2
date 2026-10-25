# ADR-011 — Frontend container runtime and same-origin API forwarding

## Status

PROPOSED (architecture proposal for issue #75; independent review precedes acceptance). This is a design, not delivered behavior: no frontend image, Compose file or proxy configuration exists yet, and `CURRENT_ARCHITECTURE.md` / `CURRENT_STATE_SPEC.md` correctly do not describe one.

## Context

`TARGET_ARCHITECTURE.md` sections 3, 8.2 and 12 select three containers (static Vue frontend, Java backend, PostgreSQL), a browser that calls the application origin, and a backend that alone calls ArenaNet. Section 16 left "reverse proxy, if required" open. Some component must serve the built Vite output and forward `/api` to the backend on the internal network. Scope: only that internal frontend-container runtime and its `/api` forwarding. Hosting provider, public TLS and any edge/CDN stay undecided.

## Backend facts the runtime must respect (observed in `web/access/AccountAccessHttp.java`)

- **Origin check.** `isSameOrigin` requires an `Origin` header and accepts it when it equals `scheme://serverName[:port]` computed from the request (`getScheme`, `getServerName`, `getServerPort`; default port omitted), or matches an entry of `GW2_ACCOUNT_ACCESS_ALLOWED_ORIGINS` (`gw2.account-access.allowed-origins`). A missing `Origin` fails closed. Keyed calculations and session proof/replace/logout all apply it.
- **Host is the input.** `application.properties` sets no `server.forward-headers-strategy`, so the server name and port come from the `Host` header the backend receives, and the scheme from the connection. The proxy must therefore deliver the browser's original `Host`, including a nonstandard port such as `:8081`.
- **Cookie.** `GW2_SESSION` is host-only (no `Domain`), `Path=/`, `HttpOnly`, `SameSite=Strict`; `Secure` is added unless the connection is plain HTTP to a loopback host name. The proxy must pass `Set-Cookie` and `Cookie` unchanged and must not rewrite domain/path.
- **Headers.** `X-GW2-API-Key` (credential) and `X-Account-Context` (non-secret context assertion) are read by name; `Origin` is read as sent.

## Decision

Use **Nginx** (stable, ordinary maintained image) inside the single frontend container to serve the built `dist/` as static files and forward `/api/` to `http://backend:8080` over the Compose network. The image is a multi-stage build: Node stage runs the existing `npm ci && npm run build`; the runtime stage contains only Nginx plus `dist/` and one config file. The container publishes one port; backend and PostgreSQL publish none.

Required configuration semantics (design):

1. `location /api/ { proxy_pass http://<backend>:8080; }` with **no URI part**, so the request path and query are forwarded unchanged (nginx docs, `proxy_pass`).
2. `proxy_set_header Host $http_host;` Nginx's default is `Host $proxy_host` (the upstream's own name:port), which would make the backend compute an origin of `backend:8080` and reject every browser `Origin`. `$host` is also wrong: it carries the host name without the port (nginx core docs), breaking a nonstandard published port. `$http_host` passes the client's `Host` unchanged. Browsers always send `Host`, so the documented "absent Host" caveat does not apply to them.
3. `Origin`, `Cookie`, `X-GW2-API-Key`, `X-Account-Context` and request bodies are passed by default; `Set-Cookie` returns unchanged. No `proxy_cookie_*`, no header stripping, no CORS headers (same origin).
4. Do not add `X-Forwarded-*` handling to the backend now. Forwarding headers are untrusted client input unless the backend is configured to trust them, and the origin guard deliberately does not use them.
5. Static assets: `root` at `dist/`; `try_files $uri /index.html` for browser navigation (Vue router paths such as `/crafting/profit`); a request for a missing file under `/assets/` returns 404 rather than HTML. Hashed assets under `/assets/` may be cached long-term; `index.html` should be `no-cache`. `/api/` never falls back to `index.html`.
6. Do not log request headers or the `X-GW2-API-Key` value; use no header-dumping log format.

## Alternatives

- **Caddy.** Credible and simpler config, and the realistic runner-up; its reverse proxy is documented to keep the client `Host` by default. Not selected because this run could not read its official documentation or run it (not installed), so that claim is unverified here, and Nginx's behavior is the one verified below. Revisit if Nginx image constraints prove costly. Automatic HTTPS is a hosting/edge concern and is not a reason to choose it now.
- **Vite dev/preview server.** Has a proxy but is documented as a development tool; excluded by the issue.
- **Backend serving the static files.** Collapses the selected frontend container into the backend and mixes releases; contradicts the three-container topology.
- **Custom Node/Java forwarder, Traefik/HAProxy/Envoy, extra proxy container, Kubernetes/service mesh.** More moving parts with no requirement.

## Verification performed in this run

Disposable check with Nginx 1.24.0 (Ubuntu package) on loopback, in a temp directory, fronting a Python echo backend; no repository code, no GW2 key. Observed:

- With `proxy_set_header Host $http_host;`, a POST to `http://localhost:18081/api/access/session?a=1` reached the upstream with `Host: localhost:18081` (nonstandard port preserved), the exact path/query, and unchanged `Origin`, `Cookie`, `X-GW2-API-Key`, `X-Account-Context`. The upstream's `Set-Cookie` (HttpOnly/SameSite=Strict/Secure) came back byte-identical.
- With no `proxy_set_header`, the upstream saw `Host: 127.0.0.1:18080` while `Origin` stayed `http://localhost:18082`: the origin the backend would compute does not match, so the default is unusable.
- A request without `Origin` arrives without one (nothing is invented).
- `/crafting/profit` returned the `index.html` body (200), `/assets/a.js` 200, `/assets/nope.js` 404.

Not tested: the real backend through the proxy, Docker image behavior, HTTP/2, TLS. Official sources read: <https://nginx.org/en/docs/http/ngx_http_proxy_module.html> (`proxy_set_header` default, `$http_host` pass-through, `proxy_pass` URI rules) and <https://nginx.org/en/docs/http/ngx_http_core_module.html> (`$host`, `try_files`). Sources for Caddy and image-specific facts could not be fetched.

## Container implications (proposal)

- Running non-root and without writing to the image is feasible with Nginx: the process needs a listen port above 1024 (e.g. 8080 inside, mapped to the published port), and writable locations for the pid file and the client-body/proxy/fastcgi/uwsgi/scgi temp paths (point them at `/tmp` or `tmpfs`; the check above ran fully as an unprivileged user with exactly these overrides). The static files and config can be read-only. The implementer may use the project-maintained unprivileged Nginx image or the official image with an adjusted `nginx.conf`; choosing and verifying the exact image tag, user and read-only root filesystem is implementation work.
- Backend host name on the Compose network, listen port and published port are configuration, not decisions here.
- If a public TLS edge later terminates HTTPS in front of this container, the backend would see `http` while the browser `Origin` is `https://…`, so same-origin would fail. That is the hosting/TLS decision's problem; the already existing `GW2_ACCOUNT_ACCESS_ALLOWED_ORIGINS` setting is the available mechanism, and the choice stays open. This ADR does not decide it.

## Minimal smoke for the implementation story

Build the image and start frontend + backend + PostgreSQL via Compose with a nonstandard published port; then (1) `GET /` and a deep client route return the app and a missing `/assets/x` returns 404; (2) a `POST /api/...` session/proof route sent with browser-style `Origin: http://localhost:<port>` is not rejected with `ORIGIN_REJECTED` (a fixture proof adapter avoids a live key), and one with a foreign `Origin` is; (3) the issued `GW2_SESSION` cookie reaches the browser with the expected attributes and is replayed on a following `/api` call; (4) the container runs as non-root and only the frontend port is published.

## Consequences and remaining unknowns

- The implementation story owns Dockerfile, Nginx config, Compose, smoke and measurements; this proposal changes none of them.
- Unknown, genuinely open: exact Nginx image and read-only-rootfs feasibility; proxy read-timeout for long backend calls (measure, UD-007); whether the later TLS edge needs allowed-origins or forwarded-header support (hosting decision).
