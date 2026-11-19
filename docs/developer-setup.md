# cce-admin-service — Developer Setup

How to run the service and its local environment on your machine. The local environment (Postgres,
Keycloak, scripts) is built and tested (S0). Everything about the Java application is the planned
design; each part is marked with the sub-task that builds it and is confirmed there.

## 1. Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| **Docker** | 24.x+ | `docker --version` |
| **Docker Compose** | 2.x (plugin) | `docker compose version` |
| **curl** | any | `curl --version` |
| **jq** | 1.6+ | `jq --version` |
| **Git** | 2.x+ | `git --version` |
| **Java** | 21 (from S1) | `java --version` → `21.x` |
| **Gradle** | nothing to install: the repo carries the wrapper `./gradlew` (from S1; version confirmed in S1, `gateway-service` uses 8.14.3) | `./gradlew --version` |

Ports used on your machine: **5434** (Postgres) and **8180** (Keycloak), bound to `127.0.0.1`
only. They avoid 5433 and 8080, used by `extras/cce-local-setup`.

From S1 on, the service itself uses **8085**. The optional local gateway (S13) uses **8095**,
because 8090 is the local Protocol service.

## 2. Clone & Build

```bash
git clone https://github.com/mehul-kunwar-tarento/cce-admin-service-poc.git
cd cce-admin-service-poc
```

From S1 on (commands confirmed in S1):

```bash
# Build with tests (needs Docker running: the migration test uses Testcontainers)
./gradlew build

# Build the JAR without running tests
./gradlew bootJar
```

## 3. Project Structure

```
cce-admin-service-poc/
├── README.md                     # Quick start
├── ARCHITECTURE.md               # What the service is and why
├── SPEC.md                       # Decisions (D1…) and sub-tasks (S0…)
├── docker-compose.yml            # Local Postgres + Keycloak
├── .env.example                  # Settings template (copy to .env)
├── .gitignore                    # Keeps .env out of git
├── local/
│   ├── keycloak/
│   │   └── cce-realm-local.json  # Realm "cce", imported when Keycloak starts
│   ├── create-superadmin.sh      # Creates the test user superadmin
│   └── get-token.sh              # Prints a token for superadmin
└── docs/                         # This guide and the other design docs
```

Planned additions (sub-task in brackets):

```
cce-admin-service-poc/
├── build.gradle, settings.gradle          # Gradle build (S1)
├── gradlew, gradlew.bat, gradle/wrapper/  # Gradle wrapper, committed (S1)
├── src/
│   ├── main/
│   │   ├── java/<base package>/…          # Layout: architecture-overview.md §6 (S1 onwards)
│   │   └── resources/
│   │       ├── application.yml            # Environment-neutral defaults (S1)
│   │       ├── application-local.yml      # Every localhost default (S1)
│   │       └── db/migration/
│   │           ├── V1__schema.sql         # 7 tables (S2)
│   │           └── V2__seed.sql           # 12 menus, SUPER_ADMIN (S2)
│   └── test/java/<base package>/…         # Tests (§13)
└── local/
    ├── gateway.env.example                # Env vars for the local gateway, no secrets (S13)
    └── scenarios/
        ├── README.md                      # Start order and how to run the scripts (S13)
        └── s1.sh … s6.sh                  # End-to-end scenario scripts (S13)
```

## 4. Gradle Build Configuration

Planned dependencies, added by the sub-task that first needs them. The real `build.gradle` (versions,
exact artifact ids) is confirmed in S1 and each later sub-task.

| Dependency | Purpose | Added in |
|------------|---------|----------|
| Spring Boot 3.x plugin + `io.spring.dependency-management`, Java 21 toolchain | Base | S1 |
| `spring-boot-starter-web` | Spring Web MVC, embedded Tomcat | S1 |
| `spring-boot-starter-validation` | Bean validation on request bodies | S1 |
| `spring-boot-starter-data-jpa` | JPA / Hibernate | S1 |
| PostgreSQL JDBC driver | Database | S1 |
| springdoc-openapi | Swagger UI + `/v3/api-docs` | S1 |
| Flyway | `V1__schema.sql`, `V2__seed.sql` | S2 |
| Testcontainers (Postgres) | `MigrationTest` | S2 |
| `spring-boot-starter-oauth2-resource-server` | JWT validation via JWKS | S3 |
| `org.keycloak:keycloak-admin-client` | Keycloak Admin API | S2a |
| WireMock | Fake gateway in tests | S6a |

