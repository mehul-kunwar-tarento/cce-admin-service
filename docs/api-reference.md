# cce-admin-service — API Reference

## 1. Overview

| Property | Value |
|----------|-------|
| **Base path** | `/admin/v1` |
| **Base URL, direct (iteration 1)** | `http://localhost:8085/admin/v1` |
| **Base URL, through the local gateway (S13)** | `http://localhost:8095/admin/v1` |
| **Protocol** | HTTP |
| **Content Type** | `application/json` |
| **Response Format** | `application/json` |
| **Authentication** | `Authorization: Bearer <JWT>` on every endpoint except health and the API docs |
| **API docs** | Swagger UI `/swagger-ui.html`, OpenAPI spec `/v3/api-docs` (no token needed) |

> Two layers check every call. The **gateway** validates the JWT and checks the caller's role
> against `api_permissions` (which role may call which method + URL). **This service** validates
> the JWT again and requires the caller to be a local user with status `ACTIVE`; it does no
> per-URL role check of its own (ARCHITECTURE.md §8). Locally the service can be called directly
> on :8085; the examples below do that.

## 2. Conventions

### 2.1 Response envelope

Every response except health uses one envelope (B-03):

```json
{ "data": { } }
```

```json
{ "data": [ ], "pagination": { "page": 1, "pageSize": 20, "total": 57 } }
```

```json
{ "error": { "code": "ROLE_NOT_FOUND", "message": "…" } }
```

- `pagination` is present only on paginated lists; a non-list result has no `pagination` key.
- `GET /admin/v1/health` is the one endpoint **not** wrapped (§3.1).
- `204 No Content` responses have no body.

### 2.2 Pagination

Used by `GET /users` and `GET /roles` (B-04).

| Query param | Default | Rule |
|-------------|---------|------|
| `page` | `1` | Starts at 1. `page=0` → 400 `VALIDATION_ERROR` |
| `pageSize` | `20` | Max 100. `pageSize=101` → 400 `VALIDATION_ERROR` (not silently capped) |

### 2.3 Full-set replace

`PUT /users/{id}/roles`, `PUT /roles/{id}/menus` and `PUT /roles/{id}/permissions` take the
**full new set**. The service works out what was added and removed. Sending the set the resource
already has → `200`, nothing written, no audit row, no Keycloak or gateway call.

### 2.4 Identifiers

| Resource | `id` type |
|----------|-----------|
| User, role, menu | UUID |
| Permission row (`api_permissions`) | Number (`Long`) — the gateway's table shape (D1). Row ids change on every save (S11 rule e) |

### 2.5 Check order

Every mutating endpoint runs its read-only checks first, in a fixed order; the first failure
stops the request and **nothing is written** until all checks pass. The order per endpoint is
listed in §3.

### 2.6 Audit

Every successful change writes **exactly one** `audit_log` row in the same transaction (B-50). A
request that changes nothing (same set, same status) writes none. Action names are listed per
endpoint; their `before` / `after` content is in the [Data Dictionary](data-dictionary.md).

---

## 3. Endpoints

All paths below are relative to `/admin/v1`, except the API docs.

### 3.0 Endpoint summary

| Method | Path | Sub-task | Success | Audit action | Outside call |
|--------|------|----------|---------|--------------|--------------|
| GET | `/health` | S1 | 200 | — | — |
| GET | `/me` | S12 | 200 | — | — |
| GET | `/me/menus` | S12 | 200 | — | — |
| GET | `/users` | S9 | 200 | — | — |
| POST | `/users` | S8 | 201 | `USER_CREATED` | Keycloak |
| GET | `/users/{id}` | S9 | 200 | — | — |
| PUT | `/users/{id}` | S9 | 200 | `USER_UPDATED` | Keycloak (not when only `facilityScope` changes) |
| PATCH | `/users/{id}/status` | S10 | 200 | `USER_ACTIVATED` / `USER_DEACTIVATED` / `USER_LOCKED` | Keycloak |
| PUT | `/users/{id}/roles` | S10 | 200 | `USER_ROLES_UPDATED` | Keycloak |
| POST | `/users/{id}/reset-password` | S10 | 204 | `USER_PASSWORD_RESET` | Keycloak |
| GET | `/roles` | S6 | 200 | — | — |
| POST | `/roles` | S6 | 201 | `ROLE_CREATED` | Gateway sync |
| GET | `/roles/{id}` | S6 | 200 | — | — |
| PUT | `/roles/{id}` | S6 | 200 | `ROLE_UPDATED` | — |
| DELETE | `/roles/{id}` | S6 | 204 | `ROLE_DELETED` | Gateway sync |
| GET | `/roles/{id}/menus` | S7 | 200 | — | — |
| PUT | `/roles/{id}/menus` | S7 | 200 | `ROLE_MENUS_UPDATED` | — |
| GET | `/roles/{id}/permissions` | S11 | 200 | — | — |
| PUT | `/roles/{id}/permissions` | S11 | 200 | `ROLE_PERMISSIONS_UPDATED` | Gateway sync |
| POST | `/roles/{id}/sync` | S11 | 200 | — | Gateway sync |
| GET | `/menus` | S5 | 200 | — | — |
| POST | `/menus` | S5 | 201 | `MENU_CREATED` | — |
| PUT | `/menus/{id}` | S5 | 200 | `MENU_UPDATED` | — |
| DELETE | `/menus/{id}` | S5 | 204 | `MENU_DELETED` | — |

