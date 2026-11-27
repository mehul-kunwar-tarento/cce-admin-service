# cce-admin-service — Flow Diagrams

All diagrams use Mermaid notation. Colours: green = success, orange = saved but needs a follow-up
(or nothing to do), red = error.

## 1. End-to-End Flow

```mermaid
flowchart LR
    subgraph Callers
        SW["Swagger UI / curl<br/>(iteration 1)"]
        UI["cce-admin-ui,<br/>cce-insights-ui<br/>(later)"]
    end

    GW["gateway-service<br/>JWT + api_permissions"]

    subgraph "cce-admin-service (Spring Boot :8085)"
        SEC["Security filters<br/>JWT, ACTIVE user"]
        CTRL["Controllers"]
        SVC["Services<br/>checks, diffs, transactions"]
        AUD["AuditWriter"]
        KC_C["KeycloakClient"]
        GS_C["GatewaySyncClient"]
    end

    DB[("PostgreSQL<br/>admin_service")]
    KC["Keycloak<br/>realm cce"]

    SW -->|"Bearer token<br/>/admin/v1/**"| GW
    UI -->|"Bearer token<br/>/admin/v1/**"| GW
    GW -->|"proxy /admin/**"| SEC
    SEC --> CTRL --> SVC
    SVC -->|"JPA"| DB
    SVC --> AUD -->|"audit_log"| DB
    SVC --> KC_C -->|"Keycloak Admin API<br/>users, role mappings"| KC
    SVC --> GS_C -->|"POST /api/permissions/<br/>sync-to-keycloak"| GW
    GW -.->|"R2DBC read<br/>api_permissions"| DB
    GW -.->|"create / delete<br/>realm roles"| KC
    SEC -.->|"JWKS"| KC
```

The gateway and the service share one table, `api_permissions`: the service writes its roles'
rows, the gateway reads them on startup and on every sync.

## 2. Request Processing Sequence

One authenticated request, e.g. `PUT /admin/v1/roles/{id}` (S3, S4, D7).

```mermaid
sequenceDiagram
    participant C as Caller
    participant JWT as Spring Boot<br/>JWT decoder
    participant RC as RolesClaimReader
    participant AU as ActiveUserFilter
    participant Ctrl as Controller
    participant Svc as Service
    participant AW as AuditWriter
    participant DB as PostgreSQL

    C->>JWT: request + Bearer token
    activate JWT
    alt token missing / bad signature / wrong iss or aud / expired
        JWT-->>C: 401 UNAUTHENTICATED "Missing or invalid token."
        Note over JWT: reason only in a DEBUG log
    end
    JWT->>RC: valid token
    deactivate JWT
    RC->>RC: roles from JWT_ROLES_CLAIM_PATH<br/>(missing claim → empty list)
    RC->>AU: authenticated
    AU->>DB: users WHERE keycloak_id = sub
    alt no row, or status INACTIVE / LOCKED
        AU-->>C: 403 FORBIDDEN "not set up" / "not active"
    end
    opt auth_time newer than last_login_at (D7)
        AU->>DB: UPDATE users.last_login_at
        Note over AU: update fails → WARN, request continues.<br/>No audit row.
    end
    AU->>Ctrl: CurrentUser (id, username, roles)
    activate Ctrl
    Ctrl->>Ctrl: bind + validate body (400 VALIDATION_ERROR)
    Ctrl->>Svc: call
    activate Svc
    Svc->>DB: read-only checks (404 / 409 / 400)
    Svc->>DB: BEGIN, write rows
    Svc->>AW: record(action, entity, before, after)
    AW->>DB: INSERT audit_log (same transaction)
    Svc->>DB: COMMIT
    Svc-->>Ctrl: result
    deactivate Svc
    Ctrl-->>C: 200 {data}
    deactivate Ctrl
```

## 3. Create User with Undo

`POST /admin/v1/users` (S8, D8). Keycloak never sees the request until the DB checks pass.

