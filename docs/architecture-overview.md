/# cce-admin-service — Architecture Overview

## 1. Purpose

The Admin Service is a standalone **Spring Boot 3.x** application that sits behind the gateway and
manages who may see and call what in CCE. It is responsible for:

1. **Owning** users, roles, menus and API permissions in its own PostgreSQL database `admin_service`
2. **Provisioning** user accounts in Keycloak (create, update, enable/disable, password reset) and
   assigning or removing realm roles on them
3. **Triggering** the gateway's permission sync, so every role exists as a Keycloak realm role and
   the gateway enforces the role's API permissions
4. **Serving** `GET /admin/v1/me/menus`, the menu tree a console uses to build its sidebar
5. **Recording** every change as one `audit_log` row, and every half-done change across Keycloak
   and the DB as an ERROR log plus a `*_FAILED` audit row

> **Scope:** backend only; the admin UI (`cce-admin-ui`) is a separate repo and comes later.
> **Iteration 1** (this design, SPEC v1) is built and shown **locally**, driven through Swagger UI
> and curl. **Iteration 2** deploys it to dev. Nothing goes to prod in this exercise.
> Decisions are numbered D1–D14 and sub-tasks S0–S13 in [`SPEC.md`](../SPEC.md).

## 2. System Context

```
┌─────────────────────────────────────────────────────────────┐
│                           Callers                           │
│    Iteration 1: Swagger UI / curl                           │
│    Later: cce-admin-ui, cce-insights-ui (GET /me/menus)     │
└──────────────────────────────┬──────────────────────────────┘
                               │  HTTP, Bearer token, /admin/v1/**
                               ▼
┌─────────────────────────────────────────────────────────────┐
│              gateway-service (exists, not changed)          │
│     Validates the JWT, checks the caller's role against     │
│     api_permissions, proxies /admin/** to the service       │
└──────────────────────────────┬──────────────────────────────┘
                               │  proxied request
                               ▼
┌─────────────────────────────────────────────────────────────┐
│             ★ cce-admin-service (this service) ★            │
│            Spring Boot 3.x · Spring Web MVC · :8085         │
│                                                             │
│ 1. Validate the JWT again (JWKS, iss, aud, expiry)          │
│ 2. Find the local user by sub; must be ACTIVE               │
│ 3. Run the read-only checks (404 / 409 / 400)               │
│ 4. Write the DB change + one audit_log row                  │
│ 5. Call the Keycloak Admin API for user changes             │
│ 6. Call gateway sync after role / permission changes        │
└───────┬────────────────────┬────────────────────┬───────────┘
        │ JPA                │ Keycloak           │ POST /api/permissions/
        │                    │ Admin API          │      sync-to-keycloak
        ▼                    ▼                    ▼
┌────────────────┐  ┌──────────────────┐  ┌───────────────────┐
│ PostgreSQL     │  │ Keycloak         │  │ gateway-service   │
│ admin_service  │  │ realm cce        │  │ reads             │
│ 7 tables;      │  │ users, realm-    │  │ api_permissions,  │
│ api_permissions│  │ role mappings,   │  │ creates / deletes │
│ is also read   │  │ enabled flag,    │  │ realm roles in    │
│ by the gateway │  │ reset email      │  │ Keycloak          │
└────────────────┘  └──────────────────┘  └───────────────────┘
```

The service is never internet-facing. In every non-local environment callers reach it only
through gateway-service (PRD §9 "Network").

## 3. Architecture Principles