Half-done Keycloak/DB changes also write a `*_FAILED` audit row in its own transaction — see §5.4.

Every protected endpoint can also return 401 `UNAUTHENTICATED` and 403 `FORBIDDEN` (§5), and any
endpoint can return 500 `INTERNAL_ERROR`. These are not repeated in each table below.

---

### 3.1 Health and API docs

#### GET /admin/v1/health

No token. Not wrapped in the envelope (B-05 shows the bare body; `db` is added for "best-effort
DB status").

| Situation | Status | Body |
|-----------|--------|------|
| App up, DB reachable | 200 | `{"status":"UP","db":"UP"}` |
| App up, DB down | 200 | `{"status":"UP","db":"DOWN"}` — answered within 3s |

DB down is still 200: the app is up, and a 503 would make an orchestrator restart a healthy app
during a DB blip (S1).

#### GET /swagger-ui.html · GET /v3/api-docs

No token. Swagger UI and the OpenAPI 3 spec; every endpoint in this document is listed there
(checked in S13).

---

### 3.2 Current user

Both endpoints read the caller's roles from `user_roles` in the DB, **not** from the token, so a
role change shows on the caller's next request with the same token (S12). Neither writes an audit
row or calls Keycloak.

#### GET /me

The caller's profile (B-20).

**Response 200:**

```json
{
  "data": {
    "id": "0b6f9a52-3c1e-4d7a-9f0e-2a4c8e1d5b73",
    "username": "superadmin",
    "email": "superadmin@cce.local",
    "firstName": "Super",
    "lastName": "Admin",
    "roles": ["SUPER_ADMIN"],
    "facilityScope": null
  }
}
```

`roles` = role names, sorted. `email`, `firstName`, `lastName` are added to the PRD's shape so a
console header can show a name (S12 rule a).

#### GET /me/menus

The menu tree for the union of the caller's roles (B-21). A console builds its sidebar from it.

| Rule | Source |
|------|--------|
| Union of the menus of all the caller's roles; each menu once | B-21 |
| `DETAIL` items nested under their `NAV` parent in `children` | B-21 |
| Sorted by `sortOrder` | B-21 |
| Inactive menus not returned; an inactive NAV hides its DETAIL children too | B-21, S12 rule c |
| A DETAIL is returned only if its parent NAV is also in the result | S12 rule b |
| Roles with no menus (e.g. SUPER_ADMIN) → `200` with `data: []`; not an error | D6 |

**Response 200:**

```json
{
  "data": [
    {
      "id": "5d1c…",
      "key": "dashboard",
      "label": "Dashboard",
      "path": "/",
      "icon": "ChartBarIcon",
      "type": "NAV",
      "children": []
    },
    {
      "id": "8a27…",
      "key": "compliance",
      "label": "Compliance",
      "path": "/compliance",
      "icon": "ClipboardDocumentCheckIcon",
      "type": "NAV",
      "children": [
        {
          "id": "c4e0…",
          "key": "protocol-detail",
          "label": "Protocol analytics",
          "path": "/compliance/protocols/:id",
          "icon": null,
          "type": "DETAIL",
          "children": []
        }
      ]
    }
  ]
}
```

Menu keys, labels and icon values come from the S2 seed (D14 working default; icon = Heroicon
name from `cce-insights-ui` `Sidebar.tsx`). Exact seed values: data dictionary §2.1.

---

### 3.3 Users

#### GET /users

List users with filters and paging (B-30, B-04).

| Query param | Rule |
|-------------|------|
| `page`, `pageSize` | §2.2 |
| `q` | Case-insensitive "contains" on username, firstName, lastName, email |
| `status` | `ACTIVE` / `INACTIVE` / `LOCKED`; any other value → 400 `VALIDATION_ERROR` |
| `roleId` | Users holding that role |
| `facilityScope` | Exact match |

Filters combine with AND. Sorted by username A→Z. INACTIVE and LOCKED users are included unless
`status` is given.

**Response 200:**

```json
{
  "data": [
    {
      "id": "7c3e…",
      "username": "alice",
      "email": "alice@example.org",
      "firstName": "Alice",
      "lastName": "Otieno",
      "status": "ACTIVE",
      "roles": [{ "id": "a91f…", "name": "FACILITY_ADMIN", "displayName": "Facility admin" }],
      "facilityScope": null,
      "lastLoginAt": "2026-11-20T09:14:03Z",
      "createdAt": "2026-11-19T15:02:47Z"
    }
  ],
  "pagination": { "page": 1, "pageSize": 20, "total": 1 }
}
```

`lastLoginAt` is taken from the token's `auth_time` on the user's requests (D7); `null` until the
user first reaches this service after a browser login.

#### POST /users

Create a user: Keycloak account, realm roles and local row in one call (B-31, S8).

**Request body:**

| Field | Type | Required | Rule |
|-------|------|----------|------|
| `username` | string | Yes | Unique; can never change afterwards |
| `email` | string | Yes | Valid email; unique case-insensitively |
| `firstName` | string | Yes | Not blank |
| `lastName` | string | Yes | Not blank |
| `facilityScope` | string | No | Stored only, not enforced (PRD) |
| `roleIds` | UUID[] | Yes | At least one; every id must exist |

`firstName` and `lastName` are required because Keycloak 26's default user profile needs them for
login; without them the user would be created but could not log in (S8 rule g).

There is **no password field**. A `password` in the body is ignored and never sent to Keycloak.
The Keycloak account is created with the required action `UPDATE_PASSWORD`; the admin sends the
link with reset password (S8 rule a, B-36). A new user is always `ACTIVE`.

**Flow:**

```
0. Checks, DB only: body valid? roles exist? email / username free?   no → 400 / 409, Keycloak not called
1. Keycloak: create account                                           fails → nothing created
2. Keycloak: assign realm roles                                      ─┐ fails → delete the Keycloak
3. DB, one transaction: users + user_roles + audit USER_CREATED      ─┘ account once, record, 502
4. 201 Created
```

**Responses:**

| Status | Code | When |
|--------|------|------|
| 201 | — | Created. Body: `{id, username, email, status:"ACTIVE", roles}` |
| 400 | `VALIDATION_ERROR` | Missing or blank username, email, firstName or lastName; bad email; `roleIds: []`; unknown `roleId` |
| 409 | `EMAIL_IN_USE` | Email already in `users` (any case) |
| 409 | `USERNAME_IN_USE` | Username already in `users` |
| 409 | `EMAIL_IN_USE` | Keycloak already has an account with this username or email (S8 rule c message, §5.3) |
| 502 | `KEYCLOAK_SYNC_FAILED` | Keycloak could not create the account — nothing changed (§5.4) |
| 502 | `KEYCLOAK_SYNC_FAILED` | A step after Keycloak create failed — undo attempted, `USER_CREATE_FAILED` recorded (§5.4) |

Each Keycloak call is made at most once per request; nothing is retried (B-32).

#### GET /users/{id}

One user, same fields as a `GET /users` item (B-33).

| Status | Code | When |
|--------|------|------|
| 200 | — | Found |
| 404 | `USER_NOT_FOUND` | Unknown id |

#### PUT /users/{id}

Edit profile fields (B-33, S9). The body is the **whole profile**, not a partial update (S9 rule g).

**Request body:**

| Field | Type | Required | Rule |
|-------|------|----------|------|
| `firstName` | string | Yes | Not blank; sent to Keycloak |
| `lastName` | string | Yes | Not blank; sent to Keycloak |
| `email` | string | Yes | Not blank; unique case-insensitively; sent to Keycloak |
| `facilityScope` | string | No | Our DB only; `null` clears it; changing only this calls no Keycloak |
| `username` | string | No | Same as now → ignored; different → 400 `VALIDATION_ERROR` |

**Order:** open DB transaction → update the row (flushed, so a duplicate email fails here) →
Keycloak `updateUser` → commit.

| Status | Code | When |
|--------|------|------|
| 200 | — | Updated; audit `USER_UPDATED` with before/after |
| 400 | `VALIDATION_ERROR` | `firstName`, `lastName` or `email` missing or blank; different `username`; invalid field — DB and Keycloak unchanged |
| 404 | `USER_NOT_FOUND` | Unknown id |
| 409 | `EMAIL_IN_USE` | Email used by another user; Keycloak not called |
| 502 | `KEYCLOAK_SYNC_FAILED` | Keycloak update failed → DB rolled back, nothing changed, no audit |
| 502 | `KEYCLOAK_SYNC_FAILED` | Commit failed after Keycloak succeeded → `USER_UPDATE_FAILED` recorded |

#### PATCH /users/{id}/status

Change status, mirrored to Keycloak (B-34, S10).

**Request body:** `{ "status": "ACTIVE" | "INACTIVE" | "LOCKED" }`

| Status change | Keycloak | `user_roles` | Audit |
|---------------|----------|--------------|-------|
| → `INACTIVE` | `enabled = false` | kept (re-activation restores access) | `USER_DEACTIVATED` |
| → `LOCKED` | `enabled = false` (same as INACTIVE; only the label differs) | kept | `USER_LOCKED` |
| → `ACTIVE` | `enabled = true` | unchanged | `USER_ACTIVATED` |

An INACTIVE or LOCKED user's still-valid token gets 403 `FORBIDDEN` from this service on the next
request (B-12). Users are never hard-deleted.

| Status | Code | When |
|--------|------|------|
| 200 | — | Changed. Body confirmed in S10 |
| 200 | — | Same status as now → no Keycloak call, no audit |
| 400 | `VALIDATION_ERROR` | Unknown status value; caller changing their own status |
| 404 | `USER_NOT_FOUND` | Unknown id |
| 502 | `KEYCLOAK_SYNC_FAILED` | Keycloak call failed → nothing changed, no audit |
| 502 | `KEYCLOAK_SYNC_FAILED` | Commit failed after Keycloak → `USER_STATUS_UPDATE_FAILED` recorded |

#### PUT /users/{id}/roles

Replace the user's role set (B-35, S10; full-set replace §2.3).

**Request body:** `{ "roleIds": ["…", "…"] }`

The service assigns in Keycloak only the roles added and removes only the roles taken away; the
rest are untouched. Example: `[A, B]` → `[B, C]` assigns C, removes A.

| Status | Code | When |
|--------|------|------|
| 200 | — | Changed; audit `USER_ROLES_UPDATED` with role names before/after. Body confirmed in S10 |
| 200 | — | Same set as now → no Keycloak call, no audit |
| 400 | `VALIDATION_ERROR` | `[]`, unknown `roleId`, or the caller's own roles |
| 404 | `USER_NOT_FOUND` | Unknown id |
| 502 | `KEYCLOAK_SYNC_FAILED` | First Keycloak call failed → nothing changed, no audit |
| 502 | `KEYCLOAK_SYNC_FAILED` | A later Keycloak call failed after an earlier one succeeded → `user_roles` unchanged, `USER_ROLES_UPDATE_FAILED` recorded |

#### POST /users/{id}/reset-password

Trigger Keycloak's password-reset email (execute-actions email with `UPDATE_PASSWORD`). No body.
No password ever passes through the service (B-36).

| Status | Code | When |
|--------|------|------|
| 204 | — | Email triggered; audit `USER_PASSWORD_RESET` |
| 400 | `VALIDATION_ERROR` | User not ACTIVE — `Activate the user first.` |
| 404 | `USER_NOT_FOUND` | Unknown id |
| 502 | `KEYCLOAK_SYNC_FAILED` | Keycloak call failed; no audit |

> Locally, S0's Keycloak has no mail server, so a real reset returns 502. Tests mock Keycloak;
> real email delivery is not tested (S10 rule h).

---

### 3.4 Roles

#### GET /roles

Paginated list (§2.2). Only roles in our `roles` table are listed; `api_permissions` rows of other
kinds (e.g. `COLLECTOR_EVENTS_WRITE`) are not (D11). Sort order and the `RoleResponse` fields are
confirmed in S6.

#### GET /roles/{id}

| Status | Code | When |
|--------|------|------|
| 200 | — | Found |
| 404 | `ROLE_NOT_FOUND` | Unknown id |

#### POST /roles

Create a role (B-40, S6).

**Request body:**

| Field | Type | Required | Rule |
|-------|------|----------|------|
| `name` | string | Yes | UPPER_SNAKE_CASE (`^[A-Z][A-Z0-9_]*$`), max 100. **Can never change** |
| `displayName` | string | Yes | |
| `description` | string | No | |

An `isSystem` field in the body is ignored; created roles are always `is_system = false`.

**Order:**

```
1. Validate the body                                           no  → 400 VALIDATION_ERROR
2. name already in roles?                                      yes → 409 DUPLICATE_ROLE_NAME
3. name already a permission_name in api_permissions?          yes → 409 DUPLICATE_ROLE_NAME (D11 message)
4. One transaction: role + default permission row
   (<NAME>, GET, /admin/v1/me/menus) + audit ROLE_CREATED                                   (D4, D6)
5. Gateway sync → realm role <NAME> created in Keycloak        fails → 502, role stays saved (D3)
6. 201 Created with the role
```

| Status | Code | When |
|--------|------|------|
| 201 | — | Created and synced |
| 400 | `VALIDATION_ERROR` | Bad name format, missing `displayName` |
| 409 | `DUPLICATE_ROLE_NAME` | Name taken by a role, or by an existing permission set in the gateway |
| 502 | `KEYCLOAK_SYNC_FAILED` | Saved, but sync failed — `Role created. <cause> Retry with POST /admin/v1/roles/{id}/sync.` |

#### PUT /roles/{id}

Edit `displayName` and `description` only (B-40, D9). No Keycloak call, no gateway sync.

| Body `name` | Result |
|-------------|--------|
| absent | allowed |
| same as the current name | allowed (ignored) |
| different from the current name | 400 `VALIDATION_ERROR` |
| other unknown fields | ignored |

**Check order:**

```
1. Role exists?        no  → 404 ROLE_NOT_FOUND
2. System role?        yes → 409 SYSTEM_ROLE_IMMUTABLE   (any change, even displayName)
3. Name differs?       yes → 400 VALIDATION_ERROR        (D9 message)
4. Save + audit ROLE_UPDATED (before/after)  → 200 with the updated role
```

System is checked before name, so a rename of SUPER_ADMIN gets 409, not a 400 implying the display
name could be edited.

#### DELETE /roles/{id}

Delete a role that no user holds (B-40, D10).

```
Checks (read only, first failure stops):
 1. Role exists?                                         no  → 404 ROLE_NOT_FOUND
 2. System role?                                         yes → 409 SYSTEM_ROLE_IMMUTABLE
 3. Any user_roles rows (ACTIVE, INACTIVE or LOCKED)?    yes → 409 ROLE_IN_USE

Write (one DB transaction):
 4. delete role_menus for the role
 5. delete api_permissions WHERE permission_name = role name
 6. delete the role
 7. audit ROLE_DELETED, before = role + its menus + its permissions

After commit:
 8. Gateway sync → gateway deletes realm role <NAME> in Keycloak
 9. 204 No Content
```

INACTIVE users count as "in use": their roles are kept for re-activation, so the admin must first
remove the role from them.

| Status | Code | When |
|--------|------|------|
| 204 | — | Deleted and synced |
| 404 | `ROLE_NOT_FOUND` | Unknown id |
| 409 | `SYSTEM_ROLE_IMMUTABLE` | System role |
| 409 | `ROLE_IN_USE` | Held by any user |
| 502 | `KEYCLOAK_SYNC_FAILED` | Deleted, but sync failed (D10 message, §5.4). `/roles/{id}/sync` can't be used (the role is gone); the next successful sync of **any** role cleans Keycloak |

---

### 3.5 Role → menus

Role → menu is a soft control: no Keycloak or gateway call (S7).

#### GET /roles/{id}/menus

**Response 200:** a flat list.

```json
{
  "data": [
    { "id": "5d1c…", "key": "dashboard", "label": "Dashboard", "type": "NAV", "parentId": null },
    { "id": "8a27…", "key": "compliance", "label": "Compliance", "type": "NAV", "parentId": null }
  ]
}
```

| Status | Code | When |
|--------|------|------|
| 404 | `ROLE_NOT_FOUND` | Unknown role |

#### PUT /roles/{id}/menus

**Request body:** `{ "menuIds": ["…", "…"] }` — full new set (§2.3).

| Rule | Detail |
|------|--------|
| Duplicate ids | Treated as one |
| Empty list | Allowed — the role's users see an empty sidebar (D6) |
| Inactive menus | Can be assigned; `/me/menus` hides them |
| DETAIL without its NAV parent | Saved, with a warning in `data.warnings` (B-41) |
| System role | 409 `SYSTEM_ROLE_IMMUTABLE` |

**Response 200:**

```json
{
  "data": {
    "menus": [
      { "id": "e2b9…", "key": "patient-detail", "label": "Patient detail", "type": "DETAIL", "parentId": "91d4…" }
    ],
    "warnings": [
      "DETAIL 'patient-detail' given without its parent 'patients'; it will not show in the sidebar."
    ]
  }
}
```

| Status | Code | When |
|--------|------|------|
| 200 | — | Saved; audit `ROLE_MENUS_UPDATED` with menu keys before/after. Same set → no audit |
| 400 | `VALIDATION_ERROR` | Unknown menu id (the message names the ids) |
| 404 | `ROLE_NOT_FOUND` | Unknown role |
| 409 | `SYSTEM_ROLE_IMMUTABLE` | System role |

---

### 3.6 Role → permissions

Role → permission is a hard control: what the gateway lets through (B-42).

#### GET /roles/{id}/permissions

**Response 200:**

```json
{
  "data": [
    {
      "id": 41,
      "httpMethod": "GET",
      "uriPattern": "/admin/v1/me/menus",
      "description": null,
      "resourceCategory": null
    }
  ]
}
```

| Status | Code | When |
|--------|------|------|
| 404 | `ROLE_NOT_FOUND` | Unknown role |

#### PUT /roles/{id}/permissions

Replace the role's permissions, then sync to the gateway (B-42, S11).

**Request body:**

```json
{
  "permissions": [
    { "httpMethod": "GET", "uriPattern": "/admin/v1/me/menus", "description": "Load sidebar", "resourceCategory": null },
    { "httpMethod": "GET", "uriPattern": "/admin/v1/users", "description": null, "resourceCategory": null }
  ]
}
```

| Field | Rule |
|-------|------|
| `httpMethod` | One of `GET`, `POST`, `PUT`, `PATCH`, `DELETE` |
| `uriPattern` | Starts with `/`, max 512 characters; Ant-style pattern as the gateway matches it |
| `description`, `resourceCategory` | Optional |
| `permissionName` | Ignored if sent — rows are always stored under the role's own name (D11) |

Duplicate (method, uriPattern) pairs are saved once.

**Check order** (nothing written until step 6):

```
1. Role exists?                                  no  → 404 ROLE_NOT_FOUND
2. System role?                                  yes → 409 SYSTEM_ROLE_IMMUTABLE
3. Body valid (method, uriPattern)?              no  → 400 VALIDATION_ERROR
4. List empty?                                   yes → 400 VALIDATION_ERROR                 (D4)
5. No row matching GET /admin/v1/me/menus?       yes → 400 VALIDATION_ERROR, D6 message     (D6)
6. One transaction: delete rows WHERE permission_name = role name, insert the new rows
   with permission_name = role name, audit ROLE_PERMISSIONS_UPDATED                         (D11)
7. Gateway sync → 200, or 502 "Permissions saved. <cause> Retry with POST /admin/v1/roles/{id}/sync."
```

Step 5 uses Ant-style matching (`AntPathMatcher`), like the gateway: `/admin/v1/me/menus`,
`/admin/v1/me/*` and `/admin/v1/**` all count. Rows of other permission sets (e.g.
`COLLECTOR_EVENTS_WRITE`) are never touched. Same list as now → 200, no write, no audit, no sync
(use `/sync` to retry a sync).

| Status | Code | When |
|--------|------|------|
| 200 | — | Saved and synced. Body confirmed in S11 |
| 400 | `VALIDATION_ERROR` | Bad method or pattern; empty list; `/me/menus` row missing |
| 404 | `ROLE_NOT_FOUND` | Unknown role |
| 409 | `SYSTEM_ROLE_IMMUTABLE` | System role |
| 502 | `KEYCLOAK_SYNC_FAILED` | Saved, but sync failed — rows and audit stay; retry with `/sync` |

#### POST /roles/{id}/sync

Re-run the gateway sync by hand (B-43). No body. Writes no audit row (nothing changes in our DB).
Works for system roles too — sync is global: the gateway reloads **all** rows, not just this
role's.

| Status | Code | When |
|--------|------|------|
| 200 | — | Sync OK. Body confirmed in S11 |
| 404 | `ROLE_NOT_FOUND` | Unknown role |
| 502 | `KEYCLOAK_SYNC_FAILED` | `Sync failed. <cause> Retry with POST /admin/v1/roles/{id}/sync.` |

---

### 3.7 Menus

The catalog of `cce-insights-ui` screens (B-44, S5). There is no `GET /menus/{id}` (not in the
PRD API table).

#### GET /menus

The whole catalog as a tree: DETAIL items in their NAV parent's `children`, sorted by `sortOrder`,
**inactive menus included** (with `isActive`), not paginated. (`/me/menus` is the one that hides
inactive menus.) Node fields confirmed in S5.

#### POST /menus

**Request body:**

| Field | Type | Required | Rule |
|-------|------|----------|------|
| `key` | string | Yes | `^[a-z][a-z0-9-]*$` (e.g. `patient-detail`); unique; can never change |
| `label` | string | Yes | |
| `path` | string | Yes | |
| `type` | string | Yes | `NAV` or `DETAIL` |
| `parentId` | UUID | DETAIL only | NAV has no parent; DETAIL must have a NAV parent (no DETAIL under DETAIL) |
| `icon` | string | No | |
| `sortOrder` | int | — | Confirmed in S5 |
| `isActive` | boolean | — | Confirmed in S5 |

| Status | Code | When |
|--------|------|------|
| 201 | — | Created; audit `MENU_CREATED` |
| 400 | `VALIDATION_ERROR` | Missing label, path or type; bad key format; DETAIL without parent; NAV with parent; unknown `parentId`; parent is a DETAIL |
| 409 | `DUPLICATE_MENU_KEY` | Key exists (also when two requests race: one 201, one 409, never 500) |

#### PUT /menus/{id}

Change label, path, icon, sortOrder, isActive (and parent / type within the rules above).

| Status | Code | When |
|--------|------|------|
| 200 | — | Updated; audit `MENU_UPDATED` with before/after |
| 400 | `VALIDATION_ERROR` | Different `key` (same key is fine); a NAV with children turned into DETAIL; `parentId` = its own id |
| 404 | `MENU_NOT_FOUND` | Unknown id |

#### DELETE /menus/{id}

Deletes the menu and its `role_menus` rows (B-44).

| Status | Code | When |
|--------|------|------|
| 204 | — | Deleted; audit `MENU_DELETED`, `before` lists the role ids that had it |
| 400 | `VALIDATION_ERROR` | A NAV that still has DETAIL children ("delete or move its DETAIL menus first"); nothing deleted |
| 404 | `MENU_NOT_FOUND` | Unknown id |

---

## 4. Request & Response Examples

All examples call the service directly on :8085 with a local token. Ids are examples.

```bash
TOKEN=$(./local/get-token.sh)          # superadmin, valid 5 minutes (developer-setup §9)
```

### 4.1 Load the sidebar

```bash
curl -s http://localhost:8085/admin/v1/me/menus -H "Authorization: Bearer $TOKEN"
```

SUPER_ADMIN has no menus (S2), so its answer is `{"data": []}` — expected, not an error (D6). For a
user whose roles have menus, see §3.2.

### 4.2 Create a role

```bash
curl -s -X POST http://localhost:8085/admin/v1/roles \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "FACILITY_ADMIN", "displayName": "Facility admin", "description": "Runs one facility"}'
```

**201 Created.** The role has one permission row, `GET /admin/v1/me/menus`, and the realm role
`FACILITY_ADMIN` exists in Keycloak.

**Gateway not running** (normal locally without the gateway):

```json
{
  "error": {
    "code": "KEYCLOAK_SYNC_FAILED",
    "message": "Role created. Gateway not reachable at http://localhost:8095. Retry with POST /admin/v1/roles/a91f…/sync."
  }
}
```

The role is saved (`HTTP 502`); start the gateway and call `POST /roles/{id}/sync`.

### 4.3 Save a role's permissions

```bash
curl -s -X PUT http://localhost:8085/admin/v1/roles/a91f…/permissions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"permissions": [
        {"httpMethod": "GET", "uriPattern": "/admin/v1/me/menus"},
        {"httpMethod": "GET", "uriPattern": "/admin/v1/users"}
      ]}'
```

**200 OK** when the sync succeeds. Leaving out the `/me/menus` row instead:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Every role must keep GET /admin/v1/me/menus, or its users cannot load their sidebar. Add it back to the list and save again. Nothing was changed."
  }
}
```

### 4.4 Create a user

```bash
curl -s -X POST http://localhost:8085/admin/v1/users \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"username": "alice", "email": "alice@example.org",
       "firstName": "Alice", "lastName": "Otieno",
       "roleIds": ["a91f…"]}'
