# Run the complete application

Install Docker with Compose v2 (Docker Desktop on Windows), then run from the repository root:

```sh
cd docker
docker compose up --build --wait
```

Open **http://127.0.0.1:8088**. In **System Status**, enter your read-only Guild Wars 2 API key to connect your account. The first startup downloads game data; account synchronization and calculations need that data to become available.

All commands below run from `docker/`.

## Three separate containers

```text
Browser -> frontend (Nginx :8080, host :8088)
               -> backend (Java :8080)
                      -> postgres (PostgreSQL :5432)
```

| Service | Image / build definition | Purpose |
| --- | --- | --- |
| `frontend` | [`../frontend/Dockerfile`](../frontend/Dockerfile), Nginx configuration in [`../frontend/default.conf.template`](../frontend/default.conf.template) | Serves the built browser UI and forwards `/api` to the backend. |
| `backend` | [`../backend/Dockerfile`](../backend/Dockerfile) | Runs the Java API, calculations, ArenaNet synchronization and Flyway schema migrations. |
| `postgres` | Official `postgres:17` image, configured in `compose.yaml` | Runs the database in its own container. No custom database Dockerfile is needed. |

Each application's Dockerfile and Docker ignore rules live beside its source in the top-level `backend/` or `frontend/` directory. The frontend also owns its Nginx configuration. This `docker/` directory holds the complete stack's orchestration, shared settings and startup instructions.

`compose.yaml` owns all three services, their startup health checks, network, ports and volumes. The backend connects to `jdbc:postgresql://postgres:5432/gw2` on the Compose network. Only the frontend publishes a host port; the database is not inside the backend container.

## Optional configuration

Defaults work without creating a configuration file. To customize them, copy `compose.env.example` to `compose.env`, edit it, then use it for Compose commands:

```sh
docker compose --env-file compose.env up --build --wait
```

| Setting | Default | Meaning |
| --- | --- | --- |
| `GW2_STACK_HTTP_PORT` | `8088` | Browser port on the host. |
| `GW2_STACK_HTTP_BIND` | `127.0.0.1` | Host interface to listen on. |
| `GW2_STACK_DB_PASSWORD` | `gw2-local-only` | Password shared by this stack's PostgreSQL and backend. |

The local configuration file is gitignored. The repository-root development `.env` and its database credentials or GW2 key are not passed to these containers. Changing the database password after initialization requires resetting this stack's cache volume so PostgreSQL initializes with the new password.

## Production configuration and public Oracle host

[`compose.production.yaml`](compose.production.yaml) is a standalone alternative to `compose.yaml` (never combine them). `GW2_STACK_DB_PASSWORD` is required: unset or blank makes Compose refuse to render, and there is no `gw2-local-only` fallback. Only the explicit variables in the file reach the containers; a developer's `DATABASE_*` or `GW2_API_KEY` is not forwarded. Supply the settings with `--env-file` from a file outside the repository:

```sh
docker compose -f compose.production.yaml --env-file /path/to/production.env config   # static check, no daemon
```

This file publishes only the frontend, on loopback by default, over plain HTTP. It does not configure a public TLS edge or a separate restricted database application role. `Forwarded`/`X-Forwarded-*` headers are deliberately not trusted.

The public Oracle deployment is already running behind host Nginx and Certbot. **Every command on that host must also use its protected HTTPS Compose override and explicit production environment file**; the standalone production file is not a complete reproduction of the live deployment. The override supplies the exact canonical HTTPS origin and the separate restricted `gw2_app` credential. See [Current State](../docs/CURRENT_STATE_SPEC.md#required-host-configuration) for the verified inputs and [#228](https://github.com/Nebettadjsr/GW2/issues/228) for the portable-configuration follow-up. Do not run the local reset commands below against the public stack.

Both Compose files also forward optional `GW2_OWNER_ACCOUNT_GUID` (blank grants nobody; a malformed value fails backend startup), pass the optional public `VITE_OPERATOR_*`/`VITE_HOSTING_REGION`/`VITE_PUBLIC_DOMAIN`/`VITE_CONTACT_EMAIL` values to the frontend build (unset stays "not configured"), and bound each container's stdout/stderr with `GW2_STACK_LOG_MAX_SIZE` (default `10m`) and `GW2_STACK_LOG_MAX_FILE` (default `3`). These are size and file-count bounds, not a time guarantee. The proxy access log records only method, path without query string, status, size and timing; request-bound error lines (which echo the request line, query string and Referer even at critical level) are suppressed, while startup events stay in the global error log. Activity and performance come from the existing owner operations aggregates, and CPU/RAM from Oracle metrics.

Checks: `python -m unittest discover -s .github/scripts` renders both files with synthetic values and a poisoned environment and runs a throwaway Nginx for the logging check. CI installs native Nginx so that check executes; platforms without it report an explicit skip. Public release verification has separately checked the real HTTPS/Origin/session boundary, restricted DB role, private ports and application health. Controlled CSV/deletion/browser and container checks have their own evidence; do not infer an exhaustive live journey from the static Compose checks. [Current State](../docs/CURRENT_STATE_SPEC.md#verification-evidence-and-limits) records the published release and limits.

## Status, logs and shutdown

```sh
docker compose ps
docker compose logs --tail 100
docker compose down
```

`down` stops the entire stack and keeps its cache volumes. Start again with the startup command above. If you use `compose.env`, include `--env-file compose.env` in these commands too.

To discard this local stack's database, icon and operational-history caches:

```sh
docker compose down --volumes
docker compose up --build --wait
```

The `gw2-postgres` volume holds PostgreSQL data, `gw2-icons` downloaded icons and `gw2-ops` sparse activity CSV/index files. They are disposable caches; deleting `gw2-ops` discards its historical observations. These commands target the local `gw2` Compose project; if you set another project name with `-p`, use that name consistently.

These startup/reset commands describe the local HTTP stack. See [Architecture](../docs/ARCHITECTURE.md) and [Current State](../docs/CURRENT_STATE_SPEC.md#public-oracle-deployment) for the public host, and [compose smoke](../scripts/compose-smoke.sh) for automated stack verification.

## Search-engine indexing

The frontend defaults to `X-Robots-Tag: noindex, nofollow` (including errors and `/api/`), and
`index.html` carries the matching meta tag. Home has one separate public HTML
entry, `welcome.html`, served at `/home`. Its successful/cached responses and HTML metadata allow
indexing; errors remain noindex. Legacy `/welcome` and `/welcome.html` requests redirect to `/home`.
`robots.txt` denies all paths except `/home` and `/assets/`, the resources needed to render it.
Assets themselves retain their noindex response header. No sitemap is published. Tool/hash pages
and the legal/privacy/storage pages remain excluded.

The public Oracle deployment applies the same successful-Welcome-only exception at its HTTPS edge;
see [current state](../docs/CURRENT_STATE_SPEC.md) for the exact revision and verification evidence.
Crawlers blocked by robots.txt cannot read the pages' noindex tags; a search engine can still list
a blocked URL discovered elsewhere without indexing its contents. See
[Google's distinction between crawling and indexing](https://developers.google.com/search/docs/crawling-indexing/robots/intro).
These directives are not access control, confidentiality or an anti-scraping guarantee.