```mermaid
flowchart TD
    A["POST /users<br/>username, email trimmed + lowercased"] --> B{"Body valid?<br/>username, email, firstName,<br/>lastName, roleIds ≥ 1"}
    B -->|No| E400["400 VALIDATION_ERROR<br/>Keycloak not called"]
    B -->|Yes| C{"Roles exist?<br/>email / username free in users?"}
    C -->|"unknown roleId"| E400
    C -->|"email taken"| E409E["409 EMAIL_IN_USE"]
    C -->|"username taken"| E409U["409 USERNAME_IN_USE"]
    C -->|Yes| K1["Keycloak: create account<br/>enabled, UPDATE_PASSWORD,<br/>no credentials"]

    K1 -->|"Keycloak 409"| E409K["409 EMAIL_IN_USE<br/>'Keycloak already has an account…'<br/>nothing created"]
    K1 -->|"Keycloak 400"| E400K["400 VALIDATION_ERROR<br/>'Keycloak refused the user: …'<br/>nothing created"]
    K1 -->|"down / 500"| E502A["502 KEYCLOAK_SYNC_FAILED<br/>'User was not created…'<br/>nothing created, no audit, WARN"]
    K1 -->|OK| K2["Keycloak: assign realm roles<br/>(picked from the user's available roles)"]

    K2 -->|"fails, e.g. no realm role NURSE"| U["Undo once:<br/>Keycloak delete user"]
    K2 -->|OK| D["DB transaction:<br/>users + user_roles + audit USER_CREATED"]
    D -->|"fails (rolled back)"| U
    D -->|OK| OK["201 Created<br/>{id, username, email, status, roles}"]

    U -->|"deleted (404 counts)"| R1["recordFailure USER_CREATE_FAILED<br/>entity_id = username<br/>cleanup: keycloak user deleted<br/>+ ERROR log"]
    U -->|"delete failed"| R2["recordFailure USER_CREATE_FAILED<br/>entity_id = username<br/>cleanup: FAILED — delete keycloak user by hand<br/>+ ERROR log"]
    R1 --> E502B["502 'assigning its realm roles failed: reason'<br/>or 'saving the user failed'<br/>'it has been removed again…'"]
    R2 --> E502C["502 'could not be removed.<br/>An operator must delete it…'"]

    style OK fill:#e8f5e9
    style E502B fill:#fff3e0
    style E400 fill:#ffebee
    style E409E fill:#ffebee
    style E409U fill:#ffebee
    style E409K fill:#ffebee
    style E400K fill:#ffebee
    style E502A fill:#ffebee
    style E502C fill:#ffebee
```

Each Keycloak call is made at most once; nothing is retried (B-32). `recordFailure` always logs an ERROR
line, then writes the audit row in its own transaction, so the row survives the rollback; if even
that fails (insert or commit), it logs a second ERROR line and never throws, so the first line is
the record (S4).

## 4. Gateway Sync Handling

`GatewaySyncClient.sync(roleId)` (S6a, D3). Called after the DB commit by `POST /roles`,
`DELETE /roles/{id}`, `PUT /roles/{id}/permissions` and `POST /roles/{id}/sync`.

```mermaid
sequenceDiagram
    participant Svc as Service
    participant GS as GatewaySyncClient
    participant GW as gateway-service
    participant KC as Keycloak
    participant Log as Logger

    Svc->>GS: sync(roleId)
    GS->>GW: POST /api/permissions/sync-to-keycloak<br/>(no auth header, 10s timeout)

    alt 200 + success:true
        GW->>GW: reload all api_permissions rows
        GW->>KC: create/update realm role per permission_name,<br/>delete realm roles with no rows
        GW-->>GS: 200 {success:true}
        GS-->>Svc: OK
    else 206 + failedRoles
        GW->>GW: rules already reloaded
        GW-->>GS: 206 {success:false, failedRoles:[A,B]}
        GS->>Log: WARN role id, URL, status, body
        GS-->>Svc: GatewaySyncException("Gateway reloaded its rules, but Keycloak role sync failed for: A, B.")
    else any other reply (500, 404, 200 not success / not JSON)
        GW-->>GS: 500 {message:"X"}
        GS->>Log: WARN role id, URL, status, body
        GS-->>Svc: GatewaySyncException("Gateway sync error: X.")<br/>(no message → "Gateway sync error: HTTP <status>.")
    else connection refused
        GS->>Log: WARN role id, URL, status=none, body=none, error=could not connect
        GS-->>Svc: GatewaySyncException("Gateway not reachable at {url}.")
    else no reply in 10s
        GS->>Log: WARN role id, URL, status=none, body=none, error=no answer within 10s
        GS-->>Svc: GatewaySyncException("Gateway did not answer within 10s.")
    end

    Note over Svc: Exception → 502 KEYCLOAK_SYNC_FAILED with the caller's opening<br/>("Role created." / "Permissions saved." / "Sync failed.") + cause.<br/>The DB change stays saved. No retry.
```