```

**201 Created:**

```json
{
  "data": {
    "id": "7c3e…",
    "username": "alice",
    "email": "alice@example.org",
    "status": "ACTIVE",
    "roles": [{ "id": "a91f…", "name": "FACILITY_ADMIN", "displayName": "Facility admin" }]
  }
}
```

`roles` element shape as in `GET /users`; confirmed in S8. Alice gets no password; send her the
link with `POST /users/{id}/reset-password`.

**A step after Keycloak create failed, account removed again (`HTTP 502`):**

```json
{
  "error": {
    "code": "KEYCLOAK_SYNC_FAILED",
    "message": "User was not created. The Keycloak account was created but saving the user failed; it has been removed again. Nothing was changed. Try again."
  }
}
```

### 4.5 Give a role menus, with a warning

```bash
curl -s -X PUT http://localhost:8085/admin/v1/roles/a91f…/menus \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"menuIds": ["<dashboard id>", "<patient-detail id>"]}'
```

**200 OK**, saved, with `warnings: ["DETAIL 'patient-detail' given without its parent 'patients'; it will not show in the sidebar."]` (§3.5).

---

## 5. Error Responses

### 5.1 Envelope

Every error, planned or not, has this body (B-03, D12):

```json
{ "error": { "code": "<CODE>", "message": "<text for a person>" } }
```

Spring's default error body (`timestamp`, `status`, `error`, `path`) never reaches a caller. No
exception text, SQL, stack trace, host or class name is ever put in `message`.

### 5.2 Error codes

| HTTP | Code | When | Source |
|------|------|------|--------|
| 400 | `VALIDATION_ERROR` | Body or query failed validation (incl. broken JSON, wrong field type), or a business rule (D4, D6, D9, S5, S10) | PRD App. B |
| 401 | `UNAUTHENTICATED` | Missing, invalid or expired token | PRD App. B |
| 403 | `FORBIDDEN` | Caller not a local user, or not ACTIVE | PRD App. B |
| 404 | `USER_NOT_FOUND` / `ROLE_NOT_FOUND` / `MENU_NOT_FOUND` | Unknown id | PRD App. B |
| 404 | `NOT_FOUND` | Unknown URL (e.g. `/admin/v1/rolez`) | D12 (new) |
| 405 | `METHOD_NOT_ALLOWED` | Wrong method for a URL | D12 (new) |
| 409 | `DUPLICATE_ROLE_NAME` | Role name taken (incl. by a gateway permission set, D11) | PRD App. B |
| 409 | `DUPLICATE_MENU_KEY` | Menu key taken | PRD App. B |
| 409 | `EMAIL_IN_USE` | Email taken (any case), or Keycloak already has the username/email | PRD App. B |
| 409 | `USERNAME_IN_USE` | Username taken | S8 (new) |
| 409 | `ROLE_IN_USE` | Deleting a role still held by a user | PRD App. B |
| 409 | `SYSTEM_ROLE_IMMUTABLE` | Changing or deleting a system role (incl. its menus and permissions) | PRD App. B |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Body is not JSON (`Content-Type`) | D12 (new) |
| 500 | `INTERNAL_ERROR` | Anything unplanned (DB down, bug) | D12 (new) |
| 502 | `KEYCLOAK_SYNC_FAILED` | A Keycloak or gateway call failed — read `message` for what was saved | PRD App. B |

### 5.3 Fixed messages (400 / 401 / 409)

| Case | `message` |
|------|-----------|
| 401, any token problem | `Missing or invalid token.` — the actual reason is only in a DEBUG log |
| Bean validation (body or query) | `Invalid request: <field> <rule>.`, every failing field, A–Z, joined by `; ` — e.g. `Invalid request: name must not be blank.`, `Invalid request: pageSize must be less than or equal to 100.` |
| Value of the wrong type in a query parameter (`?page=abc`) | `Invalid request: page has an invalid value.` |
| Broken JSON, missing body, wrong field type in the body | `Request body is not valid JSON, or a field has the wrong type.` |
| 404 `NOT_FOUND`, unknown URL | `No endpoint GET /admin/v1/rolez.` (method and path of the request) |
| 405 `METHOD_NOT_ALLOWED` | `Method DELETE is not allowed for this URL.` |
| 415 `UNSUPPORTED_MEDIA_TYPE` | `Content-Type text/plain is not supported. Send JSON (Content-Type: application/json).` (type/subtype only; no `Content-Type` counts as `application/octet-stream`) |
| Any other 4xx Spring raises itself (e.g. 406 when `Accept` asks for XML) | Keeps that status, code `VALIDATION_ERROR`: `The request could not be processed (HTTP 406).` No reference id, no ERROR log. With `Accept: application/xml` the body is empty, since only JSON can be sent |
| Permissions without `/me/menus` (D6) | `Every role must keep GET /admin/v1/me/menus, or its users cannot load their sidebar. Add it back to the list and save again. Nothing was changed.` |
| Role rename (D9) | `Role name cannot be changed (it is used in Keycloak and the gateway). Only displayName and description can be edited. To use a different name, create a new role. Nothing was changed.` |
| Name used by a gateway permission set (D11, 409) | `This name is already used by an existing permission set in the gateway. Choose another name.` |
| Keycloak already has the account (S8 rule c, 409 `EMAIL_IN_USE`) | `Keycloak already has an account with this username or email that this service doesn't know. An operator must remove or link it.` |
| Reset password for a non-ACTIVE user (S10 rule g) | `Activate the user first.` |

