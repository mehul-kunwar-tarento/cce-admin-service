-- Tables of cce-admin-service.
-- Primary keys are UUIDs, except api_permissions (shape owned by gateway-service).
-- Foreign keys have no ON DELETE CASCADE: the service deletes child rows itself, in a known order.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    keycloak_id    VARCHAR(36)  NOT NULL,
    username       VARCHAR(255) NOT NULL,
    email          VARCHAR(255) NOT NULL,
    first_name     VARCHAR(255),
    last_name      VARCHAR(255),
    status         VARCHAR(20)  NOT NULL,
    facility_scope TEXT,
    last_login_at  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_keycloak_id UNIQUE (keycloak_id),
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'LOCKED'))
);

-- Same email in a different case counts as the same email
CREATE UNIQUE INDEX uk_users_email_lower ON users (lower(email));

CREATE TABLE roles (
    id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    name         VARCHAR(100) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    description  TEXT,
    is_system    BOOLEAN      NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_roles PRIMARY KEY (id),
    CONSTRAINT uk_roles_name UNIQUE (name),
    CONSTRAINT ck_roles_name CHECK (name ~ '^[A-Z][A-Z0-9_]*$')
);

CREATE TABLE menus (
    id         UUID         NOT NULL DEFAULT gen_random_uuid(),
    key        VARCHAR(100) NOT NULL,
    label      VARCHAR(255) NOT NULL,
    path       VARCHAR(512) NOT NULL,
    icon       VARCHAR(100),
    type       VARCHAR(10)  NOT NULL,
    parent_id  UUID,
    sort_order INT          NOT NULL,
    is_active  BOOLEAN      NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_menus PRIMARY KEY (id),
    CONSTRAINT uk_menus_key UNIQUE (key),
    CONSTRAINT ck_menus_type CHECK (type IN ('NAV', 'DETAIL')),
    CONSTRAINT fk_menus_parent FOREIGN KEY (parent_id) REFERENCES menus (id)
);

CREATE TABLE role_menus (
    role_id    UUID        NOT NULL,
    menu_id    UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_role_menus PRIMARY KEY (role_id, menu_id),
    CONSTRAINT fk_role_menus_role FOREIGN KEY (role_id) REFERENCES roles (id),
    CONSTRAINT fk_role_menus_menu FOREIGN KEY (menu_id) REFERENCES menus (id)
);

-- Deleting a menu looks up its rows by menu_id; the primary key only helps lookups by role_id
CREATE INDEX ix_role_menus_menu_id ON role_menus (menu_id);

CREATE TABLE user_roles (
    user_id    UUID        NOT NULL,
    role_id    UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id)
);

-- Deleting a role first checks whether any user still holds it
CREATE INDEX ix_user_roles_role_id ON user_roles (role_id);

-- Owned by gateway-service, which reads it. On servers it already exists (created from
-- deploy-scripts/k8s/seeds/api_permissions.sql), so this is skipped there. On a fresh database
-- nothing else creates it. Same shape as that seed; never altered, no index added.
CREATE TABLE IF NOT EXISTS api_permissions (
    id                BIGSERIAL    PRIMARY KEY,
    permission_name   VARCHAR(255) NOT NULL,
    http_method       VARCHAR(10)  NOT NULL,
    uri_pattern       VARCHAR(512) NOT NULL,
    description       TEXT,
    resource_category VARCHAR(255),
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now()
);

-- Append-only history. No foreign keys, so startup (SYSTEM) rows and failure rows
-- never break on a missing user.
CREATE TABLE audit_log (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    actor_id       UUID,
    actor_username VARCHAR(255) NOT NULL,
    action         VARCHAR(50)  NOT NULL,
    entity_type    VARCHAR(50)  NOT NULL,
    entity_id      VARCHAR(64)  NOT NULL,
    before         JSONB,
    after          JSONB,
    ip_address     VARCHAR(45),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_audit_log PRIMARY KEY (id)
);

CREATE INDEX ix_audit_log_entity ON audit_log (entity_type, entity_id);
CREATE INDEX ix_audit_log_created_at ON audit_log (created_at);
