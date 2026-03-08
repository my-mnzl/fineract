# AGENTS.md

## Cursor Cloud specific instructions

### Overview

Apache Fineract is a Java 21 / Spring Boot microfinance platform built with Gradle. The single deployable is the `fineract-provider` module.

### Services

| Service | Required | How to Run |
|---------|----------|------------|
| Database (via Docker) | Yes | `docker compose -f config/docker/compose/postgresql.yml up -d` |
| Fineract Server | Yes | See startup command below |

### Starting the Fineract Server (dev mode)

Docker must be running first (`sudo dockerd &` if needed, then `sudo chmod 666 /var/run/docker.sock`).

Start the database:
```
docker compose -f config/docker/compose/postgresql.yml up -d
```

Start the server: source the env vars from `config/docker/env/fineract-postgresql.env` and `config/docker/env/postgresql.env`, then run `./gradlew :fineract-provider:devRun`. The required env vars are `FINERACT_HIKARI_DRIVER_SOURCE_CLASS_NAME`, `FINERACT_HIKARI_JDBC_URL`, `FINERACT_HIKARI_USERNAME`, `FINERACT_HIKARI_PASSWORD`, `FINERACT_DEFAULT_TENANTDB_PORT`, `FINERACT_DEFAULT_TENANTDB_UID`, `FINERACT_DEFAULT_TENANTDB_PWD`, `FINERACT_DEFAULT_TENANTDB_HOSTNAME`, `FINERACT_DEFAULT_TENANTDB_NAME`. All values come from the env files in `config/docker/env/`.

The server listens on **port 8080** (HTTP) when run via `devRun` (not 8443/HTTPS as in Docker production mode).

### Gotchas

- `devRun` skips quality checks (checkstyle, spotless, spotbugs, errorprone) for faster startup. Use `bootRun` for full checks.
- Default API credentials: username `mifos`, password `password`. All requests need header `Fineract-Platform-TenantId: default`.
- The Docker compose init script automatically creates both tenant databases; you do not need to run `createPGDB` manually.

### Testing

- **Unit tests**: `./gradlew test -x :twofactor-tests:test -x :oauth2-tests:test -x :integration-tests:test -x :fineract-e2e-tests-runner:test` (~748 tests, ~2 min)
- **Lint**: `./gradlew spotlessCheck` (pre-existing violations may exist on some branches; fix with `./gradlew spotlessApply`)
- **Integration tests**: require server + DB running; see `.github/workflows/` for CI details.