| Principle | Application |
|-----------|-------------|
| **Spring Boot Standard** | Spring Web MVC (blocking; low-traffic CRUD), Spring Data JPA, Flyway, `@RestControllerAdvice`, OAuth2 Resource Server |
| **One Name, Three Systems** | `roles.name` = Keycloak realm role name = `api_permissions.permission_name`, so a role name never changes (D9, D11) |
| **Gateway Owns `api_permissions`' Shape** | Created only `IF NOT EXISTS`, never altered; `id` is `BIGSERIAL` (D1, D2). Rows whose `permission_name` is not one of our roles are never read, changed or deleted (D11) |
| **Every Role Keeps ≥ 1 Permission Row** | Each role always has `GET /admin/v1/me/menus`, so its realm role exists in Keycloak from creation and its users can load their sidebar (D4, D6) |
| **No Cross-System Transaction** | Keycloak calls run inside the open DB transaction, so a Keycloak failure rolls the DB back. Half-done states → 502 + ERROR log + `*_FAILED` audit row in its own transaction. Only create user is undone; nothing is retried (D3, D8) |
| **Defense in Depth** | The service validates the JWT itself and requires a local ACTIVE user, even behind the gateway. Which role may call which URL stays the gateway's job (B-10, B-12) |
| **Full-Set Replace** | `PUT …/roles`, `…/menus`, `…/permissions` take the full new set; the service computes the diff. Same set as now = nothing written, no audit |
| **Audit Every Change** | Exactly one `audit_log` row per change, in the same transaction (B-50) |
| **Fail Loudly, Never Leak** | Every error uses `{error:{code,message}}`; unplanned errors → 500 with a reference id, details only in the log (D12) |
| **Environment-Neutral Config** | `localhost` defaults live only in the `local` profile; a missing variable elsewhere stops startup; no secrets in the repo (B-13) |

## 4. Technology Stack

| Concern | Technology | Version |
|---------|------------|---------|
| Language | Java | 21 |
| Framework | Spring Boot | 3.5.6 (same as `gateway-service`) |
| Build tool | Gradle (wrapper `gradlew`, Groovy DSL, same as `gateway-service`) | 8.14.3 |
| REST endpoints | Spring Web MVC (`@RestController`) | |
| Persistence | Spring Data JPA + Hibernate | |
| Migrations | Flyway (`src/main/resources/db/migration`) | |
| Database | PostgreSQL | 16 (local, S0) |
| Security | `spring-boot-starter-oauth2-resource-server` (JWKS) | |
| Keycloak calls | `org.keycloak:keycloak-admin-client` | |
| Gateway call | Spring `RestClient` | |
| API docs | springdoc-openapi (OpenAPI 3 + Swagger UI) | 2.8.13 |
| Identity provider | Keycloak | 26.5.4 (local, S0; same as `deploy-scripts`) |
| Testing | JUnit 5, Spring Boot Test, Testcontainers (Postgres), WireMock (fake gateway) | |
| Coverage / static analysis | — | not in v1 |

## 5. Key Concepts

### 5.1 Terms

| Term | Meaning |
|------|---------|
| **User** | A person who can log in. 1:1 with a Keycloak user, joined by `keycloak_id` = JWT `sub`. No passwords stored locally |
| **Role** | A named bundle of access, e.g. `FACILITY_ADMIN`. UPPER_SNAKE_CASE. The name never changes |
| **Menu** | One screen of `cce-insights-ui`. `NAV` = sidebar item; `DETAIL` = drill-down page under a NAV |
| **Permission** | One (HTTP method, URI pattern) a role may call. Stored in `api_permissions`, enforced by the gateway |

### 5.2 Two independent controls

| Control | Meaning | Wrong setup |
|---------|---------|-------------|
| **Role → Menu** (soft) | What a console shows | Looks odd, not a security problem |
| **Role → Permission** (hard) | What the gateway lets through | A security problem |

Giving a role the Compliance menu does not give it the compliance APIs, and the other way round.

### 5.3 Who creates what in Keycloak

| Responsibility | Owner |
|----------------|-------|
| Create / delete realm role definitions | gateway-service, via `POST /api/permissions/sync-to-keycloak`, from `api_permissions` |
| Create / update the Keycloak user account | cce-admin-service |
| Assign / remove realm roles on a user | cce-admin-service |
| Enable / disable, password-reset email | cce-admin-service |