Sync is **global**: the gateway reloads every row, so a 206 may come from another role's rows.

## 5. Error Handling Flow

D12: every error, planned or not, leaves as `{error:{code,message}}`.

```mermaid
flowchart TD
    A[Request] --> S{"Spring Security<br/>passes?"}
    S -->|"no / bad token"| H401["JsonAuthEntryPoint<br/>401 UNAUTHENTICATED"]
    S -->|"not a local ACTIVE user"| H403["ActiveUserFilter<br/>403 FORBIDDEN"]
    S -->|Yes| R{"Route found?"}

    R -->|"unknown URL"| N404["404 NOT_FOUND"]
    R -->|"wrong method"| N405["405 METHOD_NOT_ALLOWED"]
    R -->|"not JSON"| N415["415 UNSUPPORTED_MEDIA_TYPE"]
    R -->|Yes| B{"Body parses<br/>and validates?"}

    B -->|No| V400["400 VALIDATION_ERROR<br/>(names the field)"]
    B -->|Yes| SV{"Service result"}

    SV -->|OK| OK["200 / 201 / 204"]
    SV -->|"ApiException"| AE["Its own status + code + message<br/>404 *_NOT_FOUND, 409 …, 400 …<br/>no reference id"]
    SV -->|"GatewaySyncException /<br/>Keycloak failure"| K502["502 KEYCLOAK_SYNC_FAILED<br/>message says what was saved"]
    SV -->|"anything else<br/>(DB down, bug)"| I500["Rollback<br/>new 8-char reference id<br/>ERROR [ref=…] + stack trace"]
    I500 --> E500["500 INTERNAL_ERROR<br/>'… report reference 7f3a9c2e.'"]

    N404 --> GEH["GlobalExceptionHandler<br/>{error:{code,message}}"]
    N405 --> GEH
    N415 --> GEH
    V400 --> GEH
    AE --> GEH
    K502 --> GEH
    E500 --> GEH
    H401 --> OUT[Return to caller]
    H403 --> OUT
    GEH --> OUT
    OK --> OUT

    style OK fill:#e8f5e9
    style K502 fill:#fff3e0
    style H401 fill:#ffebee
    style H403 fill:#ffebee
    style V400 fill:#ffebee
    style AE fill:#ffebee
    style E500 fill:#ffebee
```

No exception text, SQL, stack trace, host or class name ever goes into `message`.

## 6. Component Dependency Graph

```mermaid
flowchart TD
    subgraph controller
        MeC[MeController]
        UC[UserController]
        RC[RoleController]
        RMC[RoleMenuController]
        RPC[RolePermissionController]
        MC[MenuController]
        HC[HealthController]
    end

    subgraph service
        MeS[MeService]
        UCS[UserCreateService]
        UQS[UserQueryService]
        UUS[UserUpdateService]
        USS[UserStatusService]
        URS[UserRolesService]
        PRS[PasswordResetService]
        RS[RoleService]
        RMS[RoleMenuService]
        RPS[RolePermissionService]
        MS[MenuService]
    end

    subgraph client
        KCC["KeycloakClient<br/>(KeycloakAdminClient)"]
        GSC["GatewaySyncClient<br/>(HttpGatewaySyncClient)"]
    end

    REPO["repository/*<br/>Spring Data JPA"]
    AW[AuditWriter]

    subgraph bootstrap
        BAR[BootstrapAdminRunner]
        BAS[BootstrapAdminService]
    end

    MeC --> MeS
    UC --> UCS & UQS & UUS & USS & URS & PRS
    RC --> RS
    RMC --> RMS
    RPC --> RPS
    MC --> MS

    UCS & UUS & USS & URS & PRS --> KCC
    RS & RPS --> GSC

    MeS & UCS & UQS & UUS & USS & URS & RS & RMS & RPS & MS --> REPO
    UCS & UUS & USS & URS & PRS & RS & RMS & RPS & MS --> AW
    AW --> REPO

    BAR --> KCC
    BAR --> BAS
    BAS --> REPO
    BAS --> AW

    style KCC fill:#c8e6c9
    style GSC fill:#c8e6c9
    style AW fill:#fff9c4
```