Messages of other expected errors (404s, duplicate key, unknown menu ids) are confirmed in the
sub-task that raises them. Expected errors never carry a reference id (D12 point 6).

### 5.4 502 `KEYCLOAK_SYNC_FAILED` messages

The message always says what **was** saved, so the caller knows what to retry.

**Gateway sync failed** (D3, S6, S10, S11). `<cause>` is one of:

| Gateway reply | `<cause>` |
|---------------|-----------|
| 206 + `failedRoles` | `Gateway reloaded its rules, but Keycloak role sync failed for: A, B.` |
| 500 (or other status) + `message` | `Gateway sync error: <gateway message>.` |
| Other status, no message | `Gateway sync error: HTTP <status>.` |
| Connection refused | `Gateway not reachable at <url>.` |
| No reply in 10s | `Gateway did not answer within 10s.` |

| Endpoint | `message` |
|----------|-----------|
| `POST /roles` | `Role created. <cause> Retry with POST /admin/v1/roles/{id}/sync.` |
| `PUT /roles/{id}/permissions` | `Permissions saved. <cause> Retry with POST /admin/v1/roles/{id}/sync.` |
| `POST /roles/{id}/sync` | `Sync failed. <cause> Retry with POST /admin/v1/roles/{id}/sync.` |
| `DELETE /roles/{id}` | `Role deleted. Keycloak still has realm role <NAME> until the next successful sync of any role. No user in this service holds it.` |