After every sync, Keycloak's realm roles = the distinct `permission_name`s that have at least one
row, plus Keycloak's built-ins. A realm role with no rows is deleted, which is why every role keeps
at least one row (D4).

### 5.4 Runtime shape

```
┌──────────────────────────────────────────────────────────────┐
│                   Spring Boot Application                    │
│                                                              │
│  ┌──────────────────┐  ┌──────────────────────────────────┐  │
│  │ Embedded Tomcat  │  │ Security filter chain            │  │
│  │ port: 8085       │──│  JWT (JWKS) → roles claim →      │  │
│  └──────────────────┘  │  ActiveUserFilter (sub, ACTIVE)  │  │
│                        └────────────────┬─────────────────┘  │
│  ┌──────────────────────────────────────▼─────────────────┐  │
│  │ @RestControllers  /admin/v1/{me, users, roles, menus}  │  │
│  └──────────────────────────────────────┬─────────────────┘  │
│  ┌──────────────────────────────────────▼─────────────────┐  │
│  │ Services: checks, diffs, guards, transactions          │  │
│  └─────────┬──────────────────┬──────────────────┬────────┘  │
│  ┌─────────▼──────┐  ┌────────▼───────┐  ┌───────▼────────┐  │
│  │ Repositories   │  │ KeycloakClient │  │ GatewaySync-   │  │
│  │ (JPA)          │  │                │  │ Client         │  │
│  └────────────────┘  └────────────────┘  └────────────────┘  │
│  ┌────────────────────────┐  ┌────────────────────────────┐  │
│  │ AuditWriter            │  │ GlobalExceptionHandler     │  │
│  │ one row per change     │  │ {error:{code,message}}     │  │
│  └────────────────────────┘  └────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ /admin/v1/health · /swagger-ui.html · /v3/api-docs     │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

## 6. Package Structure

Layout from the SPEC deliverables. The sub-task that adds each file is in brackets; files from
later sub-tasks are planned. Package names not given in the SPEC are marked.

```
org/openphc/cce/admin/
├── CceAdminServiceApplication.java                # @SpringBootApplication entry point; adds RequiredSettingsCheck (S1)
│
├── api/                                           # Response envelope
│   ├── ApiResponse.java                           #   {data, pagination?} (S1)
│   ├── Pagination.java                            #   {page, pageSize, total} (S1)
│   └── PageParams.java                            #   page from 1; pageSize default 20, max 100 (S1)
│
├── error/                                         # Errors
│   ├── ErrorCode.java                             #   PRD Appendix B codes + D12 codes (S1); USERNAME_IN_USE (S8)
│   ├── ApiException.java                          #   (status, code, message) thrown on purpose (S1)
│   ├── ErrorResponse.java                         #   {error:{code,message}} (S1)
│   └── GlobalExceptionHandler.java                #   @RestControllerAdvice: D12 mapping, reference id on 500 (S1)
│
├── health/
│   ├── HealthController.java                      #   GET /admin/v1/health → {status, db}, not enveloped (S1)
│   ├── HealthResponse.java                        #   {status, db} (S1)
│   └── DatabaseCheck.java                         #   Pool connection + isValid(1s); never throws (S1)
│
├── config/                                        # Spring configuration
│   ├── RequiredSettingsCheck.java                 #   Stops startup naming any missing or empty DB_URL / DB_USERNAME / DB_PASSWORD (S1)
│   ├── OpenApiConfig.java                         #   Swagger UI + /v3/api-docs, title "CCE Admin Service API" v1 (S1)
│   ├── SecurityConfig.java                        #   Stateless; open paths; everything else authenticated (S3)
│   ├── JwtProperties.java                         #   JWT_ISSUER, JWT_AUDIENCE, JWT_JWK_SET_URI, JWT_ROLES_CLAIM_PATH (S3)
│   ├── KeycloakProperties.java                    #   KEYCLOAK_BASE_URL, _REALM, _CLIENT_ID, _CLIENT_SECRET; 10s timeout (S2a)
│   └── GatewayProperties.java                     #   GATEWAY_BASE_URL (S6a)
│
├── security/                                      # Per-request identity
│   ├── AudienceValidator.java                     #   Checks aud (Spring does not by itself) (S3)
│   ├── RolesClaimConverter.java                   #   Roles from a dot-notation claim path (S3)
│   ├── ActiveUserFilter.java                      #   sub → users row, ACTIVE check, CurrentUser, last_login_at (S3, D7)
│   ├── CurrentUser.java                           #   id, username, roles (S3; package confirmed in S3)
│   ├── JsonAuthEntryPoint.java                    #   401 in the B-03 shape (S3)
│   └── JsonAccessDeniedHandler.java               #   403 in the B-03 shape (S3)
│
├── entity/                                        # JPA entities for the 7 tables
│   ├── User.java                                  #   (S3)
│   ├── AuditLog.java                              #   (S4)
│   ├── Menu.java, RoleMenu.java                   #   (S5)
│   └── Role.java, ApiPermission.java, UserRole.java   # ApiPermission.id is Long (S6, D1)
│
├── repository/                                    # Spring Data JPA
│   ├── UserRepository.java                        #   (S3)
│   ├── AuditLogRepository.java                    #   insert + read only, no update/delete (S4)
│   ├── MenuRepository.java, RoleMenuRepository.java   # (S5)
│   └── …Role / ApiPermission / UserRole repositories  # (names confirmed in S6)
│
├── client/                                        # Everything that leaves the service
│   ├── KeycloakClient.java                        #   Interface: findUserByUsername, hasRealmRole (S2a); createUser, assignRealmRoles, deleteUser (S8);
│   │                                              #   updateUser (S9); setEnabled/removeRealmRoles/sendUpdatePasswordEmail (S10)
│   ├── KeycloakAdminClient.java                   #   Implementation on keycloak-admin-client, client_credentials (S2a)
│   ├── KeycloakUser.java                          #   id, username, email, firstName, lastName (S2a)
│   ├── GatewaySyncClient.java                     #   Interface: sync(roleId) (S6a)
│   ├── HttpGatewaySyncClient.java                 #   RestClient, no auth header, 10s timeout (S6a)
│   └── GatewaySyncException.java                  #   Reason text → 502 KEYCLOAK_SYNC_FAILED (S6a)
│
├── bootstrap/                                     # First admin (D5)
│   ├── BootstrapAdminRunner.java                  #   ApplicationRunner: Keycloak lookup by username (S2a)
│   └── BootstrapAdminService.java                 #   One @Transactional insert: users + user_roles + audit (S2a)
│
├── audit/
│   ├── AuditAction.java                           #   Enum of action names (S4; each later sub-task adds its own)
│   └── AuditWriter.java                           #   record() MANDATORY tx; recordFailure() REQUIRES_NEW, never throws (S4)
│
├── controller/                                    # HTTP only: parse, call service, wrap in envelope
│   ├── MenuController.java                        #   /menus (S5)
│   ├── RoleController.java                        #   /roles, /roles/{id} (S6)
│   ├── RoleMenuController.java                    #   /roles/{id}/menus (S7)
│   ├── UserController.java                        #   /users… (S8, S9, S10)
│   ├── RolePermissionController.java              #   /roles/{id}/permissions, /roles/{id}/sync (S11)
│   └── MeController.java                          #   /me, /me/menus (S12)
│
├── service/                                       # The rules: order, diffs, guards, transactions
│   ├── MenuService.java                           #   (S5)
│   ├── RoleService.java                           #   (S6)
│   ├── RoleMenuService.java                       #   (S7)
│   ├── UserCreateService.java                     #   (S8)
│   ├── UserQueryService.java, UserUpdateService.java  # (S9)
│   ├── UserStatusService.java, UserRolesService.java, PasswordResetService.java  # (S10)
│   ├── RolePermissionService.java                 #   (S11)
│   └── MeService.java                             #   (S12)
│
└── <dto package>/                                 # Request / response DTOs (package confirmed in S5)
    ├── MenuRequest, MenuResponse                  #   (S5)
    ├── CreateRoleRequest, UpdateRoleRequest, RoleResponse   # (S6)
    ├── RoleMenusRequest, RoleMenusResponse        #   (S7)
    ├── CreateUserRequest                          #   (S8)
    ├── UserResponse, UpdateUserRequest            #   (S9)
    ├── PermissionRow, RolePermissionsRequest      #   (S11)
    └── MeResponse, MenuNode                       #   (S12)