`KeycloakClient` and `GatewaySyncClient` are interfaces, so service tests swap in fakes and can
test "Keycloak worked, DB failed" without a real Keycloak (ARCHITECTURE.md §7).

## 7. Deployment Topology

```mermaid
flowchart LR
    subgraph "Developer machine (iteration 1)"
        CURL["curl / Swagger UI"]
        GWL["gateway-service<br/>:8095 (optional, S13)"]
        SVC["cce-admin-service<br/>./gradlew bootRun<br/>:8085"]
        subgraph "docker compose"
            PG[("cce-admin-postgres<br/>postgres:16<br/>127.0.0.1:5434")]
            KC["cce-admin-keycloak<br/>keycloak:26.5.4<br/>127.0.0.1:8180"]
        end
    end

    subgraph "dev (iteration 2, planned)"
        DEVGW[gateway-service]
        DEVSVC["cce-admin-service<br/>ClusterIP :8085"]
    end

    CURL -->|"direct"| SVC
    CURL -->|"through gateway"| GWL --> SVC
    SVC --> PG
    SVC --> KC
    SVC -->|"sync"| GWL
    GWL --> PG
    GWL --> KC
    DEVGW -.->|"/admin/**"| DEVSVC

    style DEVGW stroke-dasharray: 5 5
    style DEVSVC stroke-dasharray: 5 5
```

Without the local gateway, every sync returns 502 "Gateway not reachable…" while the DB change is
kept; that is expected locally.

## 8. Create a Role

`POST /admin/v1/roles` (S6, D4, D11).

```mermaid
sequenceDiagram
    participant C as Caller
    participant RS as RoleService
    participant DB as PostgreSQL
    participant GS as GatewaySyncClient
    participant GW as gateway-service
    participant KC as Keycloak

    C->>RS: POST /roles {name, displayName, description}
    RS->>RS: name matches ^[A-Z][A-Z0-9_]*$? displayName present?
    alt invalid
        RS-->>C: 400 VALIDATION_ERROR
    end
    RS->>DB: roles WHERE name = ?
    alt exists
        RS-->>C: 409 DUPLICATE_ROLE_NAME
    end
    RS->>DB: api_permissions WHERE permission_name = ?
    alt exists (e.g. COLLECTOR_EVENTS_WRITE)
        RS-->>C: 409 DUPLICATE_ROLE_NAME "…existing permission set in the gateway…"
    end

    RS->>DB: BEGIN
    RS->>DB: INSERT roles (is_system = false)
    RS->>DB: INSERT api_permissions (NAME, GET, /admin/v1/me/menus)
    RS->>DB: INSERT audit_log ROLE_CREATED
    RS->>DB: COMMIT

    RS->>GS: sync(roleId)
    GS->>GW: POST /api/permissions/sync-to-keycloak
    GW->>KC: create realm role NAME
    alt sync OK
        GS-->>RS: OK
        RS-->>C: 201 Created
    else sync failed (§4)
        GS-->>RS: GatewaySyncException(cause)
        RS-->>C: 502 "Role created. {cause} Retry with POST /admin/v1/roles/{id}/sync."
        Note over C,DB: role, default row and audit stay saved
    end
```

Because of the default row, the realm role exists in Keycloak from the moment the role is created,
so the role can be given to a user straight away (D4; PRD §9 "Ordering").

## 9. Save a Role's Permissions

`PUT /admin/v1/roles/{id}/permissions` (S11). Nothing is written before step 6.