S1 deliberately has no security (S3) and no Flyway (S2) yet.

## 5. Settings (`.env`)

All passwords live in `.env`, which is in `.gitignore` and never committed.

```bash
cp .env.example .env
```

Fill in every empty value. Any random string works locally; to make one:

```bash
openssl rand -hex 16
```

| Variable | Used by | Meaning |
|---|---|---|
| `POSTGRES_DB` | Postgres | Database name, `admin_service` |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | Postgres | Database login |
| `KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD` | Keycloak | Admin console login (realm `master`) |
| `SUPERADMIN_PASSWORD` | `create-superadmin.sh`, `get-token.sh` | Password of the test user `superadmin` |
| `KEYCLOAK_CLIENT_SECRET` | Keycloak realm import | Secret of client `cce-admin-service` |

## 6. Application Configuration

From S1 on. Two files, one rule (B-13):

| File | Holds | Used |
|------|-------|------|
| `application.yml` | Only environment-neutral defaults: port 8085, timeouts, roles claim path | Always |
| `application-local.yml` | Every `localhost` value: DB URL and user, Keycloak URL, gateway URL `http://localhost:8095`, JWT issuer and audience | Only with `SPRING_PROFILES_ACTIVE=local` |

Without the `local` profile, a required variable with no value stops startup instead of silently
pointing at localhost. The full list of variables, defaults and local values is in
[Data Dictionary — §7](data-dictionary.md#7-configuration-properties).

Secrets are never in either file. Locally the service needs two of the `.env` values:

| Service variable | Local value | Sub-task |
|------------------|-------------|----------|
| `DB_PASSWORD` | `POSTGRES_PASSWORD` from `.env` | S1 |
| `KEYCLOAK_CLIENT_SECRET` | `KEYCLOAK_CLIENT_SECRET` from `.env` | S2a |

How they are passed to `./gradlew bootRun` is confirmed in S1 / S2a.

## 7. Docker Compose (Local Environment)

### What runs

| Container | Image | Port on your machine | Data kept in volume |
|---|---|---|---|
| `cce-admin-postgres` | `postgres:16` | `127.0.0.1:5434` | `postgres-data` |
| `cce-admin-keycloak` | `quay.io/keycloak/keycloak:26.5.4` (same as `deploy-scripts`) | `127.0.0.1:8180` | `keycloak-data` |

- Postgres holds only the service's database `admin_service`. It is empty until the service's
  Flyway migrations run (S2).
- Keycloak runs in `start-dev` mode and keeps its own data in its built-in database
  (in the `keycloak-data` volume), not in Postgres.
- Both have health checks; `docker compose ps` shows `healthy` when they are ready
  (about 20 seconds).

### Commands

| What | Command | Local data |
|---|---|---|
| Start | `docker compose up -d` | kept |
| Status | `docker compose ps` | — |
| Logs | `docker compose logs -f keycloak` | — |
| Stop | `docker compose down` | kept |
| Reset | `docker compose down -v`, then start again and run `create-superadmin.sh` | **deleted** |

### Connect to the database

```bash
docker compose exec postgres psql -U admin_service -d admin_service
```

With `psql` installed on your machine: `psql -h localhost -p 5434 -U admin_service -d admin_service`
(password: `POSTGRES_PASSWORD` from `.env`).

## 8. Keycloak

Admin console: <http://localhost:8180> — log in with `KEYCLOAK_ADMIN_USER` /
`KEYCLOAK_ADMIN_PASSWORD`, then switch to realm **cce** (top-left).

### Realm `cce` (`local/keycloak/cce-realm-local.json`)

Imported automatically when Keycloak starts, **only if the realm does not exist yet**. After
changing this file, reset (`docker compose down -v`) for the change to take effect.

| Item | Purpose |
|---|---|
| Realm role `SUPER_ADMIN` | Role of the first admin (SPEC D5) |
| Client `cce-admin-service` | Confidential client with a service account. The service uses it to call the Keycloak Admin API. Secret from `.env`. Roles (in `realm-management`, realm `cce` only): `view-users`, `query-users`, `manage-users` |
| Client `cce-admin-local` | Public client for test tokens. Browser login only, redirect `http://localhost:9999/callback`. Adds `cce-admin-service` to the token's `aud`. Local only |

The realm file holds no people and no secrets: `superadmin` comes from the script below, and the
client secret is read from `.env` (`${KEYCLOAK_CLIENT_SECRET}`) during import.

### Test user `superadmin`

```bash
./local/create-superadmin.sh
```

Creates `superadmin` (email `superadmin@cce.local`, name Super Admin), sets its password from
`SUPERADMIN_PASSWORD` and gives it realm role `SUPER_ADMIN`. Safe to run again: an existing
user is not created twice. Run it after every reset.

On other environments a person does this by hand in the Keycloak admin console (SPEC D5).

## 9. Getting a Token

```bash
TOKEN=$(./local/get-token.sh)
```

The script logs in the way a browser does (login page → code → token), because only that kind of
login puts `auth_time` in the token (see Troubleshooting). The token is valid for 5 minutes.

To read what is inside:

```bash
./local/get-token.sh | jq -R 'split(".") | .[1] | gsub("-";"+") | gsub("_";"/") | @base64d | fromjson'
```

Claims to expect:

| Claim | Local value |
|---|---|
| `iss` | `http://localhost:8180/realms/cce` |
| `aud` | `["cce-admin-service", "account"]` |
| `realm_access.roles` | contains `SUPER_ADMIN` |
| `auth_time` | time of login (seconds since 1970, UTC) |
| `sub` | `superadmin`'s Keycloak id (new after every reset) |

## 10. Running the Application

From S1 on.

**Start order:**

```bash
docker compose up -d                              # 1. Postgres + Keycloak (§7)
./local/create-superadmin.sh                      # 2. first time, and after every reset (§8)
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun     # 3. the service on :8085 (S1)
```

What happens at startup:

1. Flyway runs `V1__schema.sql` and `V2__seed.sql` on a fresh database (S2).
2. `BootstrapAdminRunner` looks up `BOOTSTRAP_ADMIN_USERNAME` (locally `superadmin`) in Keycloak
   and links it as the first local SUPER_ADMIN (S2a). If it can't, it logs an ERROR and the
   service keeps running.

Until `superadmin` is linked, its valid token gets 403 `FORBIDDEN` — the service only accepts
local ACTIVE users (B-12).

### 10.1 Optional: the gateway locally (S13)

Without a gateway the service works, but every call that ends in a gateway sync (create / delete a
role, save permissions, `/sync`) returns 502 `Gateway not reachable at http://localhost:8095` while
keeping the DB change. That is expected.

To run the real sync and the real per-URL permission checks, run gateway-service **unchanged** on
:8095 with environment variables only (S13):

| Variable | Value |
|----------|-------|
| `SERVER_PORT` | `8095` |
| `DATABASE_URL` | R2DBC URL of Postgres on port 5434, database `admin_service` |
| `KEYCLOAK_SERVER_URL` | `http://localhost:8180` |
| `KEYCLOAK_TARGET_REALM` | `cce` |
| Keycloak master-realm admin login | From `.env` (`KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD`) |
| `JWT_ISSUER` | `http://localhost:8180/realms/cce` |
| `ADMIN_SERVICE_URI` | `http://localhost:8085` |

The variables come in `local/gateway.env.example` (no secrets); exact names, values and the run
command are confirmed in S13. Start order: §10 steps 1–3, then the gateway.

## 11. Verifying the Setup

Local environment (S0):

```bash
docker compose ps                                                     # both "healthy"
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8180/realms/cce   # 200
./local/get-token.sh > /dev/null && echo "token OK"
```

Service (planned; sub-task in brackets):

```bash
curl -s http://localhost:8085/admin/v1/health | jq                    # {"status":"UP","db":"UP"} (S1)
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8085/swagger-ui.html   # 200 (S1)

TOKEN=$(./local/get-token.sh)
curl -s http://localhost:8085/admin/v1/me -H "Authorization: Bearer $TOKEN" | jq
# 200 with username "superadmin", roles ["SUPER_ADMIN"] (needs S2a + S12)
```

## 12. Useful Gradle Commands

Planned; confirmed in S1.

| Command | Purpose |
|---------|---------|
| `./gradlew build` | Full build + all tests (what "done" requires, PRD §11) |
| `./gradlew test` | Tests only |
| `./gradlew test --tests MigrationTest` | One test class |
| `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` | Run the service locally |
| `./gradlew bootJar` | Build the JAR only (no tests) |
| `./gradlew dependencies` | Show the dependency tree |

## 13. Testing

```bash
./gradlew build
```

| Kind | How | Sub-task |
|------|-----|----------|
| Service-layer tests | `KeycloakClient` and `GatewaySyncClient` mocked, so "Keycloak worked, DB failed" can be tested without a real Keycloak (PRD §9 "Testability") | S2a onwards |
| Migration test | `MigrationTest` runs Flyway on a Testcontainers Postgres — **Docker must be running** | S2 |
| Gateway client test | WireMock plays the gateway: 200 / 206 / 500 / refused / timeout | S6a |
| Audit assertion | `AuditAssert.exactlyOneRow(action, entityId)`: every mutating endpoint has a test that asserts exactly one `audit_log` row (B-50) | S4 onwards |
| Error-handler / security tests | Test-only controllers under `src/test` | S1, S3 |
| End-to-end scenarios | `local/scenarios/s1.sh` … `s6.sh`: curl through the local gateway, PASS / FAIL per check | S13 |

Every acceptance criterion in SPEC §4 names the test that proves it.

## 14. Troubleshooting

| Problem | Cause and fix |
|---|---|
| `port is already allocated` on `up` | Another program uses 5434 or 8180. Find it with `ss -ltnp \| grep -E ':(5434\|8180) '` |
| `set POSTGRES_PASSWORD in .env` (or another variable) on `up` | `.env` is missing or has an empty value. See §5 |
| Keycloak stays `starting` / `unhealthy` | `docker compose logs keycloak`. It needs about 20 seconds; the health check gives it up to 2 minutes |
| Changed `cce-realm-local.json`, nothing changed in Keycloak | The realm is imported only when it does not exist. Reset: `docker compose down -v && docker compose up -d`, then `./local/create-superadmin.sh` |
| `get-token.sh`: `login failed for superadmin` | User missing (e.g. after a reset) or wrong password. Run `./local/create-superadmin.sh` |
| Token has no `auth_time` | It came from a password-grant login. In Keycloak 26.5.4 only a browser-style login sets `auth_time`. Use `get-token.sh` |
| `create-superadmin.sh` prints `Logging into …` and `Created new user with id …` | Info lines from Keycloak's `kcadm` tool, not errors |
| Service won't start, error names `DB_URL`, `JWT_ISSUER` or `JWT_AUDIENCE` (planned, S1 / S3) | Started without the `local` profile. Use `SPRING_PROFILES_ACTIVE=local` |
| `superadmin`'s token gets 403 `FORBIDDEN` (planned, S2a / S3) | Not linked yet. Check the startup log for the bootstrap ERROR (`create superadmin in Keycloak first`, missing `SUPER_ADMIN` role, no email) and that `BOOTSTRAP_ADMIN_USERNAME` is set; fix, then restart the service. After a Keycloak reset, `superadmin` has a new id — reset the DB too |
| Creating a role or saving permissions returns 502 `Gateway not reachable at http://localhost:8095` (planned, S6 / S11) | The local gateway isn't running. The DB change is saved; start the gateway (§10.1) and call `POST /admin/v1/roles/{id}/sync` |
| Reset password returns 502 locally (planned, S10) | S0's Keycloak has no mail server. Expected locally; tests mock Keycloak |
| `./gradlew build` fails in `MigrationTest` (planned, S2) | Docker isn't running; Testcontainers needs it |

---

Not covered in this guide: IDE setup, code-quality tooling (SonarQube / JaCoCo; not in v1), and
any server deployment (iteration 2).