**Keycloak call failed** (D8, S8, S9, S10):

| Case | `message` |
|------|-----------|
| Create user, Keycloak could not create the account | `User was not created. Keycloak could not create the account: <reason>. Nothing was changed. Try again.` |
| Create user, later step failed, undo worked | `User was not created. The Keycloak account was created but saving the user failed; it has been removed again. Nothing was changed. Try again.` |
| Create user, later step failed, undo failed | `User was not created. The Keycloak account <keycloakId> was created but could not be removed. An operator must delete it in Keycloak before retrying with the same username/email.` |
| Update profile, Keycloak failed | `Profile not changed. Keycloak error: <reason>. Nothing was changed.` |
| Update profile, commit failed after Keycloak | `Profile only partly applied: Keycloak has the new values, the user record does not. An operator must fix it.` |
| Status / roles, first Keycloak call failed | `<Status\|Roles> not changed. Keycloak error: <reason>. Nothing was changed.` |
| Status, half-done | `Status change only partly applied: <which side succeeded>. Keycloak and the user record now differ; an operator must fix it.` |
| Roles, half-done | `Role change only partly applied in Keycloak. Keycloak and the user record now differ; an operator must fix it.` |
| Reset password, Keycloak failed | Text confirmed in S10 |

Every half-done case also writes an ERROR log and a `*_FAILED` audit row.