```

### Resources

```
src/main/resources/
├── application.yml                                # Environment-neutral defaults only (port, timeouts, claim path) (S1)
├── application-local.yml                          # Every localhost default; used only with SPRING_PROFILES_ACTIVE=local (S1)
└── db/migration/
    ├── V1__schema.sql                             # pgcrypto + 7 tables; api_permissions IF NOT EXISTS (S2)
    └── V2__seed.sql                               # 12 menus, SUPER_ADMIN role, its 5 /admin/v1/** rows (S2)
```

### Test Resources

```
src/test/
└── java/org/openphc/cce/admin/
    ├── ConfigFailFastTest.java                    # Missing / empty DB_URL stops startup (S1)
    ├── health/DatabaseCheckTest.java, HealthControllerTest.java   # (S1)
    ├── error/ErrorTestController.java, GlobalExceptionHandlerTest.java  # Test-only endpoints + every error case (S1)
    ├── api/EnvelopeTestController.java, EnvelopeTest.java          # Test-only endpoints + {data} / paging (S1)
    ├── config/OpenApiTest.java                    # Whole app without a database: /v3/api-docs, Swagger UI (S1)
    ├── MigrationTest.java                         # Flyway on Testcontainers Postgres (S2)
    ├── AuditAssert.java                           # exactlyOneRow(action, entityId) helper (S4)
    ├── …test-only controllers                     # Exercise security (S3)
    └── …                                          # Service tests with KeycloakClient and gateway mocked;
                                                   # WireMock as the fake gateway (S6a)
