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

## Status, logs and shutdown

```sh
docker compose ps
docker compose logs --tail 100
docker compose down
```

`down` stops the entire stack and keeps its cache volumes. Start again with the startup command above. If you use `compose.env`, include `--env-file compose.env` in these commands too.

To discard this stack's database and icon caches and let the app fetch them again:

```sh
docker compose down --volumes
docker compose up --build --wait
```

The `gw2-postgres` volume holds PostgreSQL data and `gw2-icons` holds downloaded icons. Both are disposable caches. These commands target the `gw2` Compose project; if you set another project name with `-p`, use that name consistently.

This is the local HTTP stack. Public hosting and TLS are described separately in the [roadmap](../docs/ROADMAP.md). For automated verification and resource measurements, see [current architecture](../docs/CURRENT_ARCHITECTURE.md#docker-compose-stack-story-infra-008) and [performance measurements](../docs/PERFORMANCE_MEASUREMENTS.md).