### 5.5 Internal error (500)

```
HTTP/1.1 500 Internal Server Error
Content-Type: application/json

{
  "error": {
    "code": "INTERNAL_ERROR",
    "message": "Something went wrong on our side. The change in this request was not saved. Try again; if it keeps failing, report reference 7f3a9c2e."
  }
}
```

The same 8-character reference is on the ERROR log line with the full exception (D12):

```
ERROR [ref=7f3a9c2e] PUT /admin/v1/roles/r-2/permissions failed
      <full exception and stack trace>
```

"Was not saved" holds because each request's DB work is one transaction, and every step that
reaches Keycloak or the gateway is caught as a 502 instead.

### 5.6 403 from the gateway vs 403 from this service

| Who answers | Why | Body |
|-------------|-----|------|
| gateway-service | The caller's role has no `api_permissions` row for this method + URL | The gateway's own; not defined here |
| cce-admin-service | Valid token, but the caller is not in `users` or not ACTIVE | `{"error":{"code":"FORBIDDEN", …}}` |

---

## 6. Downstream Calls (Outbound)

### 6.1 Gateway sync

```
POST {GATEWAY_BASE_URL}/api/permissions/sync-to-keycloak
```

- **No `Authorization` header** — the gateway's endpoint has no auth (D3).
- 10s timeout; called **once**, never retried.
- Global: the gateway reads all `api_permissions` rows, reloads its rules, then creates realm roles
  for every `permission_name` and deletes realm roles with no rows (D4).