```

## 7. Request Processing Pipeline

### 7.1 Processing steps (one request)

| Step | Component | Description |
|------|-----------|-------------|
| 1 | JWT decoder + `AudienceValidator` | Signature via JWKS, `iss`, `aud`, expiry. Fails → 401 `UNAUTHENTICATED` (B-10) |
| 2 | `RolesClaimConverter` | Reads the token's roles from `JWT_ROLES_CLAIM_PATH` (default `realm_access.roles`). Missing claim → empty list, not a rejection (B-11) |
| 3 | `ActiveUserFilter` | `sub` → `users` row with status ACTIVE, else 403 `FORBIDDEN` (B-12). Updates `last_login_at` from `auth_time` if newer (D7). Puts `CurrentUser` on the request |
| 4 | Controller | Parses and validates the body (bean validation → 400), calls the service, wraps the result in `{data, pagination?}` |
| 5 | Service: checks | Read-only checks in a fixed order (404 → 409 → 400). The first failure stops; nothing is written yet |
| 6 | Service: write | One DB transaction: rows + exactly one `AuditWriter.record()` row. Keycloak calls for user changes run inside it (S9, S10) |
| 7 | Service: after commit | Gateway sync for role create, role delete, permissions save and `/sync` (D3, D4, D10) |
| 8 | `GlobalExceptionHandler` | Any exception from steps 4–7 → the D12 envelope |

### 7.2 Ordering rules

| Rule | Where |
|------|-------|
| All checks before any write | Every mutating endpoint (D9 check order is the model) |
| Keycloak inside the open DB transaction: a Keycloak failure rolls the DB back | Update profile (S9), status, roles, reset password (S10) |
| Create user is the exception: Keycloak create → assign roles → DB transaction; a later failure deletes the new Keycloak account once | S8, D8 |
| Gateway sync only after the DB commit; a sync failure keeps the DB change | S6, S11, D3 |
| A role's realm role exists before any user gets it: role create already syncs | D4 |
| `/me` and `/me/menus` take the caller's roles from `user_roles`, not from the token | S12 |

## 8. External Interfaces

### 8.1 Inbound (from callers, through the gateway)

| Direction | Protocol | Endpoint | Content |
|-----------|----------|----------|---------|
| **IN** | HTTP | `/admin/v1/**` | JSON; Bearer token on everything except health and Swagger |

Full contract: [API Reference](api-reference.md).

### 8.2 Outbound

| Direction | Protocol | Endpoint | Content | Sub-task |
|-----------|----------|----------|---------|----------|
| **OUT** | HTTPS/HTTP | Keycloak Admin API `{KEYCLOAK_BASE_URL}/admin/realms/{realm}/users…` | User accounts, realm-role mappings, enabled flag, execute-actions email. Token via `client_credentials` for client `cce-admin-service`. 10s timeout | S2a, S8–S10 |
| **OUT** | HTTPS/HTTP | Keycloak JWKS `{JWT_ISSUER}/protocol/openid-connect/certs` | Public keys to verify tokens | S3 |
| **OUT** | HTTP POST | `{GATEWAY_BASE_URL}/api/permissions/sync-to-keycloak` | **No auth header** (the endpoint has none). 10s timeout, never retried | S6a |

### 8.3 Shared data

| Table | Written by | Read by |
|-------|------------|---------|
| `api_permissions` | cce-admin-service (only rows whose `permission_name` is one of its roles) | gateway-service (R2DBC), at startup and on every sync |

## 9. Configuration

```
┌──────────────────────────────────────┐
│ Highest Priority                     │
│                                      │
│  1. Environment Variables            │  ← DB_URL, KEYCLOAK_*, JWT_*, GATEWAY_BASE_URL, …
│  2. application-local.yml            │  ← only with SPRING_PROFILES_ACTIVE=local
│  3. application.yml                  │  ← environment-neutral defaults
│                                      │
│ Lowest Priority                      │
└──────────────────────────────────────┘
```

- Every `localhost` value lives only in `application-local.yml`. Without the `local` profile, a
  missing `DB_URL`, `JWT_ISSUER`, `JWT_AUDIENCE`, `KEYCLOAK_BASE_URL`, `KEYCLOAK_CLIENT_SECRET` or
  `GATEWAY_BASE_URL` stops startup instead of silently pointing at localhost (B-13); S1 and S3
  test that the error names the missing variable.
- Secrets come from environment variables only; locally from the gitignored `.env` (S0).
- Full variable list with defaults: [Data Dictionary — Configuration](data-dictionary.md).

## 10. Error Handling Strategy

401 and 403 are written by Spring Security's `JsonAuthEntryPoint` / `JsonAccessDeniedHandler`
(they answer before the controller runs, S3); everything else by `GlobalExceptionHandler` (S1).
Every body is `{error:{code,message}}`; Spring's default error body never reaches a caller (D12).

| Scenario | Action | HTTP Status |
|----------|--------|-------------|
| Missing, invalid or expired token | Fixed message `Missing or invalid token.`; reason in a DEBUG log | 401 `UNAUTHENTICATED` |
| Caller not in `users`, or INACTIVE / LOCKED | Reject | 403 `FORBIDDEN` |
| Broken JSON, wrong field type, failed bean validation | Reject, nothing written | 400 `VALIDATION_ERROR` |
| Business rule broken (e.g. empty permission list D4, `/me/menus` row missing D6, role rename D9, own status/roles S10) | Reject before any write, message explains | 400 `VALIDATION_ERROR` |
| Unknown user / role / menu id | Reject | 404 `USER_NOT_FOUND` / `ROLE_NOT_FOUND` / `MENU_NOT_FOUND` |
| Unknown URL · wrong method · body not JSON | Reject | 404 `NOT_FOUND` · 405 `METHOD_NOT_ALLOWED` · 415 `UNSUPPORTED_MEDIA_TYPE` |
| Any other client error Spring raises with its own 4xx (e.g. 406) | Reject; no ERROR log | that 4xx, `VALIDATION_ERROR` |
| Unique conflict | Reject | 409 `DUPLICATE_ROLE_NAME` / `DUPLICATE_MENU_KEY` / `EMAIL_IN_USE` / `USERNAME_IN_USE` |
| Delete a role still held by any user | Reject | 409 `ROLE_IN_USE` |
| Change or delete a system role | Reject | 409 `SYSTEM_ROLE_IMMUTABLE` |
| Gateway sync fails after the DB commit (anything but 200 + `success:true`) | DB change stays; WARN log with the gateway's reply; caller retries with `POST /roles/{id}/sync` (after a delete: next sync of any role) | 502 `KEYCLOAK_SYNC_FAILED` |
| First Keycloak call fails (update profile, status, roles, reset password) | DB rolled back, nothing changed, no audit | 502 `KEYCLOAK_SYNC_FAILED` |
| Create user: Keycloak create fails | Nothing created (Keycloak 409 → 409 `EMAIL_IN_USE`) | 502 `KEYCLOAK_SYNC_FAILED` |
| Create user: a step after Keycloak create fails | Delete the new Keycloak account once; ERROR log + `USER_CREATE_FAILED` audit row in its own transaction | 502 `KEYCLOAK_SYNC_FAILED` |
| Profile / status / roles change half-done | No undo; ERROR log + `*_FAILED` audit row in its own transaction; an operator fixes it | 502 `KEYCLOAK_SYNC_FAILED` |
| Anything else (DB down, bug) | Roll back; ERROR log with stack trace and an 8-character reference id; the same id in `message` | 500 `INTERNAL_ERROR` |

Nothing is retried automatically (B-32). Exact messages: [API Reference — §4](api-reference.md).

## 11. Security

| Concern | Mechanism |
|---------|-----------|
| **JWT signature, issuer, audience, expiry** | Checked by the gateway **and** by this service (B-10). `aud` is checked by `AudienceValidator` |
| **Which role may call which method + URI** | The gateway only, from `api_permissions`. No per-URL role check in this service |
| **Caller is a local ACTIVE user** | This service only (B-12). Blocks a deactivated user whose token hasn't expired yet |
| **Open paths** | `/admin/v1/health`, `/swagger-ui/**`, `/v3/api-docs/**` (they show the API shape only; calls still need a token) |
| **Audit identity** | Actor from the verified token's `sub` → `users`; IP from the first `X-Forwarded-For` entry, else the remote address (S4) |
| **Passwords** | Never pass through the service: create sets Keycloak's `UPDATE_PASSWORD` required action; reset sends Keycloak's email (B-36) |
| **Secrets** | Environment variables only; nothing in the repo (B-13) |
| **Config parity** | `JWT_ISSUER`, `JWT_AUDIENCE`, `JWT_ROLES_CLAIM_PATH` match the gateway's values for the same realm (PRD §9) |

### 11.1 Keycloak service account

Client `cce-admin-service` is confidential, with a service account holding only the
`realm-management` roles `view-users`, `query-users` and `manage-users` in realm `cce`. Its token
gets 403 on any other realm (checked in S0).

### 11.2 Realm roles vs client roles (D13, OPEN)

The PRD and this design use Keycloak **realm** roles (`realm_access.roles`). The deployed gateway
reads **client** roles of `gateway-service`. Iteration 1 uses realm roles; `JWT_ROLES_CLAIM_PATH`
stays configurable. This must be answered (Q4) before the dev deploy in iteration 2.

## 12. Deployment

### 12.1 Iteration 1 — local (v1)

| Aspect | Value |
|--------|-------|
| **Run** | `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` (S1) |
| **Port** | 8085 |
| **Health** | `GET /admin/v1/health` → `{status, db}` |
| **API docs** | `/swagger-ui.html`, `/v3/api-docs` |
| **Key env vars** | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `KEYCLOAK_BASE_URL`, `KEYCLOAK_CLIENT_SECRET`, `JWT_ISSUER`, `JWT_AUDIENCE`, `GATEWAY_BASE_URL`, `BOOTSTRAP_ADMIN_USERNAME`, `SPRING_PROFILES_ACTIVE` |

#### Local stack

| Component | How | Port(s) | Purpose |
|-----------|-----|---------|---------|
| `cce-admin-postgres` | `docker-compose.yml`, `postgres:16` | 5434 | Database `admin_service` |
| `cce-admin-keycloak` | `docker-compose.yml`, `quay.io/keycloak/keycloak:26.5.4` | 8180 | Realm `cce` from `local/keycloak/cce-realm-local.json` |
| cce-admin-service | Gradle, on the host | 8085 | This service |
| gateway-service | Optional; run unchanged with env vars (S13) | 8095 | Real sync and real per-URL checks; otherwise the service is called directly |

Details: [Developer Setup](developer-setup.md).

### 12.2 Iteration 2 — dev (planned, not in v1)

From ARCHITECTURE.md §9; no sub-task builds this yet.

| Aspect | Value |
|--------|-------|
| **Image** | Docker image of the service, port 8085 (PRD: multi-stage) |
| **k8s** | Deployment + ClusterIP Service in `deploy-scripts`; not exposed directly, reached only through the gateway |
| **Gateway route** | `/admin/**` → admin-service (already in the gateway's `application.yml` as `ADMIN_SERVICE_URI`) plus an ingress path so `/admin` reaches the gateway |
| **Keycloak** | Per environment: service-account client for the service; `SUPER_ADMIN` realm role and the first admin user created by hand once (D5) |
| **Config** | DB, Keycloak, `JWT_*`, `GATEWAY_BASE_URL`, `BOOTSTRAP_ADMIN_USERNAME` from env vars / secrets |
| **Before deploying** | Answer D13 (realm vs client roles). Note from D2: on servers `api_permissions` lives in database `ccedb`, while the PRD and this POC use `admin_service` |

Not in this exercise: prod deployment, Jenkins pipeline.

## 13. Observability

> **See also:** [Data Dictionary](data-dictionary.md) for audit actions.

| Signal | What it gives |
|--------|---------------|
| `GET /admin/v1/health` | `status` UP while the app runs; `db` UP / DOWN (best effort). DB down still answers 200 |
| Logs | ERROR with a reference id for every 500 (D12); WARN for every gateway sync failure (D3); ERROR for every half-done Keycloak/DB change (D8); bootstrap ERROR / INFO (D5) |
| `audit_log` | One row per change; `*_FAILED` rows for half-done changes, found with SQL (no read endpoint in v2) |

Metrics (Micrometer / Prometheus), alert rules, dashboards, JSON log format and MDC fields are
**not in v1**.

## 14. Non-Functional Requirements

From PRD §9.

| Requirement | Target |
|-------------|--------|
| **Consistency** | Keycloak calls can partly fail. Operations run in the order in [Flow Diagrams](flow-diagrams.md); partial state is returned as 502 and recorded for reconciliation, not retried |
| **Ordering** | A role's realm role exists in Keycloak (gateway sync) before it is assigned to any user |
| **Auditability** | Every change can be rebuilt from `audit_log`; treated as a correctness requirement |
| **Security** | Defense in depth (JWT checked again); least-privilege Keycloak service account; no secrets committed |
| **Network** | In every non-local environment, reached only through gateway-service; not internet-facing |
| **Config parity** | `JWT_ISSUER`, `JWT_AUDIENCE`, `JWT_ROLES_CLAIM_PATH` match gateway-service for the same realm |
| **Testability** | Service-layer tests with the Keycloak and gateway clients mocked; every mutating endpoint has a test asserting one `audit_log` row |
| **Throughput / latency / availability** | Not specified in the PRD or SPEC (low-traffic CRUD) |