```mermaid
flowchart TD
    A["PUT /roles/{id}/permissions"] --> S1{"1. Role exists?"}
    S1 -->|No| E404["404 ROLE_NOT_FOUND"]
    S1 -->|Yes| S2{"2. System role?"}
    S2 -->|Yes| E409["409 SYSTEM_ROLE_IMMUTABLE"]
    S2 -->|No| S3{"3. Every row valid?<br/>method in GET/POST/PUT/PATCH/DELETE,<br/>uriPattern starts with /, ≤ 512"}
    S3 -->|No| E400A["400 VALIDATION_ERROR"]
    S3 -->|Yes| S4{"4. List empty?"}
    S4 -->|Yes| E400B["400 VALIDATION_ERROR (D4)"]
    S4 -->|No| S5{"5. A GET row matches<br/>/admin/v1/me/menus?<br/>(AntPathMatcher)"}
    S5 -->|No| E400C["400 VALIDATION_ERROR<br/>'Every role must keep GET /admin/v1/me/menus…'"]
    S5 -->|Yes| SAME{"Same set as now?<br/>(duplicates counted once)"}
    SAME -->|Yes| NOOP["200 — no write, no audit, no sync"]
    SAME -->|No| W["6. One transaction:<br/>DELETE rows WHERE permission_name = role.name<br/>INSERT new rows with permission_name = role.name<br/>audit ROLE_PERMISSIONS_UPDATED"]
    W --> SY{"7. Gateway sync OK?"}
    SY -->|Yes| OK["200"]
    SY -->|No| E502["502 KEYCLOAK_SYNC_FAILED<br/>'Permissions saved. {cause} Retry with POST …/sync.'"]

    style OK fill:#e8f5e9
    style NOOP fill:#fff3e0
    style E502 fill:#fff3e0
    style E404 fill:#ffebee
    style E409 fill:#ffebee
    style E400A fill:#ffebee
    style E400B fill:#ffebee
    style E400C fill:#ffebee
```

`permission_name` always comes from the role in the URL, never from the body, and rows of other
permission sets are never touched (D11).

## 10. Delete a Role

`DELETE /admin/v1/roles/{id}` (D10).

```mermaid
flowchart TD
    A["DELETE /roles/{id}"] --> C1{"Role exists?"}
    C1 -->|No| E404["404 ROLE_NOT_FOUND"]
    C1 -->|Yes| C2{"System role?"}
    C2 -->|Yes| E409S["409 SYSTEM_ROLE_IMMUTABLE"]
    C2 -->|No| C3{"Any user_roles rows?<br/>(ACTIVE, INACTIVE or LOCKED users)"}
    C3 -->|Yes| E409U["409 ROLE_IN_USE"]
    C3 -->|No| T["One transaction:<br/>4. DELETE role_menus<br/>5. DELETE api_permissions WHERE permission_name = name<br/>6. DELETE roles<br/>7. audit ROLE_DELETED (before = role + menus + permissions)"]
    T --> SY{"8. Gateway sync OK?<br/>(gateway finds no rows → deletes realm role)"}
    SY -->|Yes| OK["204 No Content"]
    SY -->|No| E502["502 KEYCLOAK_SYNC_FAILED<br/>'Role deleted. Keycloak still has realm role NAME<br/>until the next successful sync of any role.'"]

    style OK fill:#e8f5e9
    style E502 fill:#fff3e0
    style E404 fill:#ffebee
    style E409S fill:#ffebee
    style E409U fill:#ffebee
```

After a failed sync, `POST /roles/{id}/sync` can't be used (the role is gone, 404); the next
successful sync of any role removes the leftover realm role (D10 option a).

## 11. Change Status / Roles

`PATCH /users/{id}/status` and `PUT /users/{id}/roles` (S10, D8). Keycloak is called inside the
open DB transaction.

```mermaid
sequenceDiagram
    participant C as Caller
    participant Svc as UserStatusService /<br/>UserRolesService
    participant DB as PostgreSQL
    participant KC as Keycloak
    participant AW as AuditWriter

    C->>Svc: new status / full role set
    Svc->>DB: checks: user exists (404), valid value (400),<br/>not the caller's own (400), roles exist (400)
    alt same status / same role set as now
        Svc-->>C: 200 — no Keycloak call, no audit
    end
    Svc->>DB: BEGIN, update users.status or user_roles
    Svc->>DB: INSERT audit_log (USER_DEACTIVATED, USER_ROLES_UPDATED, …)

    alt status
        Svc->>KC: setEnabled(false | true)
    else roles: [A,B] → [B,C]
        Svc->>KC: assignRealmRoles([C])
        Svc->>KC: removeRealmRoles([A])
    end

    alt all Keycloak calls OK
        Svc->>DB: COMMIT
        Svc-->>C: 200
    else first Keycloak call fails
        Svc->>DB: ROLLBACK
        Svc-->>C: 502 "Status or Roles not changed. Keycloak error: … Nothing was changed."
    else later call fails, or COMMIT fails after Keycloak
        Svc->>DB: ROLLBACK
        Svc->>AW: recordFailure(USER_STATUS_UPDATE_FAILED | USER_ROLES_UPDATE_FAILED)<br/>own transaction + ERROR log
        Svc-->>C: 502 "… only partly applied … an operator must fix it."
    end
```

