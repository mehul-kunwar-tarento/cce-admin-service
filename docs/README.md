# cce-admin-service — Design Docs

Backend for the CCE v2 Admin Module: users, roles, menus and API permissions, kept in step
with Keycloak.

## Documentation

Design docs, written before the code; each sub-task updates the parts it builds.

- [architecture-overview.md](architecture-overview.md) — system context, principles, package layout, error handling, security, deployment
- [api-reference.md](api-reference.md) — every endpoint, request/response, error codes and messages, outbound calls
- [data-dictionary.md](data-dictionary.md) — tables, seed data, request/response models, configuration, audit actions
- [flow-diagrams.md](flow-diagrams.md) — request, create-user, gateway-sync, role and bootstrap flows (Mermaid)
- [developer-setup.md](developer-setup.md) — local environment, running and testing the service

## How to read these docs

The docs refer to decisions, build steps and requirements by short codes. This section explains
them, so the docs can be read without SPEC.md. The SPEC holds the full reasoning and
evidence behind each one.

### Legend

| Code | Meaning | Where it is defined |
|------|---------|---------------------|
| `D<n>` | A design decision, e.g. D3 | SPEC §2 — summarised below |
| `S<n>` | A sub-task: one build step, one commit, e.g. S8 | SPEC §4 — listed below |
| `B-<n>` | A backend requirement in the PRD, e.g. B-31 | CCE v2 Admin Module PRD §7 |
| `F-<n>` | A frontend requirement in the PRD (admin UI, out of scope here) | PRD §8 |
| `Q<n>` | A question asked of the lead, not yet answered | SPEC §3 |
| `PRD §<n>`, `PRD App. B` | A section of the PRD; Appendix B is its error-code table | PRD |
| "(confirmed in S<n>)" | A value that only exists once that sub-task is built (a class name, a default, an exact message). The docs give the planned design; the sub-task fills in the real value | — |
| "Not in v1" | Deliberately not built in this version | — |
| Iteration 1 / 2 | 1 = built and shown locally (this design); 2 = deployed to dev | — |

### Decisions

| # | Status | Decision |
|---|--------|----------|
| D1 | Resolved | `api_permissions.id` is `BIGSERIAL` (a number), not UUID, because the gateway reads it as a number. The other 6 tables use UUID |
| D2 | Resolved | Our Flyway creates `api_permissions` only if it doesn't exist, in the shape the gateway and the server seed use, and never alters it |
| D3 | Resolved | Gateway sync: only `200` + `success:true` is success. Anything else (206, 500, other status, refused, no reply in 10s) → 502 `KEYCLOAK_SYNC_FAILED`; the DB change is kept and the message says what was saved; retry with `POST /admin/v1/roles/{id}/sync`. Local gateway: `http://localhost:8095` |
| D4 | Resolved | Every role always has at least one permission row. `POST /roles` adds `GET /admin/v1/me/menus` and syncs, so the role exists in Keycloak from the start; an empty permission list is rejected (400) |
| D5 | Resolved | First admin: Flyway seeds the `SUPER_ADMIN` role and its `/admin/v1/**` rows; at startup the service finds the user named in `BOOTSTRAP_ADMIN_USERNAME` in Keycloak and links it. If that fails it logs an ERROR and keeps running |
| D6 | Resolved | Every role must keep a `GET` row matching `/admin/v1/me/menus`, or its users can't load their sidebar; saving permissions without it → 400 |
| D7 | Resolved | `last_login_at` is taken from the token's `auth_time` by the security filter when it is newer; it never blocks a request and writes no audit row |
| D8 | Resolved | A change that succeeds in Keycloak but not in the DB (or the reverse) → 502, an ERROR log line and a `*_FAILED` audit row. Create user is undone once (the new Keycloak account is deleted); other cases are fixed by hand, with the DB as the source of truth |
| D9 | Resolved | A role's name can never change. `PUT /roles/{id}` with the same name is fine, a different name → 400; a system role → 409 first |
| D10 | Resolved | A role can be deleted only when no user holds it (INACTIVE users count). Its menus and permission rows go in the same transaction, then sync; if the sync fails, the next successful sync of any role cleans Keycloak |
| D11 | Resolved | `permission_name` = the role's name (a job role). Rows with other names, such as `COLLECTOR_EVENTS_WRITE`, are never read or changed; a new role can't take a name already used there (409) |
| D12 | Resolved | Every error is `{error:{code,message}}`. Four codes added to the PRD's list (`NOT_FOUND`, `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, `INTERNAL_ERROR`). A 500 carries a reference id that is also on the ERROR log line; internal details never reach the caller |
| D13 | **Open** (Q4) | Keycloak realm roles (PRD) or client roles (the deployed gateway)? Working default: realm roles. Must be answered before the dev deploy (iteration 2) |
| D14 | Resolved | Compared with Jay's data dictionary (PR #1) on 2026-11-19: our tables and the 12 menus (taken from `cce-insights-ui`) are kept; one index, `role_menus(menu_id)`, taken from his |

### Sub-tasks

Built in this order (PRD milestones in brackets). Only S0 is built so far.

| # | Sub-task |
|---|----------|
| S0 | Local environment (M0) — done |
| S1 | Project skeleton, health, response envelope, errors (B1) |
| S2 | Flyway schema + seed (B1) |
| S3 | Security: JWT, roles claim, local ACTIVE user (B1) |
| S4 | Audit writer (B2) |
| S2a | Bootstrap admin on startup (B2) |
| S6a | Gateway sync client (B2) |
| S5 | Menus CRUD (B2) |
| S6 | Roles CRUD + guards (B2) |
| S7 | Role → menus (B2) |
| S8 | Keycloak client + create user (B3) |
| S9 | Users list / get / update (B3) |
| S10 | User status, roles, reset password (B3) |
| S11 | Role → permissions + gateway sync (B4) |
| S12 | Current user: `/me`, `/me/menus` (B5) |
| S13 | End-to-end run of the PRD scenarios through a local gateway (E2E) |