- `GATEWAY_BASE_URL` local default `http://localhost:8095` (D3).

| Gateway reply | Result |
|---------------|--------|
| 200 + `success: true` | Success |
| 200 + `success: false`, or a 200 body that is not JSON | Failure (reason text confirmed in S6a) |
| 206 + `success: false` + `failedRoles` | Failure — rules already reloaded, some Keycloak roles failed |
| 500 + `message` | Failure |
| Any other status | Failure |
| Connection refused / no reply in 10s | Failure |

Every failure → one WARN log with role id, URL, status and the full gateway response body, then
502 to the caller (§5.4).

### 6.2 Keycloak Admin API

Through `KeycloakClient` (implemented on `keycloak-admin-client`), token from the
`client_credentials` grant of client `cce-admin-service`, realm `KEYCLOAK_REALM`, 10s timeout.
Each method is called at most once per request.

| Method | What it does in Keycloak | Sub-task | Used by |
|--------|--------------------------|----------|---------|
| `findUserByUsername(username)` | Exact-match username search (`GET /admin/realms/cce/users?username=…&exact=true`, tested in S0) | S2a | Bootstrap admin |
| `hasRealmRole(keycloakId, roleName)` | Checks effective realm roles (composite / group roles count) | S2a | Bootstrap admin |
| `createUser(…)` | Creates an enabled account with required action `UPDATE_PASSWORD`, no credentials | S8 | `POST /users` |
| `assignRealmRoles(keycloakId, names)` | Adds realm-role mappings | S8 | `POST /users`, `PUT /users/{id}/roles` |
| `deleteUser(keycloakId)` | Deletes the account (create-user undo only) | S8 | `POST /users` |
| `updateUser(keycloakId, email, firstName, lastName)` | Updates profile fields | S9 | `PUT /users/{id}` |
| `setEnabled(keycloakId, enabled)` | Enables / disables the account | S10 | `PATCH /users/{id}/status` |
| `removeRealmRoles(keycloakId, names)` | Removes realm-role mappings | S10 | `PUT /users/{id}/roles` |
| `sendUpdatePasswordEmail(keycloakId)` | Execute-actions email with `UPDATE_PASSWORD` | S10 | `POST /users/{id}/reset-password` |

Exact Keycloak REST paths behind each method are confirmed in the sub-task that adds it.

---

## 7. Not in v1

| Item | Why |
|------|-----|
| Audit-log read endpoint | PRD: not in CCE v2; operators query `audit_log` with SQL |
| Global permission catalog (`GET /permissions`) | PRD: permissions are viewed and edited per role only |
| `GET /menus/{id}` | Not in the PRD API table |
| Spring Boot Actuator endpoints (`/actuator/**`) | Not in the SPEC; health is `/admin/v1/health` |
| Facility-scope enforcement | PRD: `facilityScope` is stored and shown only |