No undo here: reverting could overwrite something that changed in the meantime, so a person fixes
it (D8). Deactivation keeps `user_roles`, so re-activation restores the same access (B-34).
`PUT /users/{id}` (S9) follows the same order with one Keycloak call, `updateUser`, made only when
the email or a name changed; the same values return 200 before the transaction writes anything. A
taken email is found at the row write (flush), before Keycloak. When our side fails after Keycloak
took the change, `USER_UPDATE_FAILED` is recorded with `cleanup` = `none — make Keycloak match the
user record by hand`.

## 12. Bootstrap Admin at Startup

`BootstrapAdminRunner`, after Flyway, on every start (D5, S2a). It never stops the service: any
other failure (e.g. the database) is an ERROR with its stack trace, and nothing is saved. The
username is lowercased first, as Keycloak stores it.

```mermaid
flowchart TD
    A["Service starts<br/>Flyway V1, V2 done"] --> B{"BOOTSTRAP_ADMIN_USERNAME set?"}
    B -->|No| SKIP1["Do nothing<br/>(no Keycloak call)"]
    B -->|Yes| C{"users row with<br/>that username exists?"}
    C -->|Yes| SKIP2["Do nothing<br/>(linked on an earlier start)"]
    C -->|No| D["Keycloak: find user<br/>exact username match (10s timeout)"]
    D -->|"unreachable / not found"| E1["ERROR 'Bootstrap admin {username} not linked: …<br/>create {username} in Keycloak first, then restart'<br/>no rows, keep running"]
    D -->|found| F{"Has realm role<br/>SUPER_ADMIN?<br/>(effective roles)"}
    F -->|No| E2["ERROR '… has no realm role SUPER_ADMIN'<br/>no rows, keep running"]
    F -->|Yes| G{"Has an email?"}
    G -->|No| E3["ERROR '{username} has no email in Keycloak'<br/>no rows, keep running"]
    G -->|Yes| T["One transaction:<br/>users (keycloak_id, username, email, names, ACTIVE)<br/>user_roles → SUPER_ADMIN<br/>audit BOOTSTRAP_ADMIN_LINKED, actor SYSTEM"]
    T --> OK["INFO 'bootstrap admin {username} linked'"]

    style OK fill:#e8f5e9
    style SKIP1 fill:#fff3e0
    style SKIP2 fill:#fff3e0
    style E1 fill:#ffebee
    style E2 fill:#ffebee
    style E3 fill:#ffebee
```

The fix for any ERROR is to create or correct the user in Keycloak and restart the service. Until
the admin is linked, their valid token gets 403 `FORBIDDEN` (B-12).

## 13. Building `/me/menus`

`GET /admin/v1/me/menus` (S12, B-21). Read-only; no audit, no Keycloak call.

```mermaid
flowchart TD
    A["GET /me/menus"] --> R["Caller's roles from user_roles<br/>(DB, not the token)"]
    R --> U["Union of role_menus across all roles<br/>each menu once"]
    U --> I["Drop inactive menus"]
    I --> P["Drop a DETAIL whose NAV parent<br/>is not in the result or is inactive"]
    P --> N["Nest each DETAIL under its NAV<br/>in children"]
    N --> S["Sort by sortOrder"]
    S --> E{"Anything left?"}
    E -->|Yes| OK["200 {data: [menu tree]}"]
    E -->|No| EMPTY["200 {data: []}<br/>(e.g. SUPER_ADMIN, or a new role<br/>with no menus yet) — not an error"]

    style OK fill:#e8f5e9
    style EMPTY fill:#fff3e0
```

Roles come from the DB so a role change shows on the caller's next request with the same token,
whichever claim path the token uses (S12, D13).
